// M25 动态作用域：manager 轮询到的 pending 作用域请求条目（结构化 parcelable，aidl 编译器生成实现）。
package drip.manager;

parcelable ScopeRequestInfo {
    /** 请求唯一 id（respondScopeRequest 按此定位）。daemon 进程内单调递增。 */
    int requestId;

    /** 发起请求的模块包名 */
    String modulePackage;

    /** 模块请求加入作用域的目标 app 包名列表 */
    List<String> packages;
}
