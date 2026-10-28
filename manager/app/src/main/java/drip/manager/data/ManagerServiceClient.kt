// ManagerServiceClient：静态持有 IManagerService，真实调用路径 + 未连接留痕降级。
package drip.manager.data

import android.content.Context
import android.content.pm.PackageInfo
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import drip.manager.DeviceUser
import drip.manager.IFrameworkDumpReceiver
import drip.manager.IManagerService
import drip.manager.KernelStatus
import drip.manager.ModuleInfo
import drip.manager.ModuleLoadFailure
import drip.manager.ScopeEntry
import drip.manager.ScopeRequestInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * manager ↔ daemon 的 binder 静态持有（契约 §2 LSP 式）。
 *
 * daemon 未注入 binder 时：connect 失败 → connected=false，所有调用写文件留痕
 * （files/drip_mgr_calls.log）并返回默认值；UI 显示空态。daemon 注入后同一代码
 * 路径自动显示真实数据。
 */
object ManagerServiceClient {

    const val DESCRIPTOR = "drip.manager.IManagerService"
    const val PROTOCOL_VERSION = 2

    private const val LOG_FILE = "drip_mgr_calls.log"

    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var service: IManagerService? = null

    // Compose 可观察状态：connect 完成 / binder 死亡时自动触发相关页面重组，
    // 避免「异步连接成功后 UI 仍显示未连接」的过期状态。
    var connected: Boolean by mutableStateOf(false)
        private set

    /** 协议版本不匹配（daemon 协议更新，manager 需升级）。UI 可据此提示。 */
    var protocolMismatch: Boolean by mutableStateOf(false)
        private set

    private val deathRecipient = object : IBinder.DeathRecipient {
        override fun binderDied() {
            service = null
            connected = false
            // 会话复位：允许 daemon 重注入后由 StatusNotificationController 再次投递。
            StatusNotificationController.onDaemonBinderDied()
            log("binderDied", "daemon binder died, mark disconnected")
        }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        // 进程级广播检测——寄生注入先于 Activity 创建（init 时已 connected）则在此补注册。
        if (connected) appContext?.let { ModuleInstallWatcher.ensureRegistered(it) }
    }

    /** 注入通道：寄生框架注入的 IManagerService binder 优先作为已连接 service。 */
    fun injectManagerBinder(binder: IBinder?) {
        if (binder == null) return
        try {
            if (binder.interfaceDescriptor != DESCRIPTOR) {
                log("inject", "descriptor mismatch: ${binder.interfaceDescriptor}")
                return
            }
            val svc = IManagerService.Stub.asInterface(binder)
            if (svc.protocolVersion != PROTOCOL_VERSION) {
                protocolMismatch = true
                log("inject", "protocol mismatch: ${svc.protocolVersion}, need $PROTOCOL_VERSION")
                return
            }
            binder.linkToDeath(deathRecipient, 0)
            service = svc
            connected = true
            protocolMismatch = false
            // 进程级广播检测兜底（appContext 通常由 DripManagerApp.onCreate 先设置，此处防御）。
            appContext?.let { ModuleInstallWatcher.ensureRegistered(it) }
            // 独立触发通道：注入完成事件 → 通知控制器统一决策。注入恒早于 Application
            // 创建（appContext 尚 null），Context 由 DripManagerApp.onCreate 的 onAppReady
            // 事件补齐——此处不再内联投递逻辑（doc/spec/drip-m8-fix-boot-notification.md）。
            StatusNotificationController.onInjected()
            log("inject", "injected binder accepted, protocol=$PROTOCOL_VERSION")
        } catch (e: Throwable) {
            log("inject", "error: $e")
        }
    }

    /**
     * 连接（纯注入）：relay 事务码/descriptor + MGR_CODE 已随 daemon 启动随机化，
     * manager（无 root）读不到随机值（见 doc/spec/drip-m4-r15-relay-randomize.md §4），
     * 独立 relay→MGR_CODE 两步通道移除。连接只依赖注入：zygisk 注入（BRIDGE →
     * attachProcess 特判 manager → framework）→ `requestManagerService` →
     * [injectManagerBinder]。未注入则留痕降级，UI 显示未连接；进程重启后重新注入即重连。
     */
    fun connect() {
        if (connected) return
        fail("connect", "not injected (relay/MGR_CODE randomized, injection-only)")
    }

    // ==== 3.1 身份/版本 ====
    fun getProtocolVersion(): Int = call("getProtocolVersion", 0) { it.protocolVersion }
    fun getLibxposedApiVersion(): Int = call("getLibxposedApiVersion", 0) { it.libxposedApiVersion }
    fun getFrameworkVersionName(): String = call("getFrameworkVersionName", "") { it.frameworkVersionName }
    fun getFrameworkVersionCode(): Long = call("getFrameworkVersionCode", 0L) { it.frameworkVersionCode }
    fun getBuildStamp(): String = call("getBuildStamp", "") { it.buildStamp }

