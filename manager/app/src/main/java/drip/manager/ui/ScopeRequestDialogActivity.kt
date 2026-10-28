// ScopeRequestDialogActivity：daemon 的作用域请求通知点进来的「小型弹窗」（对话框样式 Activity）。
//
// 链路（2026-09-16 用户拍板）：模块 requestScope → daemon 登记 outstanding → **daemon 直发通知**
// （root 常驻，不受寄生宿主 cached 冻结影响）→ contentIntent = 宿主 Activity + LAUNCH_MANAGER +
// EXTRA_SCOPE_REQUEST_ID → framework ParasiticManagerHooker 按该 extra 把这次启动重定向到本类
// → 用户「同意 / 拒绝」→ ManagerServiceClient.respondScopeRequest → daemon 写库 + 回执模块。
//
// 为什么必须由 framework 重定向才能进来：寄生模式下 manager 跑在 com.android.shell 宿主里，
// 宿主 manifest 不含 manager 的任何 Activity，`Intent(context, XxxActivity::class.java)` 会被
// AMS 解析成 com.android.shell/drip.manager.ui.XxxActivity → ActivityNotFoundException
// （同 StatusNotification.kt:32-36 记录）⇒ manager 进程内无法自行 startActivity 打开第二个
// Activity，只能「启动宿主 Activity + 框架侧 ActivityInfo/Intent 替换」。
//
// 请求来源：daemon 在 extras 里带 request_id（登记后即知）+ module + packages；request_id 失效
// （daemon 重启 / 已被处理 / extras 过期）时按 module + packages 自行匹配；都匹配不上 → 直接
// finish，不崩、不误写。
package drip.manager.ui

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import drip.manager.ScopeRequestInfo
import drip.manager.data.ManagerServiceClient
import drip.manager.data.ModuleInstallWatcher
import drip.manager.data.ScopeRequestWatcher
import drip.manager.data.SettingsState
import drip.manager.ui.theme.DripManagerTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** intent extras：与 daemon `DaemonNotifier.EXTRA_SCOPE_*`、framework
 *  `ParasiticManagerHooker.EXTRA_SCOPE_REQUEST_ID` 三处同值（跨进程/跨模块，各自持有常量）。 */
const val EXTRA_SCOPE_REQUEST_ID = "drip.scope_request_id"
const val EXTRA_SCOPE_MODULE = "drip.scope_request_module"
const val EXTRA_SCOPE_PACKAGES = "drip.scope_request_packages"

/**
 * M38：**激活模式** 的 extra（值 = 模块包名）。与 framework
 * `ParasiticManagerHooker.EXTRA_ACTIVATE_MODULE`、manager `ModuleInstallWatcher` 三处同值。
 *
 * 两种模式共用一个 Activity（用户 2026-09-17 拍板）：带 [EXTRA_SCOPE_REQUEST_ID] 走作用域请求，
 * 只有本 extra 时走「已安装未激活 → 激活」。二者互斥，作用域请求优先（见 [ScopeRequestDialogActivity.onCreate]）。
 */
const val EXTRA_ACTIVATE_MODULE = "drip.activate_module"

class ScopeRequestDialogActivity : ComponentActivity() {

