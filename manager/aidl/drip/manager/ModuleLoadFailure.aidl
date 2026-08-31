// 模块加载失败（契约 §4.2）。结构化 parcelable。
package drip.manager;

parcelable ModuleLoadFailure {
    /** 模块包名 */
    String packageName;

    /** MODULE_LOAD_NO_APK=1 / UNUSABLE=2 / UNSUPPORTED_API=3 */
    int reason;
}
