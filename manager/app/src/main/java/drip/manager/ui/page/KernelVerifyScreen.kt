// KernelVerifyScreen：内核模式全屏验证界面（设计卡 §3 / §10-U2）。进程内 overlay，不做独立 Activity（U8）。
package drip.manager.ui.page

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.StrictMode
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import drip.manager.IManagerService
import drip.manager.data.KernelModeState
import drip.manager.data.ManagerServiceClient
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 反馈文案（requirements.md:25 原话一字不改，标题/正文按句号切分以便展示）。 */
private const val FEEDBACK_TITLE = "当前内核不支持内核模式!"
private const val FEEDBACK_BODY = "请联系作者并且反馈!将该压缩包发送至相关群聊或者提交issue!"

/** a′ 无缝切换提示（requirements.md:91 第四批① 原话）。 */
const val MODE_SWITCH_HINT = "新应用立即生效，系统组件下次重启后完全切换"

/** 验证阶段（设计卡 §3.1 stage 序列，常量取自 AIDL 3.13）。 */
private data class VerifyStage(val key: String, val label: String)

private val VERIFY_STAGES = listOf(
    VerifyStage(IManagerService.STAGE_INIT, "初始化"),
    VerifyStage(IManagerService.STAGE_PACK, "打包"),
    VerifyStage(IManagerService.STAGE_CHECK, "校验"),
    VerifyStage(IManagerService.STAGE_BACKFILL, "回填"),
    VerifyStage(IManagerService.STAGE_GATE, "比对"),
    VerifyStage(IManagerService.STAGE_INSMOD, "加载"),
    VerifyStage(IManagerService.STAGE_BP, "测试"),
)

/**
 * 全屏验证界面：阶段进度 + 开始/取消 + 通过态 + 失败反馈（分享/复制路径）。
 * [firstLaunch] = 首启弹出（设计卡 :35）。[onDisableAutoPrompt] = 用户选择「以后不再自动弹出」，
 * 置位后永久不再自动弹（设计卡 :68）。
 */
