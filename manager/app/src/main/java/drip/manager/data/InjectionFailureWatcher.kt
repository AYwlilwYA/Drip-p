// InjectionFailureWatcher：检测注入失败标记 → 发系统通知 + 自动转储日志。Manager 进程级。
package drip.manager.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import drip.manager.IManagerService
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
 * - 启动后立即检查注入失败来源（[ManagerServiceClient.getInjectionFailureSource] 位掩码）
 * - 若 daemon 报告失败标记存在 → 按来源取文案发通知 + 自动 dumpLogs 一次
 * - 去重：同一来源存在期间只通知一次（SharedPreferences 记录已通知来源）
 * - 通知文案按来源拆分（设计卡 §5-4，第四批② 拍板）：已回退用户模式 / 已完成自动复原 /
 *   Drip注入失败 —— 只有真失败（relay / bridge / 复原失败）才用「Drip注入失败」
 * - 点击通知 → 打开 manager（buildManagerOpenIntent）
 */
object InjectionFailureWatcher {
    private const val TAG = "InjectionFailureWatcher"
    private const val POLL_INTERVAL_MS = 15_000L
    private const val PREFS_NAME = "injection_failure_notified"
    // 值 = 已通知的来源位掩码；来源变化（例如回退 → 真失败）时会再通知一次。
    private const val KEY_NOTIFIED_SOURCE = "notified_source"

    /** 真失败的既有正文（relay / bridge / 复原失败）。 */
    private const val BODY_LOG_SAVED =
        "崩溃日志已保存\n可联系开发者，提供 /Download/Driplog 日志文件"

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
        val (source, failed) = with(Dispatchers.IO) {
            val mask = ManagerServiceClient.getInjectionFailureSource()
            // daemon 侧位掩码尚未实现时回退 boolean 判据（协议降级不误报）。
            mask to (mask != IManagerService.FAIL_NONE || ManagerServiceClient.isInjectionFailed())
        }
        if (!failed) return
        val prefs = getPrefs(context)
        // -1 = 从未通知过（source 可能为 0 = daemon 侧位掩码未实现、只有 boolean 命中）
        if (prefs.getInt(KEY_NOTIFIED_SOURCE, -1) == source) return
        // 注入失败 → 发通知 + 自动转储日志（仅「已完成自动复原」无故障可导，不转储）
        sendInjectionFailureNotification(context, source)
        if (source != IManagerService.FAIL_OBF_RESTORED) {
            autoDumpLogs(context)
        }
        prefs.edit().putInt(KEY_NOTIFIED_SOURCE, source).apply()
        Log.i(TAG, "injection failure notification posted (source=$source)")
    }

    /**
     * 来源位掩码 → 通知文案（设计卡 §5-4 表）。位掩码同时命中多个来源时按「真失败 > 崩溃待
     * 反馈 > 已回退 > 已复原」取最严重的一项。
     */
    fun failureText(source: Int): Pair<String, String> = when {
        source and (IManagerService.FAIL_RELAY or
            IManagerService.FAIL_BRIDGE or
            IManagerService.FAIL_OBF_FAILED) != 0 -> "Drip注入失败" to BODY_LOG_SAVED
        source and IManagerService.FAIL_KMOD_CRASH_PENDING != 0 ->
            "当前内核不支持内核模式!" to "请联系作者并且反馈!将该压缩包发送至相关群聊或者提交issue!"
        source and IManagerService.FAIL_KMOD_FALLBACK != 0 ->
            "已回退用户模式" to "已进入用户模式，请重新验证"
        source and IManagerService.FAIL_OBF_RESTORED != 0 -> "已完成自动复原" to "无需处理"
        else -> "Drip注入失败" to BODY_LOG_SAVED
    }

    /** 发送注入失败通知（标题/正文按来源取，设计卡 §5-4）。 */
    private fun sendInjectionFailureNotification(context: Context, source: Int) {
        // 权限门统一在 StatusNotification.notificationPermissionGate（Android 13+ 无权限静默）。
        if (!notificationPermissionGate(context, "injection-failure")) return
        try {
            createInjectionFailureChannel(context)
            val (title, body) = failureText(source)
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
                        .setContentTitle(title)
                        .setContentText(body)
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
                        .setContentTitle(title)
                        .setContentText(body)
                        .setStyle(
                            androidx.core.app.NotificationCompat.BigTextStyle()
                                .bigText(body)
                        )
                        .setContentIntent(pendingIntent)
                        .setAutoCancel(true)
                        .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                        .setCategory(androidx.core.app.NotificationCompat.CATEGORY_ERROR)
                        .build()
                }
            // 「通知全走 daemon」（2026-09-16）：注入失败横幅改由 daemon 直发
            // （daemon 是 root 常驻，不受系统冻结影响）；这里只保留日志抓取等本地动作。
            // 保留 notification 变量的构建以避免大改，但不投递，防止出现重复横幅。
            @Suppress("UNUSED_EXPRESSION")
            notification
            // androidx.core.app.NotificationManagerCompat.from(context)
            //     .notify(INJECTION_FAILURE_NOTIFICATION_ID, notification)
            Log.i(
                TAG,
                "injection failure notification built (id=$INJECTION_FAILURE_NOTIFICATION_ID, title=$title)",
            )
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
