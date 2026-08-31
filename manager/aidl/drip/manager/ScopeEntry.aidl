// 模块作用域条目（契约 §4.1）。结构化 parcelable，aidl 编译器自动生成实现。
package drip.manager;

parcelable ScopeEntry {
    /** 目标 app 包名 */
    String appPackageName;

    /** 用户 id（uid = userId*100000 + appId） */
    int userId;
}
