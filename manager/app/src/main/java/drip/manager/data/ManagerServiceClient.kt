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
import drip.manager.ModuleInfo
import drip.manager.ModuleLoadFailure
import drip.manager.ScopeEntry
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
            // 进程级广播检测兜底（appContext 通常由 MainActivity.init 先设置，此处防御）。
            appContext?.let { ModuleInstallWatcher.ensureRegistered(it) }
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
    fun getModuleLoadFailures(): List<ModuleLoadFailure> =
        call("getModuleLoadFailures", emptyList()) { it.moduleLoadFailures }
    fun getGlobalMode(): Boolean = call("getGlobalMode", false) { it.globalMode }
    fun setGlobalMode(enabled: Boolean): Boolean =
        call("setGlobalMode", false) { it.setGlobalMode(enabled); true }

    // ==== 3.3 日志 ====
    fun getLogParts(verbose: Boolean): List<String> =
        call("getLogParts", emptyList()) { it.getLogParts(verbose) }
    fun getLogPart(verbose: Boolean, name: String): ParcelFileDescriptor? =
        call("getLogPart", null) { it.getLogPart(verbose, name) }
    fun getLiveLogPart(verbose: Boolean): ParcelFileDescriptor? =
        call("getLiveLogPart", null) { it.getLiveLogPart(verbose) }
    fun startNewLogPart(verbose: Boolean): Boolean =
        call("startNewLogPart", false) { it.startNewLogPart(verbose); true }

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
