// IManagerService：manager ↔ daemon 精简契约 v1.1（含 🆕 UI 提案方法，package drip.manager）
package drip.manager;

import android.content.pm.PackageInfo;
import android.os.ParcelFileDescriptor;

import drip.manager.DeviceUser;
import drip.manager.IFrameworkDumpReceiver;
import drip.manager.ModuleInfo;
import drip.manager.ModuleLoadFailure;
import drip.manager.ScopeEntry;

interface IManagerService {

    // 3.1 身份/版本（getProtocolVersion 是事务 0 首调）
    int getProtocolVersion();
    int getLibxposedApiVersion();
    String getFrameworkVersionName();
    long getFrameworkVersionCode();
    String getBuildStamp();

    // 3.2 模块配置（MVP 核心）
    List<String> getEnabledModules();
    ModuleInfo getModuleInfo(String packageName);
    List<String> getModuleRecommendedScope(String packageName);
    List<String> getStaticScope(String packageName);
    boolean setModuleEnabled(String packageName, boolean enabled);
    List<ScopeEntry> getModuleScope(String packageName);
    boolean setModuleScope(String packageName, in List<ScopeEntry> entries);
    boolean getIncludeNewApps(String packageName);
    void setIncludeNewApps(String packageName, boolean includeNewApps);
    List<ModuleLoadFailure> getModuleLoadFailures();

    // 3.2 M3c：全局（全量）模式开关 —— 所有 app 进程注入，忽略 scope 过滤
    boolean getGlobalMode();
    void setGlobalMode(boolean enabled);

    // 3.3 日志（M5 单通道：verbose 参数保留 wire-compatible 但 daemon 忽略，始终操作主日志）
    List<String> getLogParts(boolean verbose);
    ParcelFileDescriptor getLogPart(boolean verbose, String name);
    ParcelFileDescriptor getLiveLogPart(boolean verbose);
    void startNewLogPart(boolean verbose);
    // 2026-09-06: 清空全部日志（主 part + 模块日志），重置并从新 part 开始
    void clearLogs();

    // 3.4 设备视角
    boolean softReboot();
    void reboot();
    int getRootImplementation();
    List<DeviceUser> getUsers();
    List<PackageInfo> getInstalledPackagesFromAllUsers(int flags);
    void forceStopPackage(String packageName, int userId);
    boolean uninstallPackage(String packageName, int userId);

    // 3.5 通知/状态
    boolean isSystemServerAttached();
    boolean isSepolicyLoaded();
    boolean isStatusNotificationEnabled();
    void setStatusNotificationEnabled(boolean enabled);
    boolean isVerboseLogEnabled();
    void setVerboseLogEnabled(boolean enabled);
    boolean isForcedLauncherIcons();
    void setForcedLauncherIcons(boolean enabled);

    // 3.6 转储 hook 信息（UI spec §4.1 扩展点）
    void dumpHookInfo(IFrameworkDumpReceiver receiver);

    // 3.7 M3 UI 修复：已安装 Xposed 模块全量（PM 扫描 module.prop/xposed_init + DB 配置合并，
    // 含未启用的；enabled 状态经 ModuleDatabase 合并）。方法追加在接口末尾，不破坏旧客户端。
    List<ModuleInfo> getAllModules();

    // 3.8 M4 P1 热重载：重启该模块作用域内所有 app（forceStopPackage 聚合）。
    // 追加在接口末尾，wire-compatible，不 bump PROTOCOL_VERSION。
    void restartModuleScopeProcesses(String modulePackage);

    // 3.9 M4 P1 热重载：manager 广播实时检测到模块安装/更新 → 通知 daemon 重建 ConfigCache
    // （替代已删除的 daemon PackageEventWatcher 轮询）。末尾追加，wire-compatible，
    // 不 bump PROTOCOL_VERSION。
    void notifyModuleChanged(String packageName);

    // 3.10 M4 五缺口 2：普通 app（非模块）安装/更新 → 通知 daemon 按需重建 ConfigCache。
    // daemon 内部做相关性判断（该包在某启用模块 scope/staticScope 中，或某启用模块开启
    // includeNewApps），避免无条件全量刷。末尾追加，wire-compatible，不 bump PROTOCOL_VERSION。
    void notifyPackageChanged(String packageName);

    // 3.11 完全静默日志门控：开启后 daemon 文件日志不写（/data/adb/drip/log 不再追加）。
    // 静默时 part 文件保留但不再增长。末尾追加，wire-compatible，不 bump PROTOCOL_VERSION。
    boolean isLoggingSilenced();
    void setLoggingSilenced(boolean enabled);

    // 3.12 日志转储：打包 /data/adb/drip/log 目录为 zip → 写入下载目录。
    // 返回 zip 完整路径；失败返回 null/空。末尾追加，wire-compatible，不 bump PROTOCOL_VERSION。
    String dumpLogs();

    // M5：按模块存储日志——返回当前 part 下的模块名列表（去 .log 后缀）。
    // 末尾追加，wire-compatible，不 bump PROTOCOL_VERSION。
    List<String> getModuleNames();

    // M5：按模块存储日志——读取当前 part 下指定模块的日志文件。
    // 返回只读 PFD；模块不存在返回 null。末尾追加，wire-compatible，不 bump PROTOCOL_VERSION。
    ParcelFileDescriptor getModuleLog(String moduleName);

    // PFD 传输在某些 ROM 上 DeadObject（binder fd 通道失败）时的 String fallback：
    // 返回模块日志内容（≤900KB），文件过大/不存在返回 null。
    // 末尾追加，wire-compatible，不 bump PROTOCOL_VERSION。
    String getModuleLogContent(String moduleName);

    // M5 通知与日志导出：检测注入失败标记（relay_fail / bridge_fail）是否存在。
    // daemon 检查 /data/adb/drip/config/ 下标记文件，manager 启动后查询 → 发通知。
    // 末尾追加，wire-compatible，不 bump PROTOCOL_VERSION。
    boolean isInjectionFailed();

    // Fallback：PFD 传输 DeadObjectException 时，返回日志内容字符串（≤900KB）。
    // 末尾追加，wire-compatible，不 bump PROTOCOL_VERSION。
    String getLogPartContent(boolean verbose, String name);

    // B2 方法名随机化开关（全局，默认开）：开 = app 进程 serve B2 dex（方法名随机，隐藏更强）；
    // 关 = 全体 app 进程 serve P0 dex（方法原名），遇部分模块不可用时关闭，丧失部分隐藏性能。
    // system_server 恒 P0，不受本开关影响。末尾追加，wire-compatible，不 bump PROTOCOL_VERSION。
    boolean isB2Enabled();
    void setB2Enabled(boolean enabled);

    // M20 per-module「兼容性增强」豁免：开启后该模块作用域内目标进程整链 PRISTINE
    //（framework/模块 dex 原名），消除该模块对混淆随机名的一切失配；被注入进程丧失隐藏性。
    // 末尾追加，wire-compatible，不 bump PROTOCOL_VERSION。
    boolean isModuleCompatPristine(String packageName);
    void setModuleCompatPristine(String packageName, boolean enabled);
}