    private companion object {
        const val TAG = "DripScopeDialog"
        /** 弹窗宽度（floating window 下高度自适应内容）。 */
        val DIALOG_WIDTH = 320.dp
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 解析出的 pending 请求；[resolved] 为 true 且它为 null = 请求已失效（正在收尾关闭）。 */
    private var request by mutableStateOf<ScopeRequestInfo?>(null)
    private var resolved by mutableStateOf(false)
    private var dismissing by mutableStateOf(false)

    /** M38 激活模式的模块包名（非空 = 本弹窗处于激活模式，无需向 daemon 取请求）。 */
    private var activatePackage by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // binder 已在 Application.onCreate 前注入（寄生 Hook2），此处幂等初始化 ctx 供调用留痕。
        ManagerServiceClient.init(applicationContext)
        // 点弹窗外 / 返回键：不落决定（请求仍 outstanding，通知保留可再点开），只关掉自己。
        setFinishOnTouchOutside(true)

        val requestId = intent?.getIntExtra(EXTRA_SCOPE_REQUEST_ID, -1) ?: -1
        val module = intent?.getStringExtra(EXTRA_SCOPE_MODULE)
        val packages = intent?.getStringArrayListExtra(EXTRA_SCOPE_PACKAGES).orEmpty()
        val activatePkg = intent?.getStringExtra(EXTRA_ACTIVATE_MODULE)

        // 模式判别：**作用域请求优先**（时限性更强）。只有 scope extras 全缺、而激活 extra 在时
        // 才进激活模式——这样即便调用方把两个 extra 都带上，也不会把激活当成请求处理。
        val activationMode = requestId < 0 && !activatePkg.isNullOrEmpty()
        if (activationMode) {
            activatePackage = activatePkg
            resolved = true // 激活模式无需异步解析，直接出内容
            Log.i(TAG, "activation mode for $activatePkg")
        }

        setContent {
            val settings = remember { SettingsState.load(applicationContext) }
            DripManagerTheme(
                dynamicColor = settings.dynamicColor,
                amoledBlack = settings.amoledBlack,
            ) {
                Surface(
                    modifier = Modifier.width(DIALOG_WIDTH),
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 6.dp,
                ) {
                    val info = request
                    val act = activatePackage
                    if (act != null) {
                        ActivateContent(
                            moduleLabel = label(act),
                            onDecision = { confirmed -> if (confirmed) activate(act) else finish() },
                        )
                    } else if (!resolved) {
                        // 解析中（binder 一次往返，通常几十毫秒）：占位，避免先闪一下「已失效」。
                        Text(
                            text = "读取作用域请求…",
                            modifier = Modifier.padding(24.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (info == null) {
                        Column(Modifier.padding(24.dp)) {
                            Text(
                                text = "该作用域请求已失效",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "可能已被处理，或 Drip 服务刚重启。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        ScopeRequestContent(
                            moduleLabel = label(info.modulePackage),
                            packageLabels = info.packages.map { label(it) },
                            onDecision = { allow -> respond(info.requestId, allow) },
                        )
                    }
                }
            }
        }
        if (!activationMode) {
            scope.launch { resolve(requestId, module, packages) }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** 取请求详情：request_id 优先，失效则按 module + packages 匹配（再兜底轮询快照）。 */
    private suspend fun resolve(requestId: Int, module: String?, packages: List<String>) {
        val info = withContext(Dispatchers.IO) {
            runCatching { lookup(requestId, module, packages) }.getOrElse { e ->
                Log.w(TAG, "lookup failed: $e")
                null
            }
        }
        resolved = true
        if (info == null) {
            Log.w(TAG, "request not found (id=$requestId module=$module), finish")
            dismissing = true
            Toast.makeText(applicationContext, "该作用域请求已失效", Toast.LENGTH_SHORT).show()
            delay(300) // 让「已失效」文案先出来再关（直接 finish 会闪一下空窗）
            finish()
            return
        }
        request = info
    }

    private fun lookup(requestId: Int, module: String?, packages: List<String>): ScopeRequestInfo? {
        // daemon 侧注册表是唯一真相；**只有拿不到 binder** 才用轮询快照兜底——已连接时快照可能
        // 残留已结清的请求，拿它去响应会「假成功」（daemon 侧 unknown/already handled）。
        val pending = if (ManagerServiceClient.connected) {
            ManagerServiceClient.getPendingScopeRequests()
        } else {
            ScopeRequestWatcher.snapshot()
        }
        if (requestId >= 0) {
            pending.firstOrNull { it.requestId == requestId }?.let { return it }
        }
        if (module.isNullOrEmpty()) return null
        val wanted = packages.sorted()
        return pending.firstOrNull { it.modulePackage == module && it.packages.sorted() == wanted }
    }

    /** 落决定并关闭弹窗；结果 Toast 提示。 */
    private fun respond(requestId: Int, allow: Boolean) {
        if (dismissing) return
        dismissing = true
        val ctx = applicationContext
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                ManagerServiceClient.connect()
                if (!ManagerServiceClient.connected) {
                    false
                } else {
                    ManagerServiceClient.respondScopeRequest(requestId, allow)
                }
            }
            Log.i(TAG, "respondScopeRequest(#$requestId, allow=$allow) -> $ok")
            val text = when {
                !ok -> "Drip 未连接，操作未生效"
                allow -> "已批准"
                else -> "已拒绝"
            }
            Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    /**
     * M38：激活模块 —— `setModuleEnabled(pkg,true)`，成功后按「启用即同步」的既有语义
     * 合并模块自带的推荐作用域（用户 2026-09-17 拍板「同步」；与 [ModuleInstallWatcher]
     * 对已启用模块更新时的做法一致，只加不减）。
     */
    private fun activate(pkg: String) {
        if (dismissing) return
        dismissing = true
        val ctx = applicationContext
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                ManagerServiceClient.connect()
                if (!ManagerServiceClient.connected) {
                    false
                } else {
                    val enabled = ManagerServiceClient.setModuleEnabled(pkg, true)
                    if (enabled) {
                        val added = runCatching {
                            ModuleInstallWatcher.syncRecommendedScope(ctx, pkg)
                        }.getOrElse { e ->
                            Log.w(TAG, "syncRecommendedScope($pkg) failed: $e")
                            0
                        }
                        Log.i(TAG, "activate($pkg) ok, recommended scope +$added")
                    }
                    enabled
                }
            }
            Log.i(TAG, "activate($pkg) -> $ok")
            Toast.makeText(
                ctx,
                if (ok) "已激活" else "Drip 未连接，操作未生效",
                Toast.LENGTH_SHORT,
            ).show()
            finish()
        }
    }

    /** 应用名（取不到回落包名）。 */
    private fun label(pkg: String): String = try {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Throwable) {
        pkg
    }
}

/** M38 激活模式内容：与作用域请求同一套外壳（宽度/圆角/主题），只是文案与按钮不同。 */
@Composable
private fun ActivateContent(
    moduleLabel: String,
    onDecision: (Boolean) -> Unit,
) {
    Column(Modifier.padding(24.dp)) {
        Text(
            text = "模块未激活",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = moduleLabel,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "已在本机安装，尚未在 Drip 中激活。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "激活后本模块生效；它在 AndroidManifest 里声明的推荐作用域会一并加入。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { onDecision(false) }) { Text("稍后") }
            TextButton(onClick = { onDecision(true) }) { Text("激活") }
        }
    }
}

@Composable
private fun ScopeRequestContent(
    moduleLabel: String,
    packageLabels: List<String>,
    onDecision: (Boolean) -> Unit,
) {
    Column(Modifier.padding(24.dp)) {
        Text(
            text = "作用域请求",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = moduleLabel,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "请求把以下应用加入它的作用域：",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = packageLabels.joinToString("\n"),
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "批准后新加入的应用在下次启动时生效。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { onDecision(false) }) { Text("拒绝") }
            TextButton(onClick = { onDecision(true) }) { Text("同意") }
        }
    }
}
