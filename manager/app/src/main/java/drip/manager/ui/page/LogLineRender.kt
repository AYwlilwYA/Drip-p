// 日志行渲染组件：终端样式（等宽紧凑）和卡片样式（Surface 圆角 + 级别色条）。
package drip.manager.ui.page

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 级别色：I=默认白/W=橙/E=红/D=灰。需要 Composable 上下文读取 MaterialTheme。 */
@Composable
private fun levelColor(level: Char?): Color = when (level) {
    'I' -> Color.Unspecified
    'W' -> Color(0xFFFF9800) // 橙
    'E' -> MaterialTheme.colorScheme.error
    'D' -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> Color.Unspecified
}

/** 终端样式行：等宽字体、紧凑排列、级别色。 */
@Composable
fun TerminalLogLine(line: LogLine, lineWrap: Boolean, fontSize: Float = 12f) {
    val color = levelColor(line.level)
    val text = if (line.time != null && line.level != null && line.tag != null) {
        "${line.time} ${line.level}/${line.tag}: ${line.message}"
    } else {
        line.message
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = fontSize.sp,
            lineHeight = (fontSize + 4f).sp,
        ),
        color = color,
        maxLines = if (lineWrap) Int.MAX_VALUE else 1,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 1.dp),
    )
}

/** 卡片样式行：一条一卡，级别色条 + 分行显示。 */
@Composable
fun CardLogLine(line: LogLine, fontSize: Float = 12f) {
    val barColor = levelColor(line.level).let {
        if (it == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else it
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(Modifier.fillMaxWidth()) {
            // 级别色条：左侧 4dp 竖条
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(64.dp)
                    .clip(RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
                    .background(barColor),
            )
            Column(
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            ) {
                // 第一行：时间 + 级别标签
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (line.time != null) {
                        Text(
                            text = line.time,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (line.level != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = line.level.levelLabel(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = barColor,
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                // tag 行（如果有）
                if (line.tag != null) {
                    Text(
                        text = line.tag,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(2.dp))
                // 消息正文
                Text(
                    text = line.message,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = fontSize.sp),
                    maxLines = 100,
                )
            }
        }
    }
}
