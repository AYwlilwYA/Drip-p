// ScopeRequestWatcher：轮询 daemon pending 作用域请求 → 弹「批准/拒绝」通知。Manager 进程级，
// 寄生/独立均适用。
//
// 机制（M25）：
// - 每 10s 轮询 ManagerServiceClient.getPendingScopeRequests()
// - 新增 pending 请求（requestId 去重）→ 发带「批准」「拒绝」按钮的系统通知
//   （照 ModuleInstallWatcher 寄生/独立双分支：寄生用 Bitmap Icon，独立用资源 ID）
// - 按钮是 PendingIntent.getBroadcast → 本 watcher 的 receiver → respondScopeRequest(id, allow)
// - daemon 不再 outstanding 该 id → 取消对应通知
// 通知本体点击照常打开 manager；channel 新建 drip_scope_request（不挤占模块安装/失败 channel）。
package drip.manager.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import drip.manager.R
import drip.manager.ScopeRequestInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ScopeRequestWatcher {

    private const val TAG = "ScopeRequestWatcher"
    private const val POLL_INTERVAL_MS = 10_000L

    /** 「批准」「拒绝」按钮广播 action（PendingIntent.getBroadcast 触发）。 */
    const val ACTION_APPROVE_SCOPE = "drip.manager.action.APPROVE_SCOPE"
    const val ACTION_DENY_SCOPE = "drip.manager.action.DENY_SCOPE"
    private const val EXTRA_REQUEST_ID = "request_id"
    private const val EXTRA_MODULE = "module"

    /** 作用域请求通知 channel（用户决策类，IMPORTANCE_HIGH 可响铃）。 */
    private const val SCOPE_CHANNEL_ID = "drip_scope_request"
    /** 通知 ID 基址：requestId 落低位段，避开状态通知 1001 / 注入失败 2001 / 模块安装高位段。 */
    private const val SCOPE_NOTIFICATION_ID_BASE = 0x6A000000

    private var scope: CoroutineScope? = null
    private var receiver: BroadcastReceiver? = null

    /** 当前已弹通知的 outstanding requestId（去重；仅主线程访问）。 */
    private val activeRequestIds = mutableSetOf<Int>()

    /** 启动轮询。幂等，多次调用无副作用。 */
    fun startWatching(context: Context) {
        if (scope != null) return
        val appCtx = context.applicationContext
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        registerReceiver(appCtx)
        Log.i(TAG, "start watching")
        scope?.launch {
            checkAndNotify(appCtx)
            while (true) {
                delay(POLL_INTERVAL_MS)
                checkAndNotify(appCtx)
            }
        }
    }

    /** 停止轮询并注销 receiver。 */
    fun stopWatching(context: Context) {
        scope?.cancel()
        scope = null
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        activeRequestIds.clear()
        Log.i(TAG, "stop watching")
    }

    /** 注册「批准/拒绝」广播 receiver（幂等，进程存活期间不注销）。 */
    private fun registerReceiver(context: Context) {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val action = intent.action
                if (action != ACTION_APPROVE_SCOPE && action != ACTION_DENY_SCOPE) return
                val id = intent.getIntExtra(EXTRA_REQUEST_ID, -1)
                if (id < 0) return
                val module = intent.getStringExtra(EXTRA_MODULE)
                handleResponse(ctx.applicationContext, id, action == ACTION_APPROVE_SCOPE, module)
            }
        }
        ContextCompat.registerReceiver(
            context, r,
            IntentFilter().apply {
                addAction(ACTION_APPROVE_SCOPE)
                addAction(ACTION_DENY_SCOPE)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED)
        receiver = r
    }

    /** 按钮点击 → 调 daemon respondScopeRequest，成功后取消对应通知。后台线程执行 daemon 调用。 */
    private fun handleResponse(context: Context, requestId: Int, allow: Boolean, module: String?) {
        scope?.launch {
            val ok = withContext(Dispatchers.IO) {
                ManagerServiceClient.respondScopeRequest(requestId, allow)
            }
            activeRequestIds.remove(requestId)
            cancelScopeNotification(context, requestId)
            Log.i(TAG, "respondScopeRequest(#$requestId, allow=$allow) -> $ok module=$module")
        }
    }

    /** 轮询 pending 请求：新增 → 弹通知；已消失 → 取消通知。 */
    private suspend fun checkAndNotify(context: Context) {
        if (!ManagerServiceClient.connected) return
        val requests = withContext(Dispatchers.IO) {
            ManagerServiceClient.getPendingScopeRequests()
        }
        val serverIds = requests.map { it.requestId }.toSet()
        val gone = activeRequestIds.filterNot { it in serverIds }
        for (id in gone) {
            activeRequestIds.remove(id)
            cancelScopeNotification(context, id)
        }
        for (req in requests) {
            if (req.requestId in activeRequestIds) continue
            val label = moduleLabel(context, req.modulePackage)
            showScopeRequestNotification(context, req, label)
            activeRequestIds.add(req.requestId)
        }
    }

    private fun moduleLabel(context: Context, pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Throwable) {
        pkg
    }

    private fun notificationId(requestId: Int): Int =
        SCOPE_NOTIFICATION_ID_BASE or (requestId and 0xFFFFFF)

    private fun createScopeNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            SCOPE_CHANNEL_ID,
            "作用域请求",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "模块运行时请求把新应用加入其作用域时提醒"
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun broadcastPi(context: Context, req: ScopeRequestInfo, action: String): PendingIntent {
        val approve = action == ACTION_APPROVE_SCOPE
        val intent = Intent(action)
            .setPackage(context.packageName)
            .putExtra(EXTRA_REQUEST_ID, req.requestId)
            .putExtra(EXTRA_MODULE, req.modulePackage)
        // requestCode 区分同一请求的批准/拒绝（PendingIntent 缓存 key）。
        val requestCode = req.requestId * 2 + if (approve) 0 else 1
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * 投递「作用域请求」系统通知。无 POST_NOTIFICATIONS 权限静默（与状态通知同约定）。
     * 通知本体点击 → 打开 manager；actions「批准」「拒绝」→ 本 watcher 广播。
     */
    private fun showScopeRequestNotification(context: Context, req: ScopeRequestInfo, label: String) {
        if (!notificationPermissionGate(context, "scope-request")) return
        try {
            createScopeNotificationChannel(context)
            val openIntent = buildManagerOpenIntent(context).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val openPi = PendingIntent.getActivity(
                context, req.requestId, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val approvePi = broadcastPi(context, req, ACTION_APPROVE_SCOPE)
            val denyPi = broadcastPi(context, req, ACTION_DENY_SCOPE)
            val text = "「$label」请求将 ${req.packages.joinToString("、")} 加入作用域"
            val notification =
                if (isParasiticHostProcess()) {
                    val smallIcon = dripSmallIcon(context) ?: return
                    // 寄生模式：用 Bitmap Icon（绕过 system_server 资源解析）
                    Notification.Builder(context, SCOPE_CHANNEL_ID)
                        .setSmallIcon(smallIcon)
                        .setContentTitle("作用域请求")
                        .setContentText(text)
                        .setStyle(Notification.BigTextStyle().bigText(text))
                        .setContentIntent(openPi)
                        .addAction(Notification.Action.Builder(smallIcon, "批准", approvePi).build())
                        .addAction(Notification.Action.Builder(smallIcon, "拒绝", denyPi).build())
                        .setAutoCancel(true)
                        .setCategory(Notification.CATEGORY_EVENT)
                        .setVisibility(Notification.VISIBILITY_PUBLIC)
                        .build()
                } else {
                    // 独立模式：用资源 ID
                    NotificationCompat.Builder(context, SCOPE_CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_stat_drip)
                        .setContentTitle("作用域请求")
                        .setContentText(text)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                        .setContentIntent(openPi)
                        .addAction(R.drawable.ic_stat_drip, "批准", approvePi)
                        .addAction(R.drawable.ic_stat_drip, "拒绝", denyPi)
                        .setAutoCancel(true)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setCategory(NotificationCompat.CATEGORY_EVENT)
                        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                        .build()
                }
            NotificationManagerCompat.from(context)
                .notify(notificationId(req.requestId), notification)
            Log.i(TAG, "scope request notification posted: #${req.requestId} ${req.modulePackage}")
        } catch (e: Exception) {
            // 权限事后被撤销等场景 notify() 抛 SecurityException：投递失败不影响主流程。
            Log.w(TAG, "showScopeRequestNotification failed: $e", e)
        }
    }

    private fun cancelScopeNotification(context: Context, requestId: Int) {
        NotificationManagerCompat.from(context).cancel(notificationId(requestId))
    }
}