@Composable
fun KernelVerifyScreen(
    onClose: () -> Unit,
    firstLaunch: Boolean = false,
    onDisableAutoPrompt: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val status = KernelModeState.status
    val connected = ManagerServiceClient.connected
    val scope = rememberCoroutineScope()
    // 本次会话是否已发起过验证：用于区分「没开始」与「跑完但没通过」（设计卡 T3 出口）。
    var started by remember { mutableStateOf(false) }
    var showFeedback by remember { mutableStateOf(false) }
    var showInteDialog by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }

    // 屏幕可见期间轮询状态（verifying/stage 由 daemon 回写 kmod.verifying）。
    LaunchedEffect(Unit) {
        while (true) {
            KernelModeState.refresh()
            delay(800)
        }
    }
    // 全屏验证界面不吃掉系统返回键（关闭 = 回调 [onClose]，验证标志由 daemon 侧管理）。
    BackHandler(onBack = onClose)

    val verifying = status.verifying
    val verified = status.verified
    val failed = !verifying && !verified && started
    val stageIndex = VERIFY_STAGES.indexOfFirst { it.key == status.stage }
    val mainState = when {
        verifying -> 0
        verified -> 1
        failed -> 2
        else -> 3
    }

    var inteChecked by remember { mutableStateOf(false) }
    LaunchedEffect(verified, connected) {
        if (verified && !inteChecked && connected) {
            inteChecked = true
            val need = withContext(Dispatchers.IO) {
                ManagerServiceClient.isIntePresent() && ManagerServiceClient.getInteMode().isEmpty()
            }
            if (need) showInteDialog = true
        }
    }

    // 「开始测试 / 重试」共用入口（binder 放 IO，结果回主线程更新 UI）。
    val launchStart: () -> Unit = {
        scope.launch {
            if (startVerify()) {
                started = true
                Log.i("DripManager", "kernel verify started")
            } else {
                hint = "启动验证失败：前置检查未通过或未连接 daemon"
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "关闭")
                }
                Column {
                    Text(
                        "内核模式验证",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Crossfade(targetState = mainState, animationSpec = tween(240)) { st ->
                        Text(
                            when {
                                !connected -> "未连接 daemon"
                                st == 0 -> "正在验证"
                                st == 1 -> "已通过验证"
                                st == 2 -> "本次验证未通过"
                                else -> "待开始"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (firstLaunch && !verifying && !verified) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("默认用户模式", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "内核模式需先通过加载验证；现在可以开始测试，也可以以后不再自动弹出。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // 阶段进度（7 stage，设计卡 §3.1）
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("阶段进度", style = MaterialTheme.typography.titleSmall)
                    VERIFY_STAGES.forEachIndexed { index, stage ->
                        val state = when {
                            stageIndex < 0 -> StageMark.Pending
                            index < stageIndex -> StageMark.Done
                            index == stageIndex -> StageMark.Current
                            else -> StageMark.Pending
                        }
                        StageRow(stage.label, state)
                    }
                    if (status.stage.isNotEmpty() && stageIndex < 0) {
                        Text(
                            "未知阶段：${status.stage}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            AnimatedContent(
                targetState = mainState,
                modifier = Modifier.fillMaxWidth(),
                transitionSpec = {
                    val sizeTransform = SizeTransform(
                        clip = true,
                        sizeAnimationSpec = { _, _ -> tween(0) },
                    )
                    ContentTransform(
                        fadeIn(tween(280)) + slideInVertically { it / 8 },
                        fadeOut(tween(180)),
                        0f,
                        sizeTransform,
                    )
                },
            ) {
                when {
                    verifying -> {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { ManagerServiceClient.cancelKernelVerify() }
                                    started = false
                                    Log.i("DripManager", "kernel verify cancelled by user")
                                    hint = "已取消验证"
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("取消验证") }
                    }

                    verified -> {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                            ) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Filled.CheckCircle,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "验证通过",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        )
                                    }
                                    Text(
                                        "已成功验证:当前已进入内核模式，它将不会自动关闭。$MODE_SWITCH_HINT",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                }
                            }
                            Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("完成") }
                        }
                    }

                    failed -> {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.errorContainer,
                            ) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Filled.ErrorOutline,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onErrorContainer,
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            FEEDBACK_TITLE,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                        )
                                    }
                                    Text(
                                        FEEDBACK_BODY,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedButton(
                                    onClick = { showFeedback = true },
                                    modifier = Modifier.weight(1f),
                                ) { Text("打包反馈") }
                                Button(onClick = launchStart, modifier = Modifier.weight(1f)) { Text("重试") }
                            }
                            if (onDisableAutoPrompt != null) {
                                TextButton(
                                    onClick = onDisableAutoPrompt,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("以后不再自动弹出") }
                            }
                        }
                    }

                    else -> {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = launchStart,
                                enabled = connected,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("开始测试") }
                            if (onDisableAutoPrompt != null) {
                                TextButton(
                                    onClick = onDisableAutoPrompt,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("以后不再自动弹出") }
                            }
                        }
                    }
                }
            }

            if (status.fallback) {
                Text(
                    "已进入用户模式，请重新验证",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (status.crashPending) {
                Text(
                    "$FEEDBACK_TITLE$FEEDBACK_BODY",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    hint?.let { message ->
        AlertDialog(
            onDismissRequest = { hint = null },
            confirmButton = { TextButton(onClick = { hint = null }) { Text("确定") } },
            text = { Text(message) },
        )
    }

    if (showFeedback) {
        KernelFeedbackDialog(feedbackPath = status.feedbackPath, onDismiss = { showFeedback = false })
    }

    if (showInteDialog) {
        fun pick(mode: String) {
            scope.launch {
                withContext(Dispatchers.IO) { ManagerServiceClient.setInteMode(mode) }
                showInteDialog = false
            }
        }
        AlertDialog(
            onDismissRequest = { showInteDialog = false },
            title = { Text("检测到 inte 反安装模块") },
            text = { Text("当前设备有 inte 模块，会检测本模块。选择开机时对 inte 的处理方式：") },
            confirmButton = {
                TextButton(onClick = { pick(IManagerService.INTE_MODE_AUTO) }) {
                    Text("开机时自动卸载+装回")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { pick(IManagerService.INTE_MODE_OFF) }) {
                        Text("开机时卸载")
                    }
                    TextButton(onClick = { pick(IManagerService.INTE_MODE_NONE) }) {
                        Text("不做操作")
                    }
                }
            },
        )
    }
}

private enum class StageMark { Done, Current, Pending }

@Composable
private fun StageRow(label: String, mark: StageMark) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Crossfade(targetState = mark, animationSpec = tween(240)) { m ->
            when (m) {
                StageMark.Done -> Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                StageMark.Current -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                StageMark.Pending -> Icon(
                    Icons.Outlined.Circle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        val labelColor by animateColorAsState(
            targetValue = when (mark) {
                StageMark.Pending -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurface
            },
            animationSpec = tween(240),
        )
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = labelColor,
        )
    }
}

/** 发起验证（binder 放 IO；返回是否已写 kmod.verifying）。 */
private suspend fun startVerify(): Boolean = withContext(Dispatchers.IO) {
    ManagerServiceClient.startKernelVerify()
}

/**
 * 失败反馈弹窗（设计卡 §3.3）：原话文案 + 打包反馈（分享 tar.gz / 复制路径）。
 * daemon 未产出反馈包时只给复制路径兜底。
 */
@Composable
fun KernelFeedbackDialog(feedbackPath: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(FEEDBACK_TITLE, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(FEEDBACK_BODY, style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (feedbackPath.isBlank()) "反馈包尚未生成" else "反馈包：$feedbackPath",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { shareFeedback(context, feedbackPath) },
                enabled = feedbackPath.isNotBlank(),
            ) { Text("分享") }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = { copyToClipboard(context, feedbackPath) },
                    enabled = feedbackPath.isNotBlank(),
                ) { Text("复制路径") }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}

/** ACTION_SEND 分享反馈包：寄生模式下 manager 未安装、无 FileProvider，file:// 由临时放开的
 *  StrictMode 放行；失败回退提示复制路径。 */
private fun shareFeedback(context: Context, path: String) {
    val file = File(path)
    if (!file.isFile) {
        Toast.makeText(context, "反馈包不存在：$path", Toast.LENGTH_LONG).show()
        return
    }
    val oldPolicy = StrictMode.getVmPolicy()
    StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
    try {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/gzip"
            putExtra(Intent.EXTRA_STREAM, Uri.fromFile(file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享反馈包"))
    } catch (t: Throwable) {
        Log.w("DripManager", "share feedback failed: $t")
        Toast.makeText(context, "分享失败，请复制路径自行发送", Toast.LENGTH_LONG).show()
    } finally {
        StrictMode.setVmPolicy(oldPolicy)
    }
}

private fun copyToClipboard(context: Context, path: String) {
    try {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("drip feedback", path))
        Toast.makeText(context, "已复制路径", Toast.LENGTH_SHORT).show()
    } catch (t: Throwable) {
        Log.w("DripManager", "copy path failed: $t")
    }
}
