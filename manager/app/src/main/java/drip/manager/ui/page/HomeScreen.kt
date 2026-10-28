// Home 页：无顶栏，整页大卡片展示框架（ManagerServiceClient）与系统信息（真实 Build）+ 内核模式行/常驻横幅。
package drip.manager.ui.page

import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForwardIos
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import drip.manager.IManagerService
import drip.manager.data.KernelModeState
import drip.manager.data.ManagerServiceClient
import drip.manager.ui.component.PillChip
import drip.manager.ui.component.StatusPill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Home 页：品牌卡 + 运行模式/框架/系统信息 + 关于入口。未连接 daemon 时框架信息显示空态。 */
@Composable
fun HomeScreen(onOpenVerify: () -> Unit = {}) {
    val context = LocalContext.current
    var showAbout by remember { mutableStateOf(false) }
    var versionName by remember { mutableStateOf("") }
    var versionCode by remember { mutableStateOf(0L) }
    var libxposed by remember { mutableStateOf(0) }
    var buildStamp by remember { mutableStateOf("") }
    var showSwitchHint by remember { mutableStateOf(false) }
    var showFeedback by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        versionName = ManagerServiceClient.getFrameworkVersionName()
        versionCode = ManagerServiceClient.getFrameworkVersionCode()
        libxposed = ManagerServiceClient.getLibxposedApiVersion()
        buildStamp = ManagerServiceClient.getBuildStamp()
        KernelModeState.refresh()
    }

    val connected = ManagerServiceClient.connected
    val systemVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    val deviceName = Build.DEVICE
    val architecture = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"

    val status = KernelModeState.status
    val isKernel = status.mode == IManagerService.MODE_KERNEL

    // a′ 无缝切换：切换即写 kmod.mode，成功后一次性提示生效范围（设计卡 §6）。
    val switchMode: (String) -> Unit = { target ->
        scope.launch {
            val ok = withContext(Dispatchers.IO) { ManagerServiceClient.setMode(target) }
            if (ok) {
                KernelModeState.refresh()
                Log.i("DripManager", "mode switched to $target")
                showSwitchHint = true
            } else {
                hint = if (target == IManagerService.MODE_KERNEL) {
                    "切换失败：内核模式需先通过加载验证"
                } else {
                    "切换失败：daemon 未接受该模式"
                }
            }
        }
    }
    val exitSafeMode: () -> Unit = {
        scope.launch {
            withContext(Dispatchers.IO) { ManagerServiceClient.exitSafeMode() }
            KernelModeState.refresh()
            Toast.makeText(context, "已标记退出，重启后生效", Toast.LENGTH_SHORT).show()
            Log.i("DripManager", "safe mode exit requested (after reboot)")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // protocolMismatch 提示：已连上 daemon 但协议版本不匹配（常驻，直至协议匹配）
        if (ManagerServiceClient.protocolMismatch) {
            HomeBanner(
                title = "manager 需升级：daemon 协议版本不匹配",
                subtitle = "请更新 manager 以匹配 daemon",
            )
        }

        // 常驻横幅（设计卡 §10-U1：安全模式 / 验证中 / 已回退 / 崩溃待反馈）
        if (status.safeMode) {
            HomeBanner(
                title = "安全模式",
                subtitle = "已检测到 ${status.crashCount} 次系统崩溃 · 退出需重启生效",
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
                actionLabel = "退出",
                onAction = exitSafeMode,
            )
        }
        if (status.verifying) {
            HomeBanner(
                title = "正在验证内核模式",
                subtitle = if (status.stage.isEmpty()) "准备中" else "阶段：${status.stage}",
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                actionLabel = "查看",
                onAction = onOpenVerify,
            )
        }
        if (status.fallback) {
            HomeBanner(
                title = "已进入用户模式，请重新验证",
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                actionLabel = "去验证",
                onAction = onOpenVerify,
            )
        }
        if (status.crashPending) {
            HomeBanner(
                title = "当前内核不支持内核模式!",
                subtitle = "请联系作者并且反馈!将该压缩包发送至相关群聊或者提交issue!",
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                actionLabel = "打包反馈",
                onAction = { showFeedback = true },
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceBright,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text(
                            "Drip Manager",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            if (connected) "v$versionName ($versionCode)" else "未连接 daemon",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    StatusPill(
                        text = if (connected) "已连接" else "未连接",
                        containerColor = if (connected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                        contentColor = if (connected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }

                InfoRow("当前模式", modeSubtitle(status.mode, status.verified, status.verifying, connected))

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // 运行模式（设计卡 §10-U1：当前模式 + 内核模式门禁置灰）
                Text(
                    "运行模式",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillChip(
                        text = "用户模式",
                        selected = !isKernel,
                        onClick = { if (isKernel) switchMode(IManagerService.MODE_USER) },
                    )
                    PillChip(
                        text = "内核模式",
                        selected = isKernel,
                        // 未通过验证 → 门禁置灰（不可直接启用，点击走验证流程 T1）
                        modifier = Modifier.then(
                            if (status.verified) Modifier else Modifier.alpha(0.45f),
                        ),
                        onClick = {
                            when {
                                status.verifying -> onOpenVerify()
                                status.verified && !isKernel ->
                                    switchMode(IManagerService.MODE_KERNEL)
                                else -> onOpenVerify()
                            }
                        },
                    )
                }
                if (status.safeMode) {
                    InfoRow("系统崩溃", "已检测到 ${status.crashCount} 次系统崩溃")
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Framework 信息（未连接时空态）
                Text(
                    "框架信息",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (connected) {
                    InfoRow("Framework", "$versionName · build $versionCode")
                    InfoRow("libxposed", libxposed.toString())
                    InfoRow("构建来源", buildStamp)
                } else {
                    InfoRow("Framework", "未连接 daemon")
                    InfoRow("libxposed", "—")
                    InfoRow("构建来源", "—")
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // 系统信息（真实 Build 属性）
                Text(
                    "系统信息",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                InfoRow("系统版本", systemVersion)
                InfoRow("设备名称", deviceName)
                InfoRow("系统架构", architecture)

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // 关于入口
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showAbout = true }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "关于",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.weight(1f))
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowForwardIos,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (showAbout) {
        AboutDialog(onDismiss = { showAbout = false })
    }
    if (showFeedback) {
        KernelFeedbackDialog(feedbackPath = status.feedbackPath, onDismiss = { showFeedback = false })
    }
    if (showSwitchHint) {
        AlertDialog(
            onDismissRequest = { showSwitchHint = false },
            confirmButton = { TextButton(onClick = { showSwitchHint = false }) { Text("确定") } },
            title = { Text("模式已切换") },
            text = { Text(MODE_SWITCH_HINT) },
        )
    }
    hint?.let { message ->
        AlertDialog(
            onDismissRequest = { hint = null },
            confirmButton = { TextButton(onClick = { hint = null }) { Text("确定") } },
            text = { Text(message) },
        )
    }
}

/** 模式行副标题（空态 / 验证态 / 门禁态对号入座）。 */
private fun modeSubtitle(
    mode: String?,
    verified: Boolean,
    verifying: Boolean,
    connected: Boolean,
): String = when {
    !connected -> "未连接 daemon"
    verifying -> "正在验证…"
    !verified -> "未通过验证，需先完成加载验证"
    mode == IManagerService.MODE_KERNEL -> "内核模式"
    else -> "用户模式"
}

/** 常驻横幅（复用 protocolMismatch 横幅结构；可选尾部操作）。 */
@Composable
private fun HomeBanner(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    containerColor: Color = MaterialTheme.colorScheme.errorContainer,
    contentColor: Color = MaterialTheme.colorScheme.onErrorContainer,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Warning, contentDescription = null, tint = contentColor)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.8f),
                    )
                }
            }
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) {
                    Text(actionLabel, color = contentColor, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

