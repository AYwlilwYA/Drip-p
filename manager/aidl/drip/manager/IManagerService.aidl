// IManagerService：manager ↔ daemon 精简契约 v1.1（含 🆕 UI 提案方法，package drip.manager）
package drip.manager;

import android.content.pm.PackageInfo;
import android.os.ParcelFileDescriptor;

import drip.manager.DeviceUser;
import drip.manager.IFrameworkDumpReceiver;
import drip.manager.KernelStatus;
import drip.manager.ModuleInfo;
import drip.manager.ModuleLoadFailure;
import drip.manager.ScopeEntry;
import drip.manager.ScopeRequestInfo;

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

    // M22 per-module「兼容性增强」豁免档位（int compatMode）：
    //   0 = 不豁免（模块作用域进程 serve B2：类名+方法名随机，隐藏最强，默认）；
    //   1 = P0 混淆（进程 serve framework.dex.b1：类名随机 + 方法名原名 + 模块 raw ——
    //       UI「兼容性增强」开、未开「最大兼容」）；
    //   2 = 完全不混淆 PRISTINE（进程 serve framework.dex.orig 全原名 + 模块 raw ——
    //       UI「兼容性增强」+「最大兼容」都开，原 M20 pristine 语义）。
    // 旧布尔 isModuleCompatPristine/setModuleCompatPristine 保留（wire 兼容）：其语义即档位 2。
    // 末尾追加，wire-compatible，不 bump PROTOCOL_VERSION。
    int getModuleCompatMode(String packageName);
    void setModuleCompatMode(String packageName, int compatMode);

    // M24 随机 log-tag 开关（默认开）：真实生效视图 = config/log_tag 文件是否存在；关闭时
    // 写 config/log_tag_disabled 标记（安装脚本见该标记不重新预写，防升级重装把「关」拉回开）。
    // 末尾追加，wire-compatible，不 bump PROTOCOL_VERSION。
    boolean isRandomLogTagEnabled();
    void setRandomLogTagEnabled(boolean enabled);

    // M25 动态作用域：manager 轮询 pending 的作用域请求（模块 app 运行时经 requestScope 触发，
    // daemon 登记 outstanding），获知后弹「批准/拒绝」通知 → respondScopeRequest 落决定。
    // 末尾追加，wire-compatible，不 bump PROTOCOL_VERSION。
    List<ScopeRequestInfo> getPendingScopeRequests();
    void respondScopeRequest(int requestId, boolean allow);

    // ==== 3.13 内核模式标志 schema（设计卡 §1；路径/取值/阶段的唯一权威常量）====
    // daemon 侧 FileSystem.kt 新增字段一律由本表常量拼装、不得另写字面量；shell 脚本与
    // zygisk 侧无 AIDL 可引，用同名字面量，改动必须与本表逐字同步（规则见 kpk 调用契约文档）。
    const String KMOD_CONFIG_DIR = "/data/adb/drip/config";
    const String KMOD_MODE_FILE = "kmod.mode";
    const String KMOD_VERIFYING_FILE = "kmod.verifying";
    const String KMOD_OK_FILE = "kmod.ok";
    const String KMOD_FALLBACK_FILE = "kmod.fallback";
    const String KMOD_CRASH_PENDING_FILE = "kmod.crash_pending";
    const String SAFE_MODE_FILE = "safe_mode";
    const String CRASH_COUNT_FILE = "crash.count";
    // 下面两个是复原 P0 已落的既有标志（daemon 侧 FileSystem.kt 持有），登记在此仅为同源。
    const String OBF_RESTORE_FILE = "obf_restore";
    const String OBF_BOOT_DONE_FILE = "obf_boot.done";
    const String KO_DIR = "/data/adb/drip/ko";
    const String KO_FILE = "/data/adb/drip/ko/dripcore.ko";
    const String KO_SHA256_FILE = "/data/adb/drip/ko/dripcore.ko.sha256";
    const String VERIFY_DIR = "/data/adb/drip/verify";
    const String RAW_KO_FILE = "/data/adb/modules/drip/kmod/raw.ko";
    const String KPK_BIN = "/data/adb/modules/drip/bin/kpk";
    const String VERIFY_SCRIPT = "/data/adb/modules/drip/bin/verify-kernel.sh";
    const String MODE_USER = "user";
    const String MODE_KERNEL = "kernel";
    const String STAGE_INIT = "init";
    const String STAGE_PACK = "pack";
    const String STAGE_CHECK = "check";
    const String STAGE_BACKFILL = "backfill";
    const String STAGE_GATE = "gate";
    const String STAGE_INSMOD = "insmod";
    const String STAGE_BP = "bp";
    const int FAIL_NONE = 0;
    const int FAIL_RELAY = 1;
    const int FAIL_BRIDGE = 2;
    const int FAIL_OBF_RESTORED = 4;
    const int FAIL_OBF_FAILED = 8;
    const int FAIL_KMOD_FALLBACK = 16;
    const int FAIL_KMOD_CRASH_PENDING = 32;

    // ==== 3.13 内核模式双模（a′ 写即生效于新生进程，设计卡 §6）====
    // 读写 config/kmod.mode，取值 MODE_USER | MODE_KERNEL，文件缺省 = MODE_USER。
    String getMode();
    // 返回 true = 已写入该模式；false = 条件不满足（未通过验证却要求内核模式 / 取值非法）。
    boolean setMode(String mode);

    // ==== 3.13 内核模式验证流程（设计卡 §3；通过性由脚本判定，daemon 只编排）====
    // 前置检查通过并写入 kmod.verifying 后返回 true，否则 false。
    boolean startKernelVerify();
    // 取消验证：kill 脚本进程组 + 删 kmod.verifying（唯一合法清标志路径）。
    void cancelKernelVerify();

    // ==== 3.13 验证 / 回退 / 安全模式状态查询（设计卡 §2.1、§5-4、§7）====
    KernelStatus getKernelStatus();
    // 退出安全模式：清 crash.count + 删 safe_mode，点即生效；返回是否已退出。
    boolean exitSafeMode();
    // 注入失败来源位掩码（FAIL_* 按位或），manager 按位对号取文案。
    int getInjectionFailureSource();

    // ==== 3.13 反安装模块 inte 检测（设计卡 §8，root 精确匹配 /data/adb/modules/inte）====
    boolean hasAntiInstallModule();
    // 「是否卸载 inte？选是」用；返回 true = 已卸载或本就不存在。
    boolean removeAntiInstallModule();

    // ==== 执行断点信道（inline_hooker relay 面，契约 §5）====
    // 装执行断点：同点重复装返 false（kernel 侧 -EBUSY → 幂等拒绝）。daemon 起 dripcorectl armx。
    boolean armExecBreak(int pid, long addr, long redir);
    // 撤执行断点：没装过也返回 true（幂等 0）。
    boolean disarmExecBreak(int pid, long addr);
    // [hits, flags, ret]；ret = 0 / -errno。
    long[] getExecBreakInfo(int pid, long addr);

    // ==== 3.13 inte 弹窗（present 标志驱动；ko 路径常用列表探测）====
    const String INTE_PRESENT_FILE = "inte.present";
    const String INTE_MODE_FILE = "inte.mode";
    const String INTE_MODE_AUTO = "auto";
    const String INTE_MODE_OFF = "off";
    const String INTE_MODE_NONE = "none";

    // 验证入口写入：该机内核加载过 inte（首次验证一次性）。
    boolean isIntePresent();
    // "" = 未选择（UI 弹三选一）；否则 auto/off/none。
    String getInteMode();
    boolean setInteMode(String mode);
}
