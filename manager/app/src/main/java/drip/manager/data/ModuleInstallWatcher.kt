// ModuleInstallWatcher：监听应用安装/更新广播，检测 Xposed 模块并投递「尚未激活」系统通知。
// 动态注册（RECEIVER_NOT_EXPORTED），检测与激活的 PM/daemon 调用都在后台线程。
package drip.manager.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import drip.manager.R
import drip.manager.ScopeEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ModuleInstallWatcher {

    /** 待激活的模块（通知数据源）。 */
    data class PendingModule(
        val packageName: String,
        val name: String,
        val isUpdate: Boolean,
    )

    /** 当前待激活模块；null = 无。保留供通知流程内部用（应用内横幅 UI 已移除）。 */
    val pendingModule = mutableStateOf<PendingModule?>(null)

    /** 「激活」通知 action 的广播 action + extra key（PendingIntent.getBroadcast 触发）。 */
    const val ACTION_ACTIVATE_MODULE = "drip.manager.action.ACTIVATE_MODULE"
    private const val EXTRA_PACKAGE = "package"
    private const val EXTRA_NAME = "name"

    /** 模块通知 channel（装/更新模块是重要事件，IMPORTANCE_HIGH 可响铃/振动）。 */
    private const val MODULE_CHANNEL_ID = "module_update"
    /** 通知本体点击打开 manager 的 PendingIntent requestCode（与 StatusNotification 的 0 区分）。 */
    private const val OPEN_MANAGER_REQUEST_CODE = 100
    /** 模块通知 ID 基址：包名 hash 落高位段，避开状态通知 ID 1001。 */
    private const val MODULE_NOTIFICATION_ID_BASE = 0x5A000000
    private const val TAG = "DripManager"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var receiver: BroadcastReceiver? = null
    private var activateReceiver: BroadcastReceiver? = null

    /** 进程级常驻：ensureRegistered 已注册（进程存活期间不注销）。 */
    @Volatile
    private var processRegistered = false

    /**
     * 进程级常驻注册：幂等，进程存活期间不随 Activity 生命周期注销。
     * MainActivity 首次进入 + ManagerServiceClient init/inject 兜底调用；寄生模式下
     * Activity 退出后进程被 daemon 保活，广播（模块安装/更新）仍能送达并触发 daemon
     * 缓存重建。进程死亡后 receiver 由系统自动回收，无需显式注销。
     */
    fun ensureRegistered(context: Context) {
        if (processRegistered) return
        // 应修 #2：registerReceiver 抛异常时不能把 processRegistered 置 true 造成永久 no-op；
        // 失败置回 false 并留日志，下次调用可重试。
        try {
            register(context)
            processRegistered = true
        } catch (t: Throwable) {
            processRegistered = false
            Log.w(TAG, "ensureRegistered failed, will retry: $t")
        }
    }

    /** 动态注册安装/更新广播 + 「激活」action（主线程调用；MainActivity 初始化时 register，销毁时 unregister）。 */
    fun register(context: Context) {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val pkg = intent.data?.schemeSpecificPart ?: return
                if (pkg == ctx.packageName) return
                val isUpdate = when (intent.action) {
                    Intent.ACTION_PACKAGE_REPLACED -> true
                    Intent.ACTION_PACKAGE_ADDED ->
                        intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                    else -> return
                }
                // goAsync：检测在后台线程跑，不阻塞 onReceive 主线程。
                handlePackageEvent(ctx, pkg, isUpdate, goAsync())
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(context, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiver = r

        // 「激活」action 接收器：通知栏点「激活」→ PendingIntent.getBroadcast 到此。
        // 广播由系统以本 app UID（PendingIntent 创建者身份）代为发送，NOT_EXPORTED 可收到。
        val ar = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != ACTION_ACTIVATE_MODULE) return
                val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return
                val name = intent.getStringExtra(EXTRA_NAME) ?: pkg
                // activate 内部自行 launch 到后台 scope，onReceive 返回后照常执行。
                activate(ctx, PendingModule(pkg, name, false))
            }
        }
        ContextCompat.registerReceiver(
            context, ar, IntentFilter(ACTION_ACTIVATE_MODULE), ContextCompat.RECEIVER_NOT_EXPORTED)
        activateReceiver = ar
    }

    fun unregister(context: Context) {
        val r = receiver ?: return
        receiver = null
        context.unregisterReceiver(r)
        activateReceiver?.let { context.unregisterReceiver(it) }
        activateReceiver = null
    }

    /** 检测在后台线程跑（PM/daemon 调用不阻塞 onReceive），命中则投递系统通知。 */
    private fun handlePackageEvent(
        context: Context,
        pkg: String,
        isUpdate: Boolean,
        pendingResult: BroadcastReceiver.PendingResult,
    ) {
        scope.launch {
            try {
                val pending = detectPendingModule(context.applicationContext, pkg, isUpdate)
                if (pending != null) {
                    pendingModule.value = pending
                    showModuleNotification(context.applicationContext, pending)
                }
            } finally {
                // goAsync 窗口必须收尾；检测内部已各自吞异常，finally 兜底防泄漏。
                pendingResult.finish()
            }
        }
    }

    /**
     * 判定是否值得弹通知：① 是 Xposed 模块（meta-data `xposedmodule` 优先，
     * daemon getAllModules 扫描兜底）② daemon 未启用（已激活过不重复弹）。
     * 非模块包 → notifyPackageChanged（daemon 按需重建）；
     * 已启用模块更新 → syncRecommendedScope（推荐作用域合并新增）。
     */
    private suspend fun detectPendingModule(
        context: Context,
        pkg: String,
        isUpdate: Boolean,
    ): PendingModule? {
        val allModules = try {
            ManagerServiceClient.getAllModules()
        } catch (_: Throwable) {
            emptyList()
        }
        val daemonModule = allModules.firstOrNull { it.packageName == pkg }
        val isModule = daemonModule != null || isXposedModuleViaMetaData(context, pkg)
        if (!isModule) {
            // 普通 app（非模块）安装/更新 → 通知 daemon 按需重建 ConfigCache。
            // 影响场景：该包在某启用模块 scope/staticScope 中（scope 需重算 uid/包名），
            // 或某启用模块开启 includeNewApps（新装 app 自动纳入）。daemon 内部做相关性
            // 判断（isScopeRelevant），避免无条件全量刷。失败静默（未连接时放弃，等后续
            // 事件/重启兜底）。
            notifyPackageChanged(pkg)
            return null
        }
        // 热重载：模块安装/更新（无论是否已启用）→ 通知 daemon 重建 ConfigCache，
        // 让新 fork 的 app 拿到最新模块 dex/作用域。失败静默（manager 未连 daemon 时放弃，
        // 等 daemon 下次启动/模块启停重建兜底），不阻塞广播流程（本函数在后台线程执行）。
        notifyDaemonModuleChanged(pkg)
        if (daemonModule?.enabled == true) {
            // 已启用模块更新（PACKAGE_REPLACED，不重新激活）→ 模块新版本的
            // 推荐作用域不会自动应用。此处同步「合并新增」：新推荐包并入 scope，用户手动
            // 选的保留不覆盖；模块新版本移除的推荐包不删（只加不减，防静默移除用户手动
            // 保留的 app）。失败静默，不弹通知。
            if (isUpdate) {
                val added = syncRecommendedScope(context, pkg)
                Log.i(TAG, "enabled module $pkg updated: recommended scope merge added $added app(s)")
            }
            return null
        }
        return PendingModule(pkg, resolveAppLabel(context, pkg), isUpdate)
    }

    /** 通知 daemon 按需重建缓存（普通 app 事件；daemon 内部判断相关性），失败静默。 */
    private fun notifyPackageChanged(pkg: String) {
        try {
            ManagerServiceClient.connect()
            if (ManagerServiceClient.notifyPackageChanged(pkg)) {
                Log.i(TAG, "notifyPackageChanged($pkg): daemon conditional rebuild requested")
            } else {
                Log.w(TAG, "notifyPackageChanged($pkg): daemon not connected, skip rebuild notify")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "notifyPackageChanged($pkg) failed: $t")
        }
    }

    /** 通知 daemon 重建缓存；未连接时先 connect 再调（与 activate 同约定），失败静默。 */
    private fun notifyDaemonModuleChanged(pkg: String) {
        try {
            ManagerServiceClient.connect()
            if (ManagerServiceClient.notifyModuleChanged(pkg)) {
                Log.i(TAG, "notifyModuleChanged($pkg): daemon cache rebuild requested")
            } else {
                Log.w(TAG, "notifyModuleChanged($pkg): daemon not connected, skip rebuild notify")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "notifyModuleChanged($pkg) failed: $t")
        }
    }

    /** 本地 PM 读 manifest meta-data `xposedmodule`（LSPosed 模块标准标记）。 */
    private fun isXposedModuleViaMetaData(context: Context, pkg: String): Boolean = try {
        val ai = context.packageManager.getApplicationInfo(pkg, PackageManager.GET_META_DATA)
        ai.metaData?.getBoolean("xposedmodule") == true
    } catch (_: Throwable) {
        false
    }

    private fun resolveAppLabel(context: Context, pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Throwable) {
        pkg
    }

    /** 模块通知 ID：包名 hash 落高位段，同包名更新覆盖旧通知、不同包互不冲突。 */
    private fun moduleNotificationId(pkg: String): Int =
        MODULE_NOTIFICATION_ID_BASE or (pkg.hashCode() and 0xFFFFFF)

    private fun createModuleNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            MODULE_CHANNEL_ID,
            "模块安装/更新",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "检测到新安装或更新的 Xposed 模块时提醒激活"
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    /**
     * 投递模块「尚未激活」系统通知。无 POST_NOTIFICATIONS 权限时静默失败（与状态通知同约定）。
     * 通知本体点击 → 打开 manager；action「激活」→ [ACTION_ACTIVATE_MODULE] 广播。
     * 寄生模式：Notification.Builder + Icon.createWithBitmap（绕过 system_server 资源解析）；
     * 独立模式：NotificationCompat.Builder + 资源 ID（标准路径）。
     */
    private fun showModuleNotification(context: Context, pending: PendingModule) {
        if (!hasNotificationPermission(context)) return
        try {
            createModuleNotificationChannel(context)
            val openIntent = buildManagerOpenIntent(context).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val openPi = PendingIntent.getActivity(
                context, OPEN_MANAGER_REQUEST_CODE, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val activateIntent = Intent(ACTION_ACTIVATE_MODULE)
                .setPackage(context.packageName)
                .putExtra(EXTRA_PACKAGE, pending.packageName)
                .putExtra(EXTRA_NAME, pending.name)
            val activatePi = PendingIntent.getBroadcast(
                context, pending.packageName.hashCode(), activateIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val text = if (pending.isUpdate) {
                "「${pending.name}」已更新，尚未激活"
            } else {
                "「${pending.name}」已安装，尚未激活"
            }
            val notification =
                if (isParasiticHostProcess()) {
                    // 寄生模式：用 Bitmap Icon（绕过 system_server 资源解析）
                    val smallIcon = dripSmallIcon(context)!!
                    val actionIcon = dripSmallIcon(context) ?: smallIcon
                    val action = android.app.Notification.Action.Builder(
                        actionIcon, "激活", activatePi,
                    ).build()
                    android.app.Notification.Builder(context, MODULE_CHANNEL_ID)
                        .setSmallIcon(smallIcon)
                        .setContentTitle(if (pending.isUpdate) "模块已更新" else "新模块已安装")
                        .setContentText(text)
                        .setStyle(
                            android.app.Notification.BigTextStyle().bigText(text)
                        )
                        .setContentIntent(openPi)
                        .addAction(action)
                        .setAutoCancel(true)
                        .setCategory(android.app.Notification.CATEGORY_EVENT)
                        .setVisibility(android.app.Notification.VISIBILITY_PUBLIC)
                        .build()
                } else {
                    // 独立模式：用资源 ID
                    NotificationCompat.Builder(context, MODULE_CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_stat_drip)
                        .setContentTitle(if (pending.isUpdate) "模块已更新" else "新模块已安装")
                        .setContentText(text)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                        .setContentIntent(openPi)
                        .addAction(R.drawable.ic_stat_drip, "激活", activatePi)
                        .setAutoCancel(true)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setCategory(NotificationCompat.CATEGORY_EVENT)
                        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                        .build()
                }
            NotificationManagerCompat.from(context)
                .notify(moduleNotificationId(pending.packageName), notification)
            Log.i(TAG, "module notification posted: ${pending.packageName}")
        } catch (e: Exception) {
            // 权限事后被撤销等场景 notify() 抛 SecurityException：投递失败不影响主流程。
            Log.w(TAG, "showModuleNotification failed: $e", e)
        }
    }

    private fun cancelModuleNotification(context: Context, pkg: String) {
        NotificationManagerCompat.from(context).cancel(moduleNotificationId(pkg))
    }

    /**
     * 激活模块：启用 + 自动应用推荐作用域（`drip_scope` 声明的包），成功后取消该模块通知。
     * 未连接 daemon 时 setModuleEnabled 返回 false → 提示失败、通知保留可稍后重试。
     */
    fun activate(context: Context, pending: PendingModule) {
        val appContext = context.applicationContext
        scope.launch {
            ManagerServiceClient.connect()
            val enabled = ManagerServiceClient.setModuleEnabled(pending.packageName, true)
            val scopeCount = if (enabled) applyRecommendedScope(appContext, pending.packageName) else 0
            withContext(Dispatchers.Main) {
                when {
                    !enabled -> Toast.makeText(
                        appContext, "激活失败（未连接 daemon）", Toast.LENGTH_SHORT).show()
                    scopeCount > 0 -> {
                        pendingModule.value = null
                        cancelModuleNotification(appContext, pending.packageName)
                        Toast.makeText(
                            appContext, "已激活并应用 $scopeCount 个推荐应用", Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        pendingModule.value = null
                        cancelModuleNotification(appContext, pending.packageName)
                        Toast.makeText(
                            appContext, "已激活（模块未声明推荐作用域）", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    /** 应用推荐作用域，返回写入成功的推荐包数；无推荐/未连接返回 0。 */
    private suspend fun applyRecommendedScope(context: Context, pkg: String): Int {
        val recommended = ManagerServiceClient.getModuleRecommendedScope(pkg)
        if (recommended.isEmpty()) return 0
        val entries = recommended.map { app ->
            ScopeEntry().apply {
                appPackageName = app
                userId = resolveUserId(context, app)
            }
        }
        val ok = ManagerServiceClient.setModuleScope(pkg, entries)
        return if (ok) entries.size else 0
    }

    /**
     * 模块更新后推荐作用域重新同步（合并新增，不重置）。语义「只加不减」：
     * 当前 scope（staticScope + 用户 scope）并上模块新推荐包——新增推荐包自动加入；
     * 用户手动选的保留不覆盖；模块新版本移除的推荐包不删除（删除需用户在 UI 手动操作，
     * 防模块更新静默移除用户手动保留的 app）。返回新增写入的推荐包数。
     */
    private suspend fun syncRecommendedScope(context: Context, pkg: String): Int {
        val recommended = ManagerServiceClient.getModuleRecommendedScope(pkg)
        if (recommended.isEmpty()) return 0
        val current = ManagerServiceClient.getModuleScope(pkg)
        val currentPkgs = current.map { it.appPackageName }.toSet()
        val toAdd = recommended.filterNot { it in currentPkgs }
        if (toAdd.isEmpty()) return 0
        val merged = current.toMutableList()
        toAdd.forEach { app ->
            merged += ScopeEntry().apply {
                appPackageName = app
                userId = resolveUserId(context, app)
            }
        }
        val ok = ManagerServiceClient.setModuleScope(pkg, merged)
        return if (ok) toAdd.size else 0
    }

    /** 推荐 app 的实际 userId（uid/100000，与 ScopeScreen 同约定）；查不到回落 0。 */
    private fun resolveUserId(context: Context, pkg: String): Int = try {
        val ai = context.packageManager.getApplicationInfo(pkg, 0)
        ai.uid.coerceAtLeast(0) / 100000
    } catch (_: Throwable) {
        0
    }
}