    // ==== 3.2 模块配置 ====
    fun getEnabledModules(): List<String> = call("getEnabledModules", emptyList()) { it.enabledModules }
    /** 已安装 Xposed 模块全量（含未启用的；enabled 状态区分显示）。 */
    fun getAllModules(): List<ModuleInfo> = call("getAllModules", emptyList()) { it.allModules }
    fun getModuleInfo(pkg: String): ModuleInfo? = call("getModuleInfo", null) { it.getModuleInfo(pkg) }
    fun getModuleRecommendedScope(pkg: String): List<String> =
        call("getModuleRecommendedScope", emptyList()) { it.getModuleRecommendedScope(pkg) }
    fun getStaticScope(pkg: String): List<String> =
        call("getStaticScope", emptyList()) { it.getStaticScope(pkg) }
    fun setModuleEnabled(pkg: String, enabled: Boolean): Boolean =
        call("setModuleEnabled", false) { it.setModuleEnabled(pkg, enabled) }
    fun getModuleScope(pkg: String): List<ScopeEntry> =
        call("getModuleScope", emptyList()) { it.getModuleScope(pkg) }
    fun setModuleScope(pkg: String, entries: List<ScopeEntry>): Boolean =
        call("setModuleScope", false) { it.setModuleScope(pkg, entries) }
    fun getIncludeNewApps(pkg: String): Boolean = call("getIncludeNewApps", false) { it.getIncludeNewApps(pkg) }
    fun setIncludeNewApps(pkg: String, value: Boolean): Boolean =
        call("setIncludeNewApps", false) { it.setIncludeNewApps(pkg, value); true }
    // ==== M20 per-module compat pristine 豁免 ====
    fun isModuleCompatPristine(pkg: String): Boolean =
        call("isModuleCompatPristine", false) { it.isModuleCompatPristine(pkg) }
    fun setModuleCompatPristine(pkg: String, enabled: Boolean): Boolean =
        call("setModuleCompatPristine", false) { it.setModuleCompatPristine(pkg, enabled); true }

    // ==== M22 per-module「兼容性增强」档位（int：0=B2 / 1=P0 / 2=PRISTINE 完全不混淆）====
    fun getModuleCompatMode(pkg: String): Int =
        call("getModuleCompatMode", 0) { it.getModuleCompatMode(pkg) }
    fun setModuleCompatMode(pkg: String, mode: Int): Boolean =
        call("setModuleCompatMode", false) { it.setModuleCompatMode(pkg, mode); true }

    // ==== M24 随机 log-tag 开关（默认开）====
    fun isRandomLogTagEnabled(): Boolean =
        call("isRandomLogTagEnabled", true) { it.isRandomLogTagEnabled() }
    fun setRandomLogTagEnabled(enabled: Boolean): Boolean =
        call("setRandomLogTagEnabled", false) { it.setRandomLogTagEnabled(enabled); true }

    fun getModuleLoadFailures(): List<ModuleLoadFailure> =
        call("getModuleLoadFailures", emptyList()) { it.moduleLoadFailures }
    fun getGlobalMode(): Boolean = call("getGlobalMode", false) { it.globalMode }
    fun setGlobalMode(enabled: Boolean): Boolean =
        call("setGlobalMode", false) { it.setGlobalMode(enabled); true }

    // ==== M25 动态作用域：pending 请求查询 / 响应 ====
    // （daemon 直发带 request_id 的通知 → 点开 ScopeRequestDialogActivity 弹窗 →
    //  这里查 pending / respondScopeRequest 落决定。）
    fun getPendingScopeRequests(): List<ScopeRequestInfo> =
        call("getPendingScopeRequests", emptyList()) { it.getPendingScopeRequests() }
    fun respondScopeRequest(requestId: Int, allow: Boolean): Boolean =
        call("respondScopeRequest", false) { it.respondScopeRequest(requestId, allow); true }

    // ==== 3.3 日志 ====
    fun getLogParts(verbose: Boolean): List<String> =
        call("getLogParts", emptyList()) { it.getLogParts(verbose) }
    fun getLogPart(verbose: Boolean, name: String): ParcelFileDescriptor? =
        call("getLogPart", null) { it.getLogPart(verbose, name) }
    fun getLiveLogPart(verbose: Boolean): ParcelFileDescriptor? =
        call("getLiveLogPart", null) { it.getLiveLogPart(verbose) }
    fun startNewLogPart(verbose: Boolean): Boolean =
        call("startNewLogPart", false) { it.startNewLogPart(verbose); true }
    /** 清空全部日志（主 part + 模块日志），daemon 重置并从新 part 开始。 */
    fun clearLogs(): Boolean = call("clearLogs", false) { it.clearLogs(); true }

