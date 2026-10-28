// 内核模式 / 安全模式状态查询返回体（设计卡 §2.1 状态表 + §5-4 文案拆分 + §7）。
// 结构化 parcelable，aidl 编译器自动生成实现；字段只在末尾追加，不改不删。
package drip.manager;

parcelable KernelStatus {
    /** 当前模式：kmod.mode 的取值（MODE_USER | MODE_KERNEL），文件缺省 = MODE_USER */
    String mode;

    /** kmod.verifying 存在 = 正在验证 */
    boolean verifying;

    /** verifying 现场 stage=（STAGE_* 常量之一），非验证态为空串 */
    String stage;

    /** kmod.ok 存在 = 已通过加载验证（内核模式可用开关） */
    boolean verified;

    /** kmod.fallback 存在 = 开机加载失败已回退，待重新验证 */
    boolean fallback;

    /** kmod.crash_pending 存在 = 上次验证期间崩溃，待反馈 */
    boolean crashPending;

    /** safe_mode 存在 = 安全模式生效中 */
    boolean safeMode;

    /** crash.count 当前值（系统崩溃未稳重启与复原失败共用同一计数器） */
    int crashCount;

    /** 最近一次反馈包完整路径（tar.gz），无则空串 */
    String feedbackPath;
}
