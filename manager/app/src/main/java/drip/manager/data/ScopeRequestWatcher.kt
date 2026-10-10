// ScopeRequestWatcher：轮询 daemon 的 outstanding 作用域请求，作为作用域请求弹窗的**数据源快照**。
// Manager 进程级，寄生/独立均适用。
//
// 通知不再由本 watcher 发（2026-09-16 改设计）：manager 寄生的宿主是后台 cached 进程会被系统冻结
// → 通知晚到约 1 分钟；改由 daemon 直发（daemon/util/DaemonNotifier.notifyScopeRequest），
// contentIntent 经宿主 Activity + LAUNCH_MANAGER + EXTRA_SCOPE_REQUEST_ID 被 framework 重定向到
// manager 的 ScopeRequestDialogActivity（见 manager/ui/ScopeRequestDialogActivity.kt）。
//
// 本类保留轮询：弹窗在 binder 不可用（未连接）时用 [snapshot] 兜底取请求详情。
package drip.manager.data

import android.content.Context
import android.util.Log
import drip.manager.ScopeRequestInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ScopeRequestWatcher {

    private const val TAG = "ScopeRequestWatcher"
    private const val POLL_INTERVAL_MS = 10_000L

    /** 最近一次轮询到的 outstanding 请求（跨线程读，故 @Volatile）。 */
    @Volatile
    private var latest: List<ScopeRequestInfo> = emptyList()

    private var scope: CoroutineScope? = null

    /** 启动轮询。幂等，多次调用无副作用。 */
    fun startWatching(context: Context) {
        if (scope != null) return
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        Log.i(TAG, "start watching")
        scope?.launch {
            while (true) {
                refresh()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /** 停止轮询并清空快照。 */
    fun stopWatching() {
        scope?.cancel()
        scope = null
        latest = emptyList()
        Log.i(TAG, "stop watching")
    }

    /** 最近一次轮询快照（binder 不可用时弹窗的兜底来源；正常路径直接查 binder）。 */
    fun snapshot(): List<ScopeRequestInfo> = latest

    private suspend fun refresh() {
        if (!ManagerServiceClient.connected) return
        val requests = withContext(Dispatchers.IO) {
            ManagerServiceClient.getPendingScopeRequests()
        }
        latest = requests
    }
}
