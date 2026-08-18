// IManagerService：manager 与注入侧服务之间的接口定义 v1.1
package drip.manager;

import android.content.pm.PackageInfo;
import android.os.ParcelFileDescriptor;

import drip.manager.DeviceUser;
import drip.manager.IFrameworkDumpReceiver;
import drip.manager.ModuleInfo;
import drip.manager.ModuleLoadFailure;
import drip.manager.ScopeEntry;

interface IManagerService {

    // 身份/版本
    int getProtocolVersion();
    int getLibxposedApiVersion();
    String getFrameworkVersionName();
    long getFrameworkVersionCode();
    String getBuildStamp();

    // 模块配置
    List<String> getEnabledModules();
    ModuleInfo getModuleInfo(String packageName);
    List<String> getModuleRecommendedScope(String packageName);
    boolean setModuleEnabled(String packageName, boolean enabled);
    List<ScopeEntry> getModuleScope(String packageName);
    boolean setModuleScope(String packageName, in List<ScopeEntry> entries);
    boolean getIncludeNewApps(String packageName);
    void setIncludeNewApps(String packageName, boolean includeNewApps);
    List<ModuleLoadFailure> getModuleLoadFailures();

    // 全局（全量）模式开关
    boolean getGlobalMode();
    void setGlobalMode(boolean enabled);

    // 日志：part 列表 / 内容 / 活动 part / 轮转
    List<String> getLogParts(boolean verbose);
    ParcelFileDescriptor getLogPart(boolean verbose, String name);
    ParcelFileDescriptor getLiveLogPart(boolean verbose);
    void startNewLogPart(boolean verbose);

    // 设备视角
    boolean softReboot();
    void reboot();
    int getRootImplementation();
    List<DeviceUser> getUsers();
    List<PackageInfo> getInstalledPackagesFromAllUsers(int flags);
    void forceStopPackage(String packageName, int userId);
    boolean uninstallPackage(String packageName, int userId);

    // 通知/状态
    boolean isSystemServerAttached();
    boolean isSepolicyLoaded();
    boolean isStatusNotificationEnabled();
    void setStatusNotificationEnabled(boolean enabled);
    boolean isVerboseLogEnabled();
    void setVerboseLogEnabled(boolean enabled);
    boolean isForcedLauncherIcons();
    void setForcedLauncherIcons(boolean enabled);

    // 转储 hook 信息
    void dumpHookInfo(IFrameworkDumpReceiver receiver);

    // 已安装 Xposed 模块全量（含未启用的）
    List<ModuleInfo> getAllModules();

    // 重启该模块作用域内所有 app
    void restartModuleScopeProcesses(String modulePackage);

    // 模块安装/更新后通知 framework 重建配置缓存
    void notifyModuleChanged(String packageName);

    // 普通 app 安装/更新后通知 framework 按需重建配置缓存
    void notifyPackageChanged(String packageName);

    // 完全静默日志门控：开启后不写日志文件
    boolean isLoggingSilenced();
    void setLoggingSilenced(boolean enabled);

    // 日志转储：打包日志目录为 zip 并写入下载目录
    String dumpLogs();

    // 返回当前 part 下的模块名列表
    List<String> getModuleNames();

    // 读取当前 part 下指定模块的日志文件
    ParcelFileDescriptor getModuleLog(String moduleName);

    // 检测注入失败状态：框架报告失败时返回 true
    boolean isInjectionFailed();
}
