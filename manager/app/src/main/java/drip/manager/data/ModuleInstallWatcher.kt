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
import drip.manager.ui.EXTRA_ACTIVATE_MODULE
import drip.manager.ui.EXTRA_OPEN_MODULE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ModuleInstallWatcher {

    /**
     * 模块安装/更新通知的数据源。
     * [enabled] = 该模块已在 Drip 中启用（通知只提示「已更新 + 生效方式」，无「激活」action）；
     * [syncedScope] = 本次为已启用模块新增同步的推荐作用域应用数（仅 enabled 时有意义）。
     */
    data class PendingModule(
        val packageName: String,
        val name: String,
        val isUpdate: Boolean,
        val enabled: Boolean = false,
        val syncedScope: Int = 0,
    )

    /** 当前待激活模块；null = 无。保留供通知流程内部用（应用内横幅 UI 已移除）。 */
    val pendingModule = mutableStateOf<PendingModule?>(null)

    /** 模块通知 channel（装/更新模块是重要事件，IMPORTANCE_HIGH 可响铃/振动）。 */
    private const val MODULE_CHANNEL_ID = "module_update"
    /** 通知本体点击的 PendingIntent requestCode 基址（与 StatusNotification 的 0 区分）。 */
    private const val OPEN_MANAGER_REQUEST_CODE_BASE = 1000

    /**
     * requestCode **必须按「模块包 + 动作」区分**：PendingIntent 的身份 =
     * (type, requestCode, intent.filterEquals)，而 filterEquals **只比 component/action/categories、
     * 不比 extras** —— 所有通知的 component 都是同一个宿主 Activity，包名/动作全在 extras 里
     * ⇒ 共用一个常量会让全部通知变成**同一个** PendingIntent。
     * 后果（M38/M40 之后 extras 已是**动作指令**，不再是空 extras）：连续装两个未激活模块 A、B，
     * 点 A 的通知 → 弹窗显示 A 的名字（文案来自 A），实际执行的却是 `setModuleEnabled(B, true)`。
     * 同基址下每包 2 个槽（激活 / 打开设置页各一），hash 低 16 位。
     */
    private fun openManagerRequestCode(pkg: String, activate: Boolean): Int =
        OPEN_MANAGER_REQUEST_CODE_BASE + (pkg.hashCode() and 0xFFFF) * 2 + if (activate) 1 else 0
    /** 模块通知 ID 基址：包名 hash 落高位段，避开状态通知 ID 1001。 */
    private const val MODULE_NOTIFICATION_ID_BASE = 0x5A000000
    private const val TAG = "DripManager"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var receiver: BroadcastReceiver? = null

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

        // M38：「激活」不再走通知栏 action 按钮（那需要一个常驻的广播接收器），改为点通知本体
        // → 宿主 Activity + LAUNCH_MANAGER + EXTRA_ACTIVATE_MODULE → framework 重定向到
        // ScopeRequestDialogActivity 的激活模式。故此处只保留安装/更新广播接收器。
    }

    fun unregister(context: Context) {
        val r = receiver ?: return
        receiver = null
        context.unregisterReceiver(r)
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
                    // M44：「尚未激活 / 已更新」通知**已全部改由 daemon 直发**
                    // （ModuleUpdateNotifier）。本接收器保留的是**兜底层** —— daemon 缓存重建
                    // 通知 + 推荐作用域同步，见 detectPendingModule 内的注释。
                    //
                    // 为什么不在这里发：本接收器随 manager 进程存活，而 manager 是寄生的
                    // ——宿主 com.android.shell 是后台 cached 进程、由 daemon 按需拉起，
                    // **装模块那一刻宿主没在跑就整条错过且无补投**（2026-09-17 实测：
                    // 包事件 21:48:15，宿主 21:48:52 才起来，整整晚 37 秒）。
                    // 与 M34 横幅同一个坑（「通知全走 daemon」原则），当时只搬了横幅。
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
            // 保留的 app）。失败静默（同步失败不影响通知）。
            if (isUpdate) {
                val added = syncRecommendedScope(context, pkg)
                Log.i(TAG, "enabled module $pkg updated: recommended scope merge added $added app(s)")
            }
            // 「已启用模块已更新」横幅改由 **daemon 直发**（2026-09-16 用户拍板「通知全走 daemon」）：
            // manager 寄生的宿主是 cached 进程会被冻结 → PACKAGE_REPLACED 排队、横幅晚到约 1 分钟；
            // daemon（root）经寄生注册直接收包事件，即时投递。此处不再发通知，只做作用域同步。
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
     *
     * M38：**未激活**的模块 → 点通知本体直接弹「激活」弹窗（宿主 Activity + LAUNCH_MANAGER +
     * [EXTRA_ACTIVATE_MODULE] → framework 重定向到 `ScopeRequestDialogActivity` 激活模式）；
     * **已启用**（更新）的模块 → 点通知照旧打开 manager 主界面。
     * 寄生模式：Notification.Builder + Icon.createWithBitmap（绕过 system_server 资源解析）；
     * 独立模式：NotificationCompat.Builder + 资源 ID（标准路径）。
     */
    private fun showModuleNotification(context: Context, pending: PendingModule) {
        // 权限门统一在 StatusNotification.notificationPermissionGate（Android 13+ 无权限静默）。
        if (!notificationPermissionGate(context, "module")) return
        try {
            createModuleNotificationChannel(context)
            // M38：未激活模块的通知**点本体直接弹激活弹窗**（不再挂通知栏 action 按钮）。
            // 做法与作用域请求一致：宿主 Activity + LAUNCH_MANAGER + 激活 extra，
            // framework 侧按该 extra 重定向到 ScopeRequestDialogActivity 的激活模式。
            //
            // ⚠️ 未激活时**不能带 FLAG_ACTIVITY_SINGLE_TOP**：那会让 AMS 走宿主 Activity 的
            // onNewIntent 复用栈顶实例，ActivityClientRecord 不新建 ⇒ framework 的
            // ActivityClientRecord 重定向不发生 ⇒ 弹窗永远起不来（同 DaemonNotifier 的作用域请求注释）。
            // 已启用（更新）那条仍是普通「打开 manager」，保留 SINGLE_TOP 避免叠栈。
            val openIntent = buildManagerOpenIntent(context).apply {
                if (pending.enabled) {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                    // M40：已启用模块更新 → 点通知直达它的设置页，而不是 manager 首页
                    putExtra(EXTRA_OPEN_MODULE, pending.packageName)
                } else {
                    putExtra(EXTRA_ACTIVATE_MODULE, pending.packageName)
                }
            }
            val openPi = PendingIntent.getActivity(
                context,
                openManagerRequestCode(pending.packageName, activate = !pending.enabled),
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val title = if (pending.enabled || pending.isUpdate) "模块已更新" else "新模块已安装"
            val text = when {
                // 已启用模块：换了新版本，作用域内已运行进程仍是旧 dex —— 必须重启才生效
                pending.enabled -> buildString {
                    append("「${pending.name}」已更新")
                    if (pending.syncedScope > 0) append("，已同步 ${pending.syncedScope} 个推荐应用")
                    append("；作用域内应用重启进程后生效")
                }
                pending.isUpdate -> "「${pending.name}」已更新，尚未激活"
                else -> "「${pending.name}」已安装，尚未激活"
            }
            val notification =
                if (isParasiticHostProcess()) {
                    // 寄生模式：用 Bitmap Icon（绕过 system_server 资源解析）
                    val smallIcon = dripSmallIcon(context)!!
                    val b = android.app.Notification.Builder(context, MODULE_CHANNEL_ID)
                        .setSmallIcon(smallIcon)
                        .setContentTitle(title)
                        .setContentText(text)
                        .setStyle(
                            android.app.Notification.BigTextStyle().bigText(text)
                        )
                        .setContentIntent(openPi)
                    b.setAutoCancel(true)
                        .setCategory(android.app.Notification.CATEGORY_EVENT)
                        .setVisibility(android.app.Notification.VISIBILITY_PUBLIC)
                        .build()
                } else {
                    // 独立模式：用资源 ID
                    val nb = NotificationCompat.Builder(context, MODULE_CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_stat_drip)
                        .setContentTitle(title)
                        .setContentText(text)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                        .setContentIntent(openPi)
                    nb.setAutoCancel(true)
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
     * M38：激活动作已移到 [drip.manager.ui.ScopeRequestDialogActivity] 的激活模式
     * （点通知 → 弹窗 → 激活），此处不再有 activate()。原实现在激活后调用的
     * `applyRecommendedScope`（整表替换）也随之移除；弹窗改用 [syncRecommendedScope]
     * （只加不减，与「已启用模块更新时同步作用域」同一语义）——新装模块通常没有既有 scope，
     * 两者结果一致，但「只加不减」不会覆盖用户后续的手动增删。
     */

    /**
     * 推荐作用域同步（合并新增，不重置）。语义「只加不减」：
     * 当前 scope（staticScope + 用户 scope）并上模块新推荐包——新增推荐包自动加入；
     * 用户手动选的保留不覆盖；模块新版本移除的推荐包不删除（删除需用户在 UI 手动操作，
     * 防模块更新静默移除用户手动保留的 app）。返回新增写入的推荐包数。
     */
    internal suspend fun syncRecommendedScope(context: Context, pkg: String): Int {
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
