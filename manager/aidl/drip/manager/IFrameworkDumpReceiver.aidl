// 转储 hook 信息结果回调（UI spec §4.1 扩展点）
package drip.manager;

interface IFrameworkDumpReceiver {
    void onDumpResult(boolean success, String path);
}
