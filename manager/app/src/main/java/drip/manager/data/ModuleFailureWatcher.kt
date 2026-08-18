// ModuleFailureWatcher：增量检测模块加载失败 → 弹系统通知。Manager 进程级。
package drip.manager.data

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 模块加载失败增量检测 + 通知。
 *
 * - 每 15s 轮询 getModuleLoadFailures()
 * - 与 SharedPreferences 已通知集合对比，新增的失败 → 发通知
 * - 去重键：packageName + reason（同一模块同原因只通知一次）
 * - 通知内容：「<模块名> 加载失败，详情见日志」，点击跳日志页（携带 filter tag）
 */
object ModuleFailureWatcher {
    private const val TAG = "ModuleFailureWatcher"
    private const val POLL_INTERVAL_MS = 15_000L
    private const val PREFS_NAME = "module_failure_notified"
    private const val KEY_NOTIFIED_SET = "notified_set" // Set<String>，格式: "pkg:reason"

    private var scope: CoroutineScope? = null
    private var handler: Handler? = null

    /** 启动失败检测轮询。幂等，多次调用无副作用。 */
    fun startWatching(context: Context) {
        if (scope != null) return
        val appCtx = context.applicationContext
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        handler = Handler(Looper.getMainLooper())
        Log.i(TAG, "start watching")
        // 首次立即检查一次，然后周期轮询
        scope?.launch {
            checkAndNotify(appCtx)
            while (true) {
                delay(POLL_INTERVAL_MS)
                checkAndNotify(appCtx)
            }
        }
    }

    /** 停止检测轮询。 */
    fun stopWatching() {
        scope?.cancel()
        scope = null
        handler = null
        Log.i(TAG, "stop watching")
    }

    /** 检查新增失败并发送通知。 */
    private suspend fun checkAndNotify(context: Context) {
        if (!ManagerServiceClient.connected) return
        val failures = with(kotlinx.coroutines.Dispatchers.IO) {
            ManagerServiceClient.getModuleLoadFailures()
        }
        if (failures.isEmpty()) return
        val prefs = getPrefs(context)
        val notified = loadNotifiedSet(prefs)
        val pm = context.packageManager
        for (failure in failures) {
            val key = "${failure.packageName}:${failure.reason}"
            if (key in notified) continue
            // 新增失败 → 发通知
            val label = getModuleLabel(pm, failure.packageName)
            sendFailureNotification(context, failure.packageName, label, failure.reason)
            notified.add(key)
        }
        saveNotifiedSet(prefs, notified)
    }

    /** 获取模块显示名（label），查不到返回包名。 */
    private fun getModuleLabel(pm: PackageManager, packageName: String): String {
        return try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val label = pm.getApplicationLabel(appInfo).toString()
            if (label.isNotBlank()) label else packageName
        } catch (_: Throwable) {
            packageName
        }
    }

    /** 发送模块加载失败通知。 */
    private fun sendFailureNotification(
        context: Context,
        packageName: String,
        label: String,
        reason: Int,
    ) {
        try {
            createFailureNotificationChannel(context)
            val intent = buildFailureIntent(context, packageName, label)
            val pendingIntent = android.app.PendingIntent.getActivity(
                context,
                packageName.hashCode(),
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
            val reasonText = when (reason) {
                1 -> "未找到 APK"
                2 -> "模块不可用"
                3 -> "API 不支持"
                else -> "未知原因($reason)"
            }
            // Notification.Builder + Icon.createWithBitmap；
            // 独立模式：NotificationCompat.Builder + 资源 ID（标准路径）。
            val notification =
                if (isParasiticHostProcess()) {
                    android.app.Notification.Builder(context, FAILURE_CHANNEL_ID)
                        .setSmallIcon(dripSmallIcon(context)!!)
                        .setContentTitle("模块加载失败")
                        .setContentText("$label $reasonText，详情见日志")
                        .setContentIntent(pendingIntent)
                        .setAutoCancel(true)
                        .setCategory(android.app.Notification.CATEGORY_ERROR)
                        .build()
                } else {
                    androidx.core.app.NotificationCompat.Builder(
                        context,
                        FAILURE_CHANNEL_ID,
                    )
                        .setSmallIcon(drip.manager.R.drawable.ic_stat_drip)
                        .setContentTitle("模块加载失败")
                        .setContentText("$label $reasonText，详情见日志")
                        .setContentIntent(pendingIntent)
                        .setAutoCancel(true)
                        .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
                        .setCategory(androidx.core.app.NotificationCompat.CATEGORY_ERROR)
                        .build()
                }
            androidx.core.app.NotificationManagerCompat.from(context)
                .notify(packageName.hashCode(), notification)
            Log.i(TAG, "failure notification posted: $packageName ($reasonText)")
        } catch (e: Exception) {
            Log.w(TAG, "sendFailureNotification failed: $e", e)
        }
    }

    // ==== SharedPreferences 去重 ====

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun loadNotifiedSet(prefs: SharedPreferences): MutableSet<String> {
        val raw = prefs.getString(KEY_NOTIFIED_SET, null) ?: return mutableSetOf()
        return raw.split(",").filter { it.isNotBlank() }.toMutableSet()
    }

    private fun saveNotifiedSet(prefs: SharedPreferences, set: Set<String>) {
        prefs.edit().putString(KEY_NOTIFIED_SET, set.joinToString(",")).apply()
    }
}
