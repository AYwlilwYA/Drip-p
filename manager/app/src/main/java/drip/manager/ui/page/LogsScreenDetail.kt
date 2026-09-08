// 日志二级页：按模块 tag 过滤显示全部日志行，支持终端/卡片双样式。
package drip.manager.ui.page

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.WrapText
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import drip.manager.ui.component.PanelHeader

/** 日志二级页：显示某个模块（tag）或模块独立日志的全部日志行，支持终端/卡片双样式。 */
@Composable
fun LogsScreenDetail(
    tag: String,
    lines: List<LogLine>,
    moduleMode: Boolean,
    loading: Boolean,
    logStyle: Int,
    lineWrap: Boolean,
    onLineWrapToggle: () -> Unit,
    onBack: () -> Unit,
) {
    // 字体缩放（不开换行时放大看长行）
    var fontSize by rememberSaveable(tag) { mutableFloatStateOf(12f) }

    val tagLines = remember(lines, tag, moduleMode) {
        when {
            moduleMode -> lines
            tag == "全部" -> lines
            tag == "框架" -> lines.filter { it.tag != null && isFrameworkTag(it.tag!!) }
            else -> lines.filter { it.tag == tag }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        PanelHeader(
            title = tag,
            subtitle = if (loading) "加载中…" else "${tagLines.size} 条日志",
            actions = {
                IconButton(onClick = { fontSize = (fontSize - 1f).coerceAtLeast(8f) }) {
                    Icon(Icons.Outlined.Remove, contentDescription = "缩小")
                }
                IconButton(onClick = { fontSize = (fontSize + 1f).coerceAtMost(26f) }) {
                    Icon(Icons.Outlined.Add, contentDescription = "放大")
                }
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
                    if (loading) "加载中…" else "该模块暂无日志",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            // 2026-09-06 #7：两指捏合缩放字号（单指事件不消费，交还 LazyColumn 滚动）。
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(tag) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val event = awaitPointerEvent()
                                if (event.changes.count { it.pressed } >= 2) {
                                    val zoom = event.calculateZoom()
                                    if (zoom != 1f) {
                                        fontSize = (fontSize * zoom).coerceIn(8f, 30f)
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    },
            ) {
                // key 用 index：日志行可重复（模块反复打印相同内容），raw.hashCode()
                // 会 key 冲突导致 Compose 复用错乱、渲染出空行。
                itemsIndexed(
                    // 2026-09-06：最新日志在上（文件按时间顺序写，倒序渲染即新→旧）
                    items = tagLines.asReversed(),
                    key = { index, _ -> index },
                ) { _, line ->
                    when (logStyle) {
                        LogDisplayStyle.CARD -> CardLogLine(line = line, fontSize = fontSize)
                        else -> TerminalLogLine(line = line, lineWrap = lineWrap, fontSize = fontSize)
                    }
                }
            }
        }
    }
}
