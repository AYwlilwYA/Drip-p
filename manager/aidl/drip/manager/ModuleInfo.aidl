// 模块详情。结构化 parcelable，aidl 编译器自动生成实现。
package drip.manager;

parcelable ModuleInfo {
    String packageName;

    /** 模块归属用户（多用户 tab 用） */
    int userId;

    String name;

    String versionName;

    /** libxposed API 级别（=102） */
    int apiVersion;

    String description;

    boolean enabled;

    boolean loadFailed;

    /** 0=无 1=NO_APK 2=UNUSABLE 3=UNSUPPORTED_API */
    int loadFailureReason;

    /** 安装时间（排序「最近安装」用） */
    long installTime;

    /** 更新时间（排序「最近更新」用） */
    long updateTime;
}
