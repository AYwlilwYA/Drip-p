// 转储 hook 信息结果回调
package drip.manager;

interface IFrameworkDumpReceiver {
    void onDumpResult(boolean success, String path);
}
