package drip.manager

import android.app.Application
import android.util.Log
import drip.manager.data.InjectionFailureWatcher
import drip.manager.data.ManagerServiceClient
import drip.manager.data.ModuleFailureWatcher
import drip.manager.data.ModuleInstallWatcher

/**
 * Manager 进程级后台入口（解耦，2026-09-06）。
 *
 * <p>watcher/模块事件广播等「常驻后台」不再绑 MainActivity.onCreate——本 Application 在进程
 * bind 时实例化（寄生模式：ParasiticManagerHooker Hook1 把宿主 appInfo 换成 manager 的，宿主
 * 每次 bind 都会实例化本类；独立安装模式同样适用），无 UI 也注册。</p>
 *
 * <p>这样宿主被 daemon 拉起 / relaunch / 通知点击时，attach 即 watcher 在线：装/更新模块 →
 * ModuleInstallWatcher 弹通知 + notifyModuleChanged 让 daemon 重建缓存；模块失败/注入失败
 * 轮询也在此启动。Activity 退掉后台照常工作。</p>
 */
class DripManagerApp : Application() {

    companion object {
        private const val TAG = "DripManagerApp"
    }

    override fun onCreate() {
        super.onCreate()
        try {
            // appContext 供 ManagerServiceClient 写调用留痕 / 通知；幂等。
            ManagerServiceClient.init(this)
            // 进程级模块安装/更新广播检测（幂等，进程存活期间常驻）。
            ModuleInstallWatcher.ensureRegistered(this)
            // 模块加载失败 / 注入失败轮询（各自幂等，scope 非空即返回）。
            ModuleFailureWatcher.startWatching(this)
            InjectionFailureWatcher.startWatching(this)
            Log.i(TAG, "background bootstrap complete (watchers registered)")
        } catch (t: Throwable) {
            // Application.onCreate 抛异常会杀掉宿主/自身进程——必须吞干净。
            Log.w(TAG, "background bootstrap failed: ${t}", t)
        }
    }
}
