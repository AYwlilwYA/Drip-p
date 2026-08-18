// Home 页：无顶栏，整页大卡片展示框架（ManagerServiceClient）与系统信息（真实 Build）。
package drip.manager.ui.page

import android.os.Build
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import drip.manager.data.ManagerServiceClient
import drip.manager.ui.component.StatusPill

/** Home 页：品牌卡 + 框架/系统信息 + 关于入口。未连接时框架信息显示空态。 */
@Composable
fun HomeScreen() {
    var showAbout by remember { mutableStateOf(false) }
    var versionName by remember { mutableStateOf("") }
    var versionCode by remember { mutableStateOf(0L) }
    var libxposed by remember { mutableStateOf(0) }
    var buildStamp by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        versionName = ManagerServiceClient.getFrameworkVersionName()
        versionCode = ManagerServiceClient.getFrameworkVersionCode()
        libxposed = ManagerServiceClient.getLibxposedApiVersion()
        buildStamp = ManagerServiceClient.getBuildStamp()
    }

    val connected = ManagerServiceClient.connected
    val systemVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    val deviceName = Build.DEVICE
    val architecture = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // protocolMismatch 提示：已连上但协议版本不匹配（常驻，直至协议匹配）
        if (ManagerServiceClient.protocolMismatch) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.errorContainer,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            "manager 需升级：daemon 协议版本不匹配",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            "请更新 manager 以匹配 daemon",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                        )
                    }
                }
            }
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