    /** Fallback：PFD 传输 DeadObjectException 时，返回日志内容字符串（≤900KB）。 */
    fun getLogPartContent(verbose: Boolean, name: String): String? =
        call("getLogPartContent", null) { it.getLogPartContent(verbose, name) }

    // ==== 3.3.1 模块独立日志（M5：log/modules/ 按模块分文件）====
    fun getModuleNames(): List<String> =
        call("getModuleNames", emptyList()) { it.getModuleNames() }
    fun getModuleLog(moduleName: String): ParcelFileDescriptor? =
        call("getModuleLog", null) { it.getModuleLog(moduleName) }
    /** String 版：PFD 传输在部分 ROM 上 DeadObject，优先走此通道（≤900KB）。 */
    fun getModuleLogContent(moduleName: String): String? =
        call("getModuleLogContent", null) { it.getModuleLogContent(moduleName) }

    // ==== 3.4 设备视角 ====
    fun softReboot(): Boolean = call("softReboot", false) { it.softReboot() }
    fun reboot(): Boolean = call("reboot", false) { it.reboot(); true }
    fun getRootImplementation(): Int = call("getRootImplementation", 0) { it.rootImplementation }
    fun getUsers(): List<DeviceUser> = call("getUsers", emptyList()) { it.users }
    fun getInstalledPackagesFromAllUsers(flags: Int): List<PackageInfo> =
        call("getInstalledPackagesFromAllUsers", emptyList()) { it.getInstalledPackagesFromAllUsers(flags) }
    fun forceStopPackage(pkg: String, userId: Int): Boolean =
        call("forceStopPackage", false) { it.forceStopPackage(pkg, userId); true }
    fun uninstallPackage(pkg: String, userId: Int): Boolean =
        call("uninstallPackage", false) { it.uninstallPackage(pkg, userId) }

    // ==== 3.8 热重载：重启该模块作用域内所有 app ====
    fun restartModuleScopeProcesses(pkg: String): Boolean =
        call("restartModuleScopeProcesses", false) { it.restartModuleScopeProcesses(pkg); true }

    // ==== 3.9 热重载：模块安装/更新 → daemon 重建 ConfigCache ====
    // （ModuleInstallWatcher 广播检测到模块事件后调用；替代 daemon 侧轮询）。
    fun notifyModuleChanged(pkg: String): Boolean =
        call("notifyModuleChanged", false) { it.notifyModuleChanged(pkg); true }

    // ==== 3.10 普通 app（非模块）安装/更新 → daemon 按需重建 ConfigCache ====
    // （daemon 内部判断是否影响某模块作用域，避免无条件全量刷。）
    fun notifyPackageChanged(pkg: String): Boolean =
        call("notifyPackageChanged", false) { it.notifyPackageChanged(pkg); true }

    // ==== 3.5 通知/状态 ====
    fun isSystemServerAttached(): Boolean =
        call("isSystemServerAttached", false) { it.isSystemServerAttached() }
    fun isSepolicyLoaded(): Boolean =
        call("isSepolicyLoaded", false) { it.isSepolicyLoaded() }
    fun isStatusNotificationEnabled(): Boolean =
        call("isStatusNotificationEnabled", false) { it.isStatusNotificationEnabled() }
    fun setStatusNotificationEnabled(enabled: Boolean): Boolean =
        call("setStatusNotificationEnabled", false) { it.setStatusNotificationEnabled(enabled); true }
    fun isVerboseLogEnabled(): Boolean = call("isVerboseLogEnabled", false) { it.isVerboseLogEnabled() }
    fun setVerboseLogEnabled(enabled: Boolean): Boolean =
        call("setVerboseLogEnabled", false) { it.setVerboseLogEnabled(enabled); true }
    fun isForcedLauncherIcons(): Boolean = call("isForcedLauncherIcons", false) { it.isForcedLauncherIcons() }
    fun setForcedLauncherIcons(enabled: Boolean): Boolean =
        call("setForcedLauncherIcons", false) { it.setForcedLauncherIcons(enabled); true }

    // ==== 3.5.1 日志静默 ====
    fun isLoggingSilenced(): Boolean = call("isLoggingSilenced", false) { it.isLoggingSilenced() }
    fun setLoggingSilenced(enabled: Boolean): Boolean =
        call("setLoggingSilenced", false) { it.setLoggingSilenced(enabled); true }

    // ==== 3.5.2 转储日志 ====
    fun dumpLogs(): String? = call("dumpLogs", null) { it.dumpLogs() }

