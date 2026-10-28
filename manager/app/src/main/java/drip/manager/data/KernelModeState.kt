// KernelModeState：内核模式 / 安全模式状态快照（manager 侧唯一读取点，Compose 可观察）。
package drip.manager.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import drip.manager.KernelStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * daemon 侧 kmod.* / safe_mode 标志的 manager 侧投影。
 *
 * manager 不可直读 /data/adb/drip（label=adb_data_file），一律经 AIDL 查询；本对象把
 * [ManagerServiceClient.getKernelStatus] 的结果挂在 Compose 状态上，Home 横幅 / 模块页
 * 置灰 / 安全模式全屏警示共用同一份快照。刷新由各页面按需触发（不后台轮询）。
 */
object KernelModeState {

    var status: KernelStatus by mutableStateOf(ManagerServiceClient.getKernelStatus())
        private set

    /** 拉取一次最新状态（binder 调用放 IO 线程，不阻塞主线程）。 */
    suspend fun refresh() {
        val next = withContext(Dispatchers.IO) { ManagerServiceClient.getKernelStatus() }
        status = next
    }
}
