// Logs 页：模块一级列表 + 独立全屏二级页 + 顶部 part 工具条。数据经 ManagerServiceClient。
package drip.manager.ui.page

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.WrapText
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import drip.manager.data.ManagerServiceClient
import drip.manager.data.SettingsState
import drip.manager.ui.component.PanelHeader
import drip.manager.ui.component.SheetAction
import drip.manager.ui.component.SheetHeading
import drip.manager.ui.component.ToggleRow
import java.io.FileInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Logs 页：模块一级列表 + 独立全屏二级页。
 *
 * 核心流程：
 * 1. 拉取 part 列表 → 选中 part 读内容 → 解析日志行
 * 2. 顶部工具条：part 选择（单通道，不再有 normal/verbose 切换）
 * 3. 主视图：最顶端「全部日志」项 + 模块列表（tag + 条数）
 * 4. 点击「全部日志」或某模块 → 进独立全屏二级页（LogsScreenDetail）
 */
@Composable
fun LogsScreen(
    settings: SettingsState,
    onSettingsChange: (SettingsState) -> Unit,
    initialTag: String? = null, // 通知点击跳转时传入的初始过滤 tag
) {
    val context = LocalContext.current
    var lineWrap by rememberSaveable { mutableStateOf(false) }
    var verbose by rememberSaveable { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var parts by remember { mutableStateOf<List<String>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }

    // 当前选中的 part
    var selectedPart by remember { mutableStateOf<String?>(null) }
    var contentLoading by remember { mutableStateOf(false) }

    // 解析后的日志行
    var parsedLines by remember { mutableStateOf<List<LogLine>>(emptyList()) }

    // 模块列表（tag + 计数），按条数降序
    var tagCounts by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }

    // 二级页状态：非 null 进入详情（"全部" 或某个 tag）
    var detailTag by remember { mutableStateOf<String?>(null) }

    // 通知点击跳转：直接进二级页（等 parsedLines 加载完再设置）
    var initialTagApplied by remember { mutableStateOf(false) }
    LaunchedEffect(initialTag, parsedLines) {
        if (initialTag != null && !initialTagApplied && parsedLines.isNotEmpty()) {
            detailTag = initialTag
            initialTagApplied = true
        }
    }

    // 初次连接：读真实 verbose 开关
    LaunchedEffect(Unit) {
        ManagerServiceClient.connect()
        verbose = ManagerServiceClient.isVerboseLogEnabled()
        refreshKey++
    }

    // 拉取 part 列表，首次加载时自动选中最新 part
    LaunchedEffect(refreshKey) {
        if (refreshKey > 0) {
            parts = withContext(Dispatchers.IO) { ManagerServiceClient.getLogParts(false) }
            loaded = true
            // 首次加载后自动选中最新 part
            if (selectedPart == null || selectedPart !in parts) {
                selectedPart = parts.lastOrNull()
            }
        }
    }

    // 选中 part 后读取内容 + 解析（模块列表数据源）
    LaunchedEffect(selectedPart) {
        val name = selectedPart ?: return@LaunchedEffect
        contentLoading = true
        val text = withContext(Dispatchers.IO) { readLogPart(name) }
        val lines = withContext(Dispatchers.Default) { parseLogLines(text) }
        parsedLines = lines
        tagCounts = extractTags(lines)
        contentLoading = false
    }

    // ── 二级页 BackHandler：拦截系统返回键，回到一级页而非退出 Activity ──
    BackHandler(enabled = detailTag != null) { detailTag = null }

    // ── 二级页：AnimatedVisibility 从右侧滑入 + 淡入过渡 ──
    AnimatedVisibility(
        visible = detailTag != null,
        enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
        exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
    ) {
        val currentDetailTag = detailTag
        if (currentDetailTag != null) {
            LogsScreenDetail(
                tag = currentDetailTag,
                lines = parsedLines,
                logStyle = settings.logDisplayStyle,
                lineWrap = lineWrap,
                onLineWrapToggle = { lineWrap = !lineWrap },
                onBack = { detailTag = null },
            )
        }
    }

    // ── 一级页 ──
    Column(Modifier.fillMaxSize()) {
        // 标题栏：标题 + 状态副标题 + 换行/设置按钮
        PanelHeader(
            title = "日志",
            subtitle = when {
                !loaded -> "加载中…"
                parts.isEmpty() -> "暂无日志部分"
                else -> {
                    val totalLogs = parsedLines.size
                    "共 ${parts.size} 个 part · $totalLogs 条日志"
                }
            },
            actions = {
                IconButton(onClick = { lineWrap = !lineWrap }) {
                    Icon(
                        Icons.AutoMirrored.Outlined.WrapText,
                        contentDescription = "换行",
                        tint = if (lineWrap) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { settingsOpen = true }) {
                    Icon(Icons.Outlined.Tune, contentDescription = "设置")
                }
            },
        )

        // ── Part 选择工具条：单通道 part 文件选择 ──
        if (loaded && parts.isNotEmpty()) {
            Column(Modifier.fillMaxWidth()) {
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Part 文件列表（可横向滚动）
                    parts.forEach { name ->
                        FilterChip(
                            selected = selectedPart == name,
                            onClick = { selectedPart = name },
                            label = { Text(name, fontSize = 12.sp) },
                        )
                    }
                }
                HorizontalDivider()
            }
        }

        // ── 主视图：「全部日志」项 + 模块列表 ──
        when {
            !loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "加载中…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            parts.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "暂无日志内容",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            contentLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "加载中…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                LazyColumn(Modifier.fillMaxSize()) {
                    // ── 最顶端「全部日志」项 ──
                    item(key = "all_logs") {
                        ListItem(
                            modifier = Modifier.clickable { detailTag = "全部" },
                            headlineContent = {
                                Text("全部日志", fontWeight = FontWeight.Medium)
                            },
                            supportingContent = {
                                Text(
                                    "${parsedLines.size} 条日志",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            trailingContent = {
                                Icon(
                                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                    contentDescription = "查看",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                        HorizontalDivider()
                    }

                    // ── 模块列表：每模块一行（模块名 + 日志条数） ──
                    if (tagCounts.isNotEmpty()) {
                        item(key = "module_header") {
                            Text(
                                "模块",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                            )
                        }
                        items(
                            items = tagCounts,
                            key = { it.first },
                        ) { (tag, count) ->
                            ListItem(
                                modifier = Modifier.clickable { detailTag = tag },
                                headlineContent = { Text(tag) },
                                supportingContent = {
                                    Text(
                                        "$count 条日志",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                trailingContent = {
                                    Icon(
                                        Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                        contentDescription = "查看",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )
                        }
                    }
                }
            }
        }
    }

    // ── 设置抽屉 ──
    if (settingsOpen) {
        LogSettingsSheet(
            verbose = verbose,
            logStyle = settings.logDisplayStyle,
            onVerboseChange = {
                verbose = it
                ManagerServiceClient.setVerboseLogEnabled(it)
            },
            onLogStyleChange = {
                onSettingsChange(settings.copy(logDisplayStyle = it))
            },
            onRotate = {
                ManagerServiceClient.startNewLogPart(false) // 单通道，忽略 verbose
                Toast.makeText(context, "日志已轮转", Toast.LENGTH_SHORT).show()
                selectedPart = null
                refreshKey++
            },
            onDismiss = { settingsOpen = false },
        )
    }
}

/** 日志设置抽屉：verbose 开关（控制 d 级采集）+ 显示样式选择 + 轮转。 */
@Composable
private fun LogSettingsSheet(
    verbose: Boolean,
    logStyle: Int,
    onVerboseChange: (Boolean) -> Unit,
    onLogStyleChange: (Int) -> Unit,
    onRotate: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SheetHeading("日志设置", Icons.Outlined.Tune)
            ToggleRow(
                title = "详细日志 (verbose)",
                subtitle = "记录框架 d 级别日志",
                icon = Icons.Outlined.Visibility,
                checked = verbose,
                onCheckedChange = onVerboseChange,
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            // 显示样式选择
            SheetHeading("显示样式", Icons.Outlined.Tune)
            androidx.compose.foundation.layout.Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FilterChip(
                    selected = logStyle == LogDisplayStyle.TERMINAL,
                    onClick = { onLogStyleChange(LogDisplayStyle.TERMINAL) },
                    label = { Text("终端") },
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    selected = logStyle == LogDisplayStyle.CARD,
                    onClick = { onLogStyleChange(LogDisplayStyle.CARD) },
                    label = { Text("卡片") },
                    modifier = Modifier.weight(1f),
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SheetAction(
                title = "轮转当前日志",
                subtitle = "关闭当前 part 并开启新 part",
                icon = Icons.Outlined.RestartAlt,
                tint = MaterialTheme.colorScheme.error,
                onClick = { onRotate(); onDismiss() },
            )
        }
    }
}

/** 经 PFD 读取指定 part 的日志文本（单通道，忽略 verbose）。 */
private fun readLogPart(name: String): String {
    val pfd = ManagerServiceClient.getLogPart(false, name)
        ?: return "读取失败：未返回文件描述符（part 可能已被轮转删除）"
    return try {
        FileInputStream(pfd.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (t: Throwable) {
        "读取失败：$t"
    } finally {
        try {
            pfd.close()
        } catch (_: Throwable) {
        }
    }
}
