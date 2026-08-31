// InjectionFailureWatcher：检测注入失败标记 → 发系统通知 + 自动转储日志。Manager 进程级。
package drip.manager.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 注入失败检测 + 通知 + 日志自动转储。
 *
 * 机制：
 * - 启动后立即检查 isInjectionFailed()
 * - 若 daemon 报告 relay_fail / bridge_fail 标记存在 → 发通知 + 自动 dumpLogs 一次
 * - 去重：标记存在期间只通知一次（SharedPreferences 记录已通知状态）
 * - 通知内容：「Drip注入失败」+ 正文「崩溃日志已保存\n可联系开发者，提供 /Download/Driplog 日志文件」
 * - 点击通知 → 打开 manager（buildManagerOpenIntent）
 */
object InjectionFailureWatcher {
    private const val TAG = "InjectionFailureWatcher"
    private const val POLL_INTERVAL_MS = 15_000L
    private const val PREFS_NAME = "injection_failure_notified"
    private const val KEY_NOTIFIED = "notified" // Boolean，标记存在期间只通知一次

    private var scope: CoroutineScope? = null

    /** 启动注入失败检测。幂等，多次调用无副作用。 */
    fun startWatching(context: Context) {
        if (scope != null) return
        val appCtx = context.applicationContext
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        Log.i(TAG, "start watching")
        // 首次立即检查，然后周期轮询（标记可能在 framework 注入后才出现）
        scope?.launch {
            checkAndNotify(appCtx)
            while (true) {
                delay(POLL_INTERVAL_MS)
                checkAndNotify(appCtx)
            }
        }
    }

    /** 停止检测。 */
    fun stopWatching() {
        scope?.cancel()
        scope = null
        Log.i(TAG, "stop watching")
    }

    /** 检查注入失败并发送通知。 */
    private suspend fun checkAndNotify(context: Context) {
        if (!ManagerServiceClient.connected) return
        val failed = with(Dispatchers.IO) {
            ManagerServiceClient.isInjectionFailed()
        }
        if (!failed) return
        val prefs = getPrefs(context)
        if (prefs.getBoolean(KEY_NOTIFIED, false)) return // 已通知过，去重
        // 注入失败 → 发通知 + 自动转储日志
        sendInjectionFailureNotification(context)
        autoDumpLogs(context)
        prefs.edit().putBoolean(KEY_NOTIFIED, true).apply()
        Log.i(TAG, "injection failure notification posted")
    }

    /** 发送注入失败通知。 */
    private fun sendInjectionFailureNotification(context: Context) {
        try {
            createInjectionFailureChannel(context)
            val intent = buildManagerOpenIntent(context).apply {
                flags = android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingIntent = android.app.PendingIntent.getActivity(
                context,
                INJECTION_FAILURE_NOTIFICATION_ID,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
            // 寄生模式：Notification.Builder + Icon.createWithBitmap（绕过 system_server 资源解析）；
            // 独立模式：NotificationCompat.Builder + 资源 ID（标准路径）。
            val notification =
                if (isParasiticHostProcess()) {
                    android.app.Notification.Builder(context, INJECTION_FAILURE_CHANNEL_ID)
                        .setSmallIcon(dripSmallIcon(context)!!)
                        .setContentTitle("Drip注入失败")
                        .setContentText("崩溃日志已保存\n可联系开发者，提供 /Download/Driplog 日志文件")
                        .setContentIntent(pendingIntent)
                        .setAutoCancel(true)
                        .setCategory(android.app.Notification.CATEGORY_ERROR)
                        .build()
                } else {
                    androidx.core.app.NotificationCompat.Builder(
                        context,
                        INJECTION_FAILURE_CHANNEL_ID,
                    )
                        .setSmallIcon(drip.manager.R.drawable.ic_stat_drip)
                        .setContentTitle("Drip注入失败")
                        .setContentText("崩溃日志已保存\n可联系开发者，提供 /Download/Driplog 日志文件")
                        .setStyle(
                            androidx.core.app.NotificationCompat.BigTextStyle()
                                .bigText("崩溃日志已保存\n可联系开发者，提供 /Download/Driplog 日志文件")
                        )
                        .setContentIntent(pendingIntent)
                        .setAutoCancel(true)
                        .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                        .setCategory(androidx.core.app.NotificationCompat.CATEGORY_ERROR)
                        .build()
                }
            androidx.core.app.NotificationManagerCompat.from(context)
                .notify(INJECTION_FAILURE_NOTIFICATION_ID, notification)
            Log.i(TAG, "injection failure notification posted (id=$INJECTION_FAILURE_NOTIFICATION_ID)")
        } catch (e: Exception) {
            Log.w(TAG, "sendInjectionFailureNotification failed: $e", e)
        }
    }

    /** 自动转储日志（dumpLogs），成功 Toast 提示路径。 */
    private suspend fun autoDumpLogs(context: Context) {
        try {
            val path = with(Dispatchers.IO) {
                ManagerServiceClient.dumpLogs()
            }
            if (!path.isNullOrBlank()) {
                android.widget.Toast.makeText(
                    context,
                    "崩溃日志已保存: $path",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
                Log.i(TAG, "autoDumpLogs: $path")
            } else {
                Log.w(TAG, "autoDumpLogs: dumpLogs returned null/empty")
            }
        } catch (e: Exception) {
            Log.w(TAG, "autoDumpLogs failed: $e", e)
        }
    }

    // ==== SharedPreferences 去重 ====

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