    // ==== 注入失败检测 ====
    fun isInjectionFailed(): Boolean = call("isInjectionFailed", false) { it.isInjectionFailed() }
    /** 注入失败来源位掩码（IManagerService.FAIL_* 按位或），manager 按位对号取文案。 */
    fun getInjectionFailureSource(): Int =
        call("getInjectionFailureSource", IManagerService.FAIL_NONE) { it.injectionFailureSource }

    // ==== 3.13 内核模式双模（设计卡 §6：a′ 切换即写 mode，新生进程生效）====
    /** 当前模式（kmod.mode 取值）；未连接/异常 = MODE_USER（空态降级）。 */
    fun getMode(): String = call("getMode", IManagerService.MODE_USER) {
        it.mode ?: IManagerService.MODE_USER
    }

    /** 写模式；false = daemon 侧条件不满足（未通过验证却要内核模式 / 取值非法）。 */
    fun setMode(mode: String): Boolean = call("setMode", false) { it.setMode(mode) }

    // ==== 3.13 内核模式验证流程（设计卡 §3）====
    /** 前置检查通过并写入 kmod.verifying 后返回 true，否则 false。 */
    fun startKernelVerify(): Boolean = call("startKernelVerify", false) { it.startKernelVerify() }

    /** 取消验证：kill 脚本进程组 + 删 kmod.verifying（唯一合法清标志路径）。 */
    fun cancelKernelVerify(): Boolean =
        call("cancelKernelVerify", false) { it.cancelKernelVerify(); true }

    // ==== 3.13 验证 / 回退 / 安全模式状态查询（设计卡 §2.1、§5-4、§7）====
    /** 状态快照；未连接/异常返回中性缺省（用户模式、无标志、空路径），UI 走空态降级。 */
    fun getKernelStatus(): KernelStatus = call("getKernelStatus", defaultKernelStatus()) { svc ->
        val status = svc.kernelStatus
        if (status == null) {
            defaultKernelStatus()
        } else {
            // daemon 侧 parcelable 字段可能为 null（Java 平台类型），归一化后再交给 UI。
            if (status.mode.isNullOrEmpty()) status.mode = IManagerService.MODE_USER
            if (status.stage == null) status.stage = ""
            if (status.feedbackPath == null) status.feedbackPath = ""
            status
        }
    }

    /** 退出安全模式：清 crash.count + 删 safe_mode，点即生效。 */
    fun exitSafeMode(): Boolean = call("exitSafeMode", false) { it.exitSafeMode() }

    // ==== 3.13 反安装模块 inte 检测（设计卡 §8）====
    fun hasAntiInstallModule(): Boolean =
        call("hasAntiInstallModule", false) { it.hasAntiInstallModule() }

    /** 选是=卸载 inte；返回 true = 已卸载或本就不存在。 */
    fun removeAntiInstallModule(): Boolean =
        call("removeAntiInstallModule", false) { it.removeAntiInstallModule() }

    fun isIntePresent(): Boolean = call("isIntePresent", false) { it.isIntePresent() }

    fun getInteMode(): String = call("getInteMode", "") { it.getInteMode() }

    fun setInteMode(mode: String): Boolean = call("setInteMode", false) { it.setInteMode(mode) }

    /** 中性缺省状态（未连接时的空态）。 */
    private fun defaultKernelStatus(): KernelStatus = KernelStatus().apply {
        mode = IManagerService.MODE_USER
        stage = ""
        feedbackPath = ""
    }

    // ==== 3.6 转储 hook 信息 ====
    fun dumpHookInfo(receiver: IFrameworkDumpReceiver): Boolean =
        call("dumpHookInfo", false) { it.dumpHookInfo(receiver); true }

    // ==== 内部 ====
    private inline fun <T> call(method: String, default: T, block: (IManagerService) -> T): T {
        val svc = service
        if (!connected || svc == null) {
            log(method, "not connected (connected=$connected, svc=$svc)")
            return default
        }
        return try {
            block(svc)
        } catch (e: RemoteException) {
            log(method, "RemoteException: $e")
            android.util.Log.e("DripManager", "$method RemoteException", e)
            default
        } catch (e: Throwable) {
            log(method, "error: ${e.javaClass.simpleName}: ${e.message}")
            android.util.Log.e("DripManager", "$method error", e)
            default
        }
    }

    private fun fail(method: String, message: String) {
        log(method, message)
    }

    /** 调用留痕：追加写入 files/drip_mgr_calls.log。 */
    private fun log(method: String, message: String) {
        try {
            val ctx = appContext ?: return
            val file = File(ctx.filesDir, LOG_FILE)
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val line = "[$stamp] $method: $message\n"
            synchronized(this) {
                file.appendText(line)
            }
        } catch (_: Throwable) {
            // 留痕失败不影响主流程
        }
    }
}
