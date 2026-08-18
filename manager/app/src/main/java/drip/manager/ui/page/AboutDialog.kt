// 关于对话框：版本/构建信息（Manager 用 BuildConfig，Framework 走 ManagerServiceClient）。
package drip.manager.ui.page

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import drip.manager.BuildConfig
import drip.manager.data.ManagerServiceClient

/** 关于对话框：展示框架与 manager 的版本信息。 */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("关于 Drip Manager", fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                Text(
                    "Drip --- 拥有更加强大隐藏能力的Xposed框架",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Text("Manager", style = MaterialTheme.typography.labelMedium)
                Text(
                    "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text("Framework", style = MaterialTheme.typography.labelMedium)
                if (connected) {
                    Text(
                        "v$versionName ($versionCode)",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "libxposed $libxposed",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        buildStamp,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "未连接 daemon",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "AlanYan",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("确定") }
        },
    )
}
