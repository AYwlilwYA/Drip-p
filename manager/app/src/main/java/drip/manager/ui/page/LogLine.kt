// 日志行解析器：将框架原始日志行解析为结构化 LogLine，用于模块隔离与双样式渲染。
package drip.manager.ui.page

import java.util.regex.Pattern

/** 单条结构化日志行。time/tag 可为 null（不匹配标准格式时兜底）。 */
data class LogLine(
    val time: String?,
    val level: Char?,
    val tag: String?,
    val message: String,
    val raw: String,
)

/** 级别字母 → 可读中文标签。 */
fun Char.levelLabel(): String = when (this) {
    'I' -> "INFO"
    'W' -> "WARN"
    'E' -> "ERROR"
    'D' -> "DEBUG"
    else -> "OTHER"
}

// 标准日志格式：
// 2026-08-18 18:53:11.545 I/DripDaemon: Drip daemon ready
private val LOG_PATTERN: Pattern = Pattern.compile(
    "^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+([IWED])/([^:]+):\\s(.*)$",
)

/** 解析单行日志。不匹配标准格式时整体作为 message，tag/level/time 均为 null。 */
fun parseLogLine(line: String): LogLine {
    val m = LOG_PATTERN.matcher(line)
    return if (m.matches()) {
        LogLine(
            time = m.group(1),
            level = m.group(2)?.firstOrNull(),
            tag = m.group(3),
            message = m.group(4) ?: "",
            raw = line,
        )
    } else {
        LogLine(time = null, level = null, tag = null, message = line, raw = line)
    }
}

/** 批量解析日志文本，按行拆分后过滤空行，返回 List<LogLine>。 */
fun parseLogLines(text: String): List<LogLine> =
    text.lineSequence()
        .filter { it.isNotBlank() }
        .map { parseLogLine(it) }
        .toList()

/** 从解析后的日志行中提取所有 tag 及其出现次数，返回按数量降序排列的 List<Pair<tag, count>>。 */
fun extractTags(lines: List<LogLine>): List<Pair<String, Int>> =
    lines.filter { it.tag != null }
        .groupBy { it.tag!! }
        .map { (tag, list) -> tag to list.size }
        .sortedByDescending { it.second }

/** 日志显示样式常量。 */
object LogDisplayStyle {
    const val TERMINAL = 0
    const val CARD = 1
}
