// 日志二级页：按模块 tag 过滤显示全部日志行，支持终端/卡片双样式。
package drip.manager.ui.page

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.WrapText
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import drip.manager.ui.component.PanelHeader

/** 日志二级页：显示某个模块（tag）的全部日志行，支持终端/卡片双样式。 */
@Composable
fun LogsScreenDetail(
    tag: String,
    lines: List<LogLine>,
    logStyle: Int,
    lineWrap: Boolean,
    onLineWrapToggle: () -> Unit,
    onBack: () -> Unit,
) {
    val tagLines = remember(lines, tag) {
        if (tag == "全部") lines else lines.filter { it.tag == tag }
    }

    Column(Modifier.fillMaxSize()) {
        PanelHeader(
            title = tag,
            subtitle = "${tagLines.size} 条日志",
            actions = {
                IconButton(onClick = onLineWrapToggle) {
                    Icon(
                        Icons.AutoMirrored.Outlined.WrapText,
                        contentDescription = "换行",
                        tint = if (lineWrap) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                }
            },
        )

        if (tagLines.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "该模块暂无日志",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(
                    items = tagLines,
                    key = { it.raw.hashCode() },
                ) { line ->
                    when (logStyle) {
                        LogDisplayStyle.CARD -> CardLogLine(line = line)
                        else -> TerminalLogLine(line = line, lineWrap = lineWrap)
                    }
                }
            }
        }
    }
}
