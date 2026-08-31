// Modules 页：模块列表（PanelHeader + 搜索 + 多用户 tab + 模块卡），数据走 ManagerServiceClient。
package drip.manager.ui.page

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import drip.manager.data.ManagerServiceClient
import drip.manager.ui.component.AppIconPlaceholder
import drip.manager.ui.component.PanelHeader
import drip.manager.ui.component.PillChip
import drip.manager.ui.component.SearchField
import drip.manager.ui.component.SheetAction
import drip.manager.ui.component.StatusPill
import java.util.LinkedHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 模块行 UI 数据：契约 ModuleInfo + 本地解析的 scope 预览标签 */
private data class UiModule(
    val info: drip.manager.ModuleInfo,
    val scopeLabels: List<String>,
)

/** Modules 页：模块列表（点击进 Scope，长按弹操作面板）。未连接 daemon 时空态。 */
@Composable
fun ModulesScreen(
    onModuleClick: (drip.manager.ModuleInfo) -> Unit,
) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var onlyEnabled by rememberSaveable { mutableStateOf(false) }
    var onlyFailed by rememberSaveable { mutableStateOf(false) }
    var selectedUser by rememberSaveable { mutableStateOf(0) }
    var menuExpanded by remember { mutableStateOf(false) }
    var longPressModule by remember { mutableStateOf<drip.manager.ModuleInfo?>(null) }
    var modules by remember { mutableStateOf<List<UiModule>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var isRefreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 模块图标内存缓存：跨 item 复用，避免滚动重复解码卡顿（同 ScopeScreen 模式，LRU 上限 256）。
    val iconCache = remember {
        object : LinkedHashMap<String, Bitmap>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>) = size > 256
        }
    }

    // 首次进入 + 下拉刷新共用的加载逻辑：binder 调用放 IO 线程，避免阻塞主线程。
    val loadModules: suspend () -> Unit = {
        val built = withContext(Dispatchers.IO) {
            val list = mutableListOf<UiModule>()
            // 显示所有已安装的 Xposed 模块（含未启用的），enabled 状态由
            // ModuleInfo.enabled 区分；scope 预览标签本地解析。
            for (info in ManagerServiceClient.getAllModules()) {
                val labels = ManagerServiceClient.getModuleScope(info.packageName)
                    .map { resolveAppLabel(context, it.appPackageName) }
                list += UiModule(info, labels)
            }
            list
        }
        modules = built
        loading = false
    }

    LaunchedEffect(Unit) {
        ManagerServiceClient.connect()
        loadModules()
    }

    val connected = ManagerServiceClient.connected
    // 多用户 tab 真实过滤（ModuleInfo.userId）+ 已启用模块置顶（enabled 在前，disabled 在后，各自保持现有顺序）
    val filtered = remember(query, onlyEnabled, onlyFailed, selectedUser, modules) {
        val list = modules.filter { m ->
            (query.isBlank() || m.info.name.contains(query, ignoreCase = true) ||
                    m.info.packageName.contains(query, ignoreCase = true)) &&
                    (!onlyEnabled || (m.info.enabled && !m.info.loadFailed)) &&
                    (!onlyFailed || m.info.loadFailed) &&
                    (if (selectedUser == 0) m.info.userId == 0 else m.info.userId == 10)
        }
        val (enabled, disabled) = list.partition { it.info.enabled }
        enabled + disabled
    }
    // activeCount 按过滤后可见列表统计
    val activeCount = filtered.count { it.info.enabled && !it.info.loadFailed }

    Column(Modifier.fillMaxSize()) {
        PanelHeader(
            title = "模块",
            subtitle = if (connected) "$activeCount of ${filtered.size} active" else "未连接 daemon",
        )

        SearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = "搜索模块",
            modifier = Modifier.padding(horizontal = 20.dp),
            trailingContent = {
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Outlined.FilterList, contentDescription = "过滤/排序")
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("仅显示启用") },
                            onClick = { onlyEnabled = !onlyEnabled; menuExpanded = false },
                            trailingIcon = {
                                if (onlyEnabled) Icon(Icons.Filled.Check, contentDescription = null)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("仅显示加载失败") },
                            onClick = { onlyFailed = !onlyFailed; menuExpanded = false },
                            trailingIcon = {
                                if (onlyFailed) Icon(Icons.Filled.Check, contentDescription = null)
                            },
                        )
                    }
                }
            },
        )

        // 多用户 tab
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("User 0", "User 10").forEachIndexed { index, label ->
                PillChip(
                    text = label,
                    selected = selectedUser == index,
                    onClick = { selectedUser = index },
                )
            }
        }

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                scope.launch {
                    isRefreshing = true
                    try {
                        loadModules()
                    } finally {
                        isRefreshing = false
                    }
                }
            },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when {
                // 空态/未连接态加 verticalScroll：内容不满一屏也能产生 overscroll，
                // 让 PullToRefreshBox 仍可响应下拉刷新（未连接时下拉可重试）。
                loading -> Box(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("加载中…", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                !connected -> Box(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("未连接 daemon", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                filtered.isEmpty() -> Box(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("无匹配的模块", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(filtered, key = { it.info.packageName }) { module ->
                        ModuleRow(
                            module = module,
                            iconCache = iconCache,
                            onClick = { onModuleClick(module.info) },
                            onLongClick = { longPressModule = module.info },
                        )
                    }
                }
            }
        }
    }

    longPressModule?.let { module ->
        ModuleActionSheet(module = module, iconCache = iconCache, onDismiss = { longPressModule = null })
    }
}

/** 本地按包名解析 app 标签（契约 §4.3：图标/标签不跨进程）。 */
private fun resolveAppLabel(context: Context, packageName: String): String = try {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
} catch (_: Throwable) {
    packageName
}

/** 模块应用图标：本地 PackageManager 按包名取真实图标，内存 LRU 缓存复用（同 ScopeScreen 模式），取不到回退首字母占位。 */
@Composable
private fun ModuleAppIcon(
    packageName: String,
    fallbackName: String,
    size: Dp,
    cache: MutableMap<String, Bitmap>,
) {
    val context = LocalContext.current
    val pm = context.packageManager
    var icon by remember(packageName) { mutableStateOf(cache[packageName]) }
    LaunchedEffect(packageName) {
        val cached = cache[packageName]
        if (cached != null) {
            icon = cached
        } else {
            val bmp = withContext(Dispatchers.IO) {
                try {
                    pm.getApplicationInfo(packageName, 0).loadIcon(pm).toBitmap()
                } catch (_: Throwable) {
                    null
                }
            }
            icon = bmp
            if (bmp != null) cache[packageName] = bmp
        }
    }
    val current = icon
    if (current != null) {
        Image(
            bitmap = current.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.size(size),
        )
    } else {
        AppIconPlaceholder(fallbackName, size = size)
    }
}

/** 模块卡：名称颜色承载状态 + 行 alpha + API badge + 描述可展开 + scope 预览。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModuleRow(
    module: UiModule,
    iconCache: MutableMap<String, Bitmap>,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val info = module.info
    val nameColor = when {
        info.loadFailed -> MaterialTheme.colorScheme.error
        info.enabled -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (info.enabled) 1f else 0.45f),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ModuleAppIcon(info.packageName, info.name, 40.dp, iconCache)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = info.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = nameColor,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        StatusPill(
                            text = "API ${info.apiVersion}",
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = info.versionName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (info.loadFailed) {
                Text(
                    text = "加载失败：模块未注入",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // 描述：固定 4 行截断，不展开
            Text(
                text = info.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                // scope 预览：前 3 个 app 图标圈 + "+N"
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    module.scopeLabels.take(3).forEach { label ->
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = label.firstOrNull()?.uppercase() ?: "?",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                    if (module.scopeLabels.size > 3) {
                        Text(
                            text = "+${module.scopeLabels.size - 3}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** 模块长按操作面板：头部 + 打开/详情/强制停止/重启作用域/卸载（daemon 未连接时降级留痕）。 */
@Composable
private fun ModuleActionSheet(
    module: drip.manager.ModuleInfo,
    iconCache: MutableMap<String, Bitmap>,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Row(
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ModuleAppIcon(module.packageName, module.name, 40.dp, iconCache)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        module.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        module.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = "版本 ${module.versionName} · API ${module.apiVersion}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SheetAction(
                title = "打开应用",
                icon = Icons.AutoMirrored.Outlined.OpenInNew,
                onClick = {
                    try {
                        val intent = context.packageManager.getLaunchIntentForPackage(module.packageName)
                        if (intent != null) {
                            context.startActivity(intent)
                        } else {
                            Toast.makeText(context, "未找到可启动的应用入口", Toast.LENGTH_SHORT).show()
                        }
                    } catch (_: Throwable) {
                        Toast.makeText(context, "无法打开应用", Toast.LENGTH_SHORT).show()
                    }
                    onDismiss()
                },
            )
            SheetAction(
                title = "应用详情",
                icon = Icons.Outlined.Info,
                onClick = {
                    try {
                        val intent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", module.packageName, null),
                        )
                        context.startActivity(intent)
                    } catch (_: Throwable) {
                        Toast.makeText(context, "无法打开应用详情", Toast.LENGTH_SHORT).show()
                    }
                    onDismiss()
                },
            )
            SheetAction(
                title = "强制停止",
                icon = Icons.Outlined.StopCircle,
                onClick = {
                    ManagerServiceClient.forceStopPackage(module.packageName, module.userId)
                    Toast.makeText(context, "已强制停止", Toast.LENGTH_SHORT).show()
                    onDismiss()
                },
            )
            // 热重载：重启该模块作用域内所有 app（daemon 聚合 forceStopPackage，
            // 读 DB scope + staticScope，进程重拉后注入最新配置）。
            SheetAction(
                title = "重启作用域进程",
                icon = Icons.Outlined.RestartAlt,
                onClick = {
                    val ok = ManagerServiceClient.restartModuleScopeProcesses(module.packageName)
                    Toast.makeText(
                        context,
                        if (ok) "已重启作用域进程" else "重启失败（未连接 daemon）",
                        Toast.LENGTH_SHORT,
                    ).show()
                    onDismiss()
                },
            )
            SheetAction(
                title = "卸载",
                icon = Icons.Outlined.Delete,
                tint = MaterialTheme.colorScheme.error,
                onClick = {
                    val ok = ManagerServiceClient.uninstallPackage(module.packageName, module.userId)
                    Toast.makeText(context, if (ok) "已卸载" else "卸载失败（未连接 daemon）", Toast.LENGTH_SHORT).show()
                    onDismiss()
                },
            )
        }
    }
}
