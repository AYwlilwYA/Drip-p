// Logs 页：模块一级列表 + 独立全屏二级页 + 顶部 part 工具条。数据经 ManagerServiceClient。
package drip.manager.ui.page

import android.widget.Toast
import androidx.activity.compose.BackHandler
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
    initialTag: String? = null,
) {
    val context = LocalContext.current
    var lineWrap by rememberSaveable { mutableStateOf(false) }
    var verbose by rememberSaveable { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var parts by remember { mutableStateOf<List<String>>(emptyList()) }
    var moduleNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var selectedPart by remember { mutableStateOf<String?>(null) }
    var contentLoading by remember { mutableStateOf(false) }
    var parsedLines by remember { mutableStateOf<List<LogLine>>(emptyList()) }
    var moduleLines by remember { mutableStateOf<List<LogLine>>(emptyList()) }
    var moduleLoading by remember { mutableStateOf(false) }
    var tagCounts by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    var detailTag by remember { mutableStateOf<String?>(null) }

    var initialTagApplied by remember { mutableStateOf(false) }
    LaunchedEffect(initialTag, parsedLines) {
        if (initialTag != null && !initialTagApplied && parsedLines.isNotEmpty()) {
            detailTag = initialTag
            initialTagApplied = true
        }
    }

    LaunchedEffect(Unit) {
        ManagerServiceClient.connect()
        verbose = ManagerServiceClient.isVerboseLogEnabled()
        refreshKey++
    }

    LaunchedEffect(refreshKey) {
        if (refreshKey > 0) {
            parts = withContext(Dispatchers.IO) { ManagerServiceClient.getLogParts(false) }
            // 模块独立日志：过滤框架内部 tag（已在主日志"框架"分组展示）
            moduleNames = withContext(Dispatchers.IO) { ManagerServiceClient.getModuleNames() }
                .filter { !isFrameworkTag(it) }
            loaded = true
            if (selectedPart == null || selectedPart !in parts) {
                selectedPart = parts.lastOrNull()
            }
        }
    }

    // 模块日志模式：点击模块列表项 → 从 log/modules/ 读该模块的独立日志
    val detailIsModule = detailTag != null && detailTag in moduleNames
    LaunchedEffect(detailTag) {
        val name = detailTag
        if (name != null && name in moduleNames) {
            moduleLoading = true
            val text = withContext(Dispatchers.IO) { readModuleLog(name) }
            moduleLines = withContext(Dispatchers.Default) { parseLogLines(text) }
            moduleLoading = false
        }
    }

    LaunchedEffect(selectedPart) {
        val name = selectedPart ?: return@LaunchedEffect
        contentLoading = true
        val text = withContext(Dispatchers.IO) { readLogPart(name) }
        val lines = withContext(Dispatchers.Default) { parseLogLines(text) }
        parsedLines = lines
        tagCounts = extractTags(lines)
        contentLoading = false
    }

    BackHandler(enabled = detailTag != null) { detailTag = null }

    // 二级页用 Box z-ordering 完全覆盖一级页
    Box(Modifier.fillMaxSize()) {
        // ── 一级页 ──
        Column(Modifier.fillMaxSize()) {
            PanelHeader(
                title = "日志",
                subtitle = when {
                    !loaded -> "加载中…"
                    parts.isEmpty() -> "暂无日志部分"
                    else -> "共 ${parts.size} 个 part · ${parsedLines.size} 条日志"
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

            when {
                !loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("加载中…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                parts.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无日志内容", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                contentLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("加载中…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> {
                    LazyColumn(Modifier.fillMaxSize()) {
                        item(key = "all_logs") {
                            ListItem(
                                modifier = Modifier.clickable { detailTag = "全部" },
                                headlineContent = { Text("全部日志", fontWeight = FontWeight.Medium) },
                                supportingContent = {
                                    Text("${parsedLines.size} 条日志", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                },
                                trailingContent = {
                                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = "查看", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )
                            HorizontalDivider()
                        }
                        if (tagCounts.isNotEmpty()) {
                            item(key = "module_header") {
                                Text("模块", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
                            }
                            items(items = tagCounts, key = { it.first }) { (tag, count) ->
                                ListItem(
                                    modifier = Modifier.clickable { detailTag = tag },
                                    headlineContent = { Text(tag) },
                                    supportingContent = {
                                        Text("$count 条日志", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    },
                                    trailingContent = {
                                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = "查看", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                )
                            }
                        }
                        if (moduleNames.isNotEmpty()) {
                            item(key = "module_log_header") {
                                Text("模块独立日志", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
                            }
                            items(items = moduleNames, key = { "mod_$it" }) { name ->
                                ListItem(
                                    modifier = Modifier.clickable { detailTag = name },
                                    headlineContent = { Text(name) },
                                    supportingContent = {
                                        Text("模块日志", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    },
                                    trailingContent = {
                                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = "查看", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── 二级页覆盖层（z-order 在一级页之上） ──
        val currentDetailTag = detailTag
        if (currentDetailTag != null) {
            LogsScreenDetail(
                tag = currentDetailTag,
                lines = if (detailIsModule) moduleLines else parsedLines,
                moduleMode = detailIsModule,
                loading = detailIsModule && moduleLoading,
                logStyle = settings.logDisplayStyle,
                lineWrap = lineWrap,
                onLineWrapToggle = { lineWrap = !lineWrap },
                onBack = { detailTag = null },
            )
        }
    }

    // ── 设置抽屉 ──
    if (settingsOpen) {
        LogSettingsSheet(
            verbose = verbose,
            logStyle = settings.logDisplayStyle,
            onVerboseChange = { verbose = it; ManagerServiceClient.setVerboseLogEnabled(it) },
            onLogStyleChange = { onSettingsChange(settings.copy(logDisplayStyle = it)) },
            onRotate = {
                ManagerServiceClient.startNewLogPart(false)
                Toast.makeText(context, "日志已轮转", Toast.LENGTH_SHORT).show()
                selectedPart = null
                refreshKey++
            },
            onDismiss = { settingsOpen = false },
        )
    }
}

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
            ToggleRow(title = "详细日志 (verbose)", subtitle = "记录框架 d 级别日志", icon = Icons.Outlined.Visibility, checked = verbose, onCheckedChange = onVerboseChange)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SheetHeading("显示样式", Icons.Outlined.Tune)
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterChip(selected = logStyle == LogDisplayStyle.TERMINAL, onClick = { onLogStyleChange(LogDisplayStyle.TERMINAL) }, label = { Text("终端") }, modifier = Modifier.weight(1f))
                FilterChip(selected = logStyle == LogDisplayStyle.CARD, onClick = { onLogStyleChange(LogDisplayStyle.CARD) }, label = { Text("卡片") }, modifier = Modifier.weight(1f))
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SheetAction(title = "轮转当前日志", subtitle = "关闭当前 part 并开启新 part", icon = Icons.Outlined.RestartAlt, tint = MaterialTheme.colorScheme.error, onClick = { onRotate(); onDismiss() })
        }
    }
}

private fun readLogPart(name: String): String {
    var lastPfdError: String? = null
    repeat(3) { attempt ->
        val pfd = ManagerServiceClient.getLogPart(false, name)
        if (pfd == null) {
            lastPfdError = "未返回文件描述符"
            if (attempt < 2) { Thread.sleep(200); return@repeat }
        } else {
            try {
                val bytes = FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
                return bytes.toString(Charsets.UTF_8)
            } catch (t: Throwable) {
                lastPfdError = "${t.javaClass.simpleName}: ${t.message}"
                if (attempt < 2) { Thread.sleep(200); return@repeat }
            } finally {
                try { pfd.close() } catch (_: Throwable) {}
            }
        }
    }
    val content = try { ManagerServiceClient.getLogPartContent(false, name) } catch (_: Throwable) { null }
    if (content != null) return content
    return "读取失败：PFD ($lastPfdError) + String fallback 均失败"
}

/** 读模块独立日志（log/modules/<part>/<module>.log）。String 版优先（PFD 在部分 ROM 上 DeadObject）。 */
private fun readModuleLog(moduleName: String): String {
    repeat(3) { attempt ->
        val content = try { ManagerServiceClient.getModuleLogContent(moduleName) } catch (_: Throwable) { null }
        if (content != null) return content
        if (attempt < 2) Thread.sleep(200)
    }
    return "读取失败：getModuleLogContent 无内容"
}
