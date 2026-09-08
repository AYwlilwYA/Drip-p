// Scope 子页：单行顶栏 + 搜索（选择/过滤/排序三抽屉）+ 勾选 + ApplyBar，数据走 ManagerServiceClient。
package drip.manager.ui.page

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.RemoveDone
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import drip.manager.ScopeEntry
import drip.manager.data.ManagerServiceClient
import drip.manager.ui.component.AppIconPlaceholder
import drip.manager.ui.component.ChoiceRow
import drip.manager.ui.component.SearchField
import drip.manager.ui.component.SheetAction
import drip.manager.ui.component.SheetHeading
import drip.manager.ui.component.StatusPill
import drip.manager.ui.component.ToggleRow
import java.util.LinkedHashMap
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 排序方式 */
private enum class ScopeSort { Relevance, Name, Package, Install, Update }

/** 作用域应用行：本地 PackageManager 标签 + 用户 + 状态标记 */
private data class ScopeApp(
    val packageName: String,
    val userId: Int,
    val label: String,
    val system: Boolean,
    val isModule: Boolean,
    val installTime: Long,
    val updateTime: Long,
)

private fun key(pkg: String, userId: Int) = "$pkg:$userId"
private fun parseKey(k: String): Pair<String, Int> {
    val i = k.lastIndexOf(':')
    return k.substring(0, i) to k.substring(i + 1).toInt()
}

/** Scope 子页：作用域勾选（draft 制）+ 三抽屉 + ApplyBar；未连接 daemon 时空态。 */
@Composable
fun ScopeScreen(
    module: drip.manager.ModuleInfo,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val pm = context.packageManager
    var moduleEnabled by rememberSaveable(module.packageName) { mutableStateOf(module.enabled) }
    val isStaticScope = module.staticScope == true
    // M8：scope 包含 "system"（静态作用域声明或用户手动添加）时自动追加系统应用列表
    var includeSystemApps by rememberSaveable { mutableStateOf(false) }
    var scopeLoaded by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }

    // 过滤状态。默认隐藏系统应用；但「系统应用」判断不能只用 FLAG_SYSTEM
    //（ColorOS OEM 预装可卸载 app 也带 FLAG_SYSTEM），见 system 字段判定。
    var showSystem by rememberSaveable { mutableStateOf(false) }
    var showModules by rememberSaveable { mutableStateOf(false) }
    var recommendedOnly by rememberSaveable { mutableStateOf(false) }
    var selectedUserIdx by rememberSaveable { mutableStateOf(0) }
    // 排序状态
    var sortOrder by rememberSaveable { mutableStateOf(ScopeSort.Relevance) }
    var reverseSort by rememberSaveable { mutableStateOf(false) }
    var includeNewApps by rememberSaveable { mutableStateOf(false) }
    var selectOpen by remember { mutableStateOf(false) }
    var filterOpen by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }

    // 数据
    var loaded by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<ScopeApp>>(emptyList()) }
    // 本地 PackageManager 拿到的 ApplicationInfo 映射，行内按需取图标（懒加载，避免一次性渲染 438 个 Bitmap）
    var appInfoMap by remember { mutableStateOf<Map<String, ApplicationInfo>>(emptyMap()) }
    var initialKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var checkedKeys by rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }
    // 排序触发 tick：勾选/取消不触发重排，仅进入页面（apps 变化）与点击「应用」时 +1，
    // 触发 filtered 用最新 checkedKeys 重算三分区。
    var sortTick by rememberSaveable { mutableIntStateOf(0) }
    var recommended by remember { mutableStateOf<Set<String>>(emptySet()) }
    // 图标内存缓存：跨 item 复用，避免滚动回来重复解码卡顿；accessOrder=true 做 LRU，上限 256。
    val iconCache = remember {
        object : LinkedHashMap<String, Bitmap>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>) = size > 256
        }
    }

    LaunchedEffect(Unit) {
        ManagerServiceClient.connect()
        val enabledModules = ManagerServiceClient.getEnabledModules().toSet()
        val scope = ManagerServiceClient.getModuleScope(module.packageName)
        val initial = scope.map { key(it.appPackageName, it.userId) }.toSet()
        val includeNew = ManagerServiceClient.getIncludeNewApps(module.packageName)
        val daemonRecommended = ManagerServiceClient.getModuleRecommendedScope(module.packageName).toSet()
        // 应用列表/图标/标签/推荐作用域移出主线程避免卡顿。
        // 列表来源（多用户完整）：daemon（root）getInstalledPackagesFromAllUsers——本地 PM
        // 只能看到 user 0，user 10 的应用必须由 daemon 列出；label/icon 仍走本地 PM 快路径。
        // daemon 未连接（返回空）时回退本地 user 0 列表（历史行为，不回归）。
        val (map, list, rec) = withContext(Dispatchers.Default) {
            val pm = context.packageManager
            val localPkgs = try {
                pm.getInstalledPackages(0)
            } catch (_: Throwable) {
                emptyList()
            }
            val appMap = localPkgs.mapNotNull { it.applicationInfo }.associateBy { it.packageName }
            fun toScopeApp(pi: PackageInfo): ScopeApp {
                val ai = pi.applicationInfo ?: throw NoSuchElementException()
                // uid = userId*100000 + appId（契约 §4.1）；UserHandle.getUserId 是隐藏 API 走除法
                val userId = ai.uid.coerceAtLeast(0) / 100000
                return ScopeApp(
                    packageName = pi.packageName,
                    userId = userId,
                    label = appMap[pi.packageName]?.loadLabel(pm)?.toString() ?: pi.packageName,
                    system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    isModule = pi.packageName in enabledModules,
                    installTime = pi.firstInstallTime,
                    updateTime = pi.lastUpdateTime,
                )
            }
            val allUserPkgs = ManagerServiceClient.getInstalledPackagesFromAllUsers(0)
            val appList = if (allUserPkgs.isNotEmpty()) {
                allUserPkgs.mapNotNull { pi -> try { toScopeApp(pi) } catch (_: Throwable) { null } }
            } else {
                localPkgs.mapNotNull { pi -> try { toScopeApp(pi) } catch (_: Throwable) { null } }
            }
            // 推荐作用域：本地读模块 meta-data（drip_scope / xposedscope），daemon 作 fallback
            val rec = readRecommendedScope(context, module.packageName)
                .ifEmpty { daemonRecommended }
            Triple(appMap, appList, rec)
        }
        appInfoMap = map
        apps = list
        recommended = rec
        includeNewApps = includeNew
        initialKeys = initial
        checkedKeys = initial
        // 2026-09-06：作用域含系统应用（scope="system" 字面量或具体系统包名）时自动开启「系统应用」显示，
        // 否则系统应用被 showSystem=false 过滤隐藏；静态作用域无过滤抽屉更不可见——drip-manager-ui-20260906.md
        // M18: 与 daemon 归一化判据对齐——系统框架标识认 'android'（UI/scope 表落库）与 'system'（模块 staticScope 声明）。
        val scopePkgs = initial.mapNotNull { parseKey(it).first }.toSet()
        includeSystemApps = scope.any { it.appPackageName == "android" || it.appPackageName == "system" }
        if (includeSystemApps || list.any { it.system && it.packageName in scopePkgs }) {
            showSystem = true
        }
        scopeLoaded = true
        loaded = true
    }

    // 静态作用域包含 system 时，自动追加设备上所有系统应用到列表
    LaunchedEffect(includeSystemApps, loaded) {
        if (!loaded || !includeSystemApps) return@LaunchedEffect
        val sysApps = withContext(Dispatchers.Default) {
            val localPkgs = try {
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
            } catch (_: Throwable) {
                emptyList()
            }
            val appMap = appInfoMap
            localPkgs.filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0 }
                .mapNotNull { ai ->
                    val pkg = ai.packageName
                    if (apps.any { it.packageName == pkg }) return@mapNotNull null
                    val userId = ai.uid.coerceAtLeast(0) / 100000
                    ScopeApp(
                        packageName = pkg,
                        userId = userId,
                        label = appMap[pkg]?.loadLabel(pm)?.toString() ?: pkg,
                        system = true,
                        isModule = false,
                        installTime = 0L,
                        updateTime = 0L,
                    )
                }
        }
        if (sysApps.isNotEmpty()) {
            val existing = apps
            apps = existing + sysApps
            // 系统应用默认 checked = true（它们在静态作用域内）
            checkedKeys = checkedKeys + sysApps.map { key(it.packageName, it.userId) }
            initialKeys = checkedKeys
        }
    }

    // 过滤 + 排序
    // 注意：checkedKeys 有意不列进 remember key（勾选/取消不触发重排，列表保持当前位置）。
    // 块内三分区仍读 checkedKeys 最新值，由 sortTick（点「应用」）或 apps 变化（进页面加载）
    // 触发重算——勿把 checkedKeys 加回 key，否则勾选又变回实时重排。
    val filtered = remember(
        query, showSystem, showModules, recommendedOnly, selectedUserIdx,
        sortOrder, reverseSort, apps, recommended, sortTick,
    ) {
        var list = apps.filter { app ->
            (query.isBlank() || app.label.contains(query, ignoreCase = true) ||
                    app.packageName.contains(query, ignoreCase = true)) &&
                    (!recommendedOnly || app.packageName in recommended) &&
                    (recommendedOnly || (
                            (showSystem || !app.system) &&
                                    (showModules || !app.isModule)
                            )) &&
                    (selectedUserIdx == 0 ||
                            app.userId == if (selectedUserIdx == 1) 0 else 10) &&
                    // 静态作用域：只展示作用域内成员（只读；系统成员由上方 #2 自动 showSystem 可见）
                    (!isStaticScope || key(app.packageName, app.userId) in checkedKeys)
        }
        // 统一三分区：已选择 > 推荐 > 其他，各分区内部按当前 sortOrder 排（partition/sortedBy 均稳定）。
        // 相关度=原列表相对顺序，其余按 label/packageName/installTime/updateTime；
        // reverseSort 时三分区各自内部反转，分区顺序不变（已选择仍置顶）。
        fun sortGroup(src: List<ScopeApp>): List<ScopeApp> = when (sortOrder) {
            ScopeSort.Relevance -> src
            ScopeSort.Name -> src.sortedBy { it.label }
            ScopeSort.Package -> src.sortedBy { it.packageName }
            ScopeSort.Install -> src.sortedBy { it.installTime }
            ScopeSort.Update -> src.sortedBy { it.updateTime }
        }
        fun sortSuffix(src: List<ScopeApp>): List<ScopeApp> =
            if (reverseSort) sortGroup(src).reversed() else sortGroup(src)
        val (checked, rest) = list.partition { key(it.packageName, it.userId) in checkedKeys }
        val (rec, others) = rest.partition { it.packageName in recommended }
        list = sortSuffix(checked) + sortSuffix(rec) + sortSuffix(others)
        list
    }

    val added = (checkedKeys - initialKeys).size
    val removed = (initialKeys - checkedKeys).size
    val dirty = added > 0 || removed > 0

    // 2026-09-06：外包 Box 承载右下角「启动该模块」FAB overlay（Column 内无法绝对悬浮）。
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        // 单行紧凑顶栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = module.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = module.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(checked = moduleEnabled, onCheckedChange = {
                moduleEnabled = it
                ManagerServiceClient.setModuleEnabled(module.packageName, it)
            })
            Spacer(Modifier.width(4.dp))
            // 重启所有作用域进程：forceStopPackage 遍历勾选
            TextButton(onClick = {
                val targets = checkedKeys.toList()
                if (targets.isEmpty()) {
                    Toast.makeText(context, "作用域为空，无需重启", Toast.LENGTH_SHORT).show()
                } else {
                    val connected = ManagerServiceClient.connected
                    if (connected) {
                        targets.forEach { k ->
                            val (pkg, uid) = parseKey(k)
                            ManagerServiceClient.forceStopPackage(pkg, uid)
                        }
                        Toast.makeText(
                            context,
                            "已重启 ${targets.size} 个作用域进程",
                            Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        Toast.makeText(
                            context,
                            "未连接 daemon，未执行",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }) {
                Icon(Icons.Outlined.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("重启")
            }
        }

        SearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = "搜索应用",
            modifier = Modifier.padding(horizontal = 20.dp),
            trailingContent = {
                if (!isStaticScope) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { selectOpen = true }) {
                            Icon(Icons.Outlined.Checklist, contentDescription = "选择")
                        }
                        val filtering = showSystem || showModules || recommendedOnly ||
                                selectedUserIdx != 0
                        IconButton(onClick = { filterOpen = true }) {
                            BadgedBox(badge = { if (filtering) Badge(Modifier.size(6.dp)) }) {
                                Icon(Icons.Outlined.FilterList, contentDescription = "过滤")
                            }
                        }
                        IconButton(onClick = { sortOpen = true }) {
                            Icon(
                                Icons.AutoMirrored.Outlined.Sort,
                                contentDescription = "排序",
                                tint = if (sortOrder != ScopeSort.Relevance || reverseSort) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            },
        )

        if (isStaticScope) {
            Text(
                text = "静态作用域：模块声明的固定作用域，不可修改",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }

        when {
            !loaded -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("加载中…", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            apps.isEmpty() -> Box(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text("未找到应用", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            filtered.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("无匹配的应用", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp),
            ) {
                items(filtered, key = { key(it.packageName, it.userId) }) { app ->
                    val appKey = key(app.packageName, app.userId)
                    // 图标异步解码：主线程只放占位图，Bitmap 解码在 IO 线程；跨 item 复用内存缓存，
                    // 避免滚动回来重复解码卡顿（缓存读写都在主线程，withContext 返回后写回，无并发写）。
                    val appInfo = appInfoMap[app.packageName]
                    var icon by remember(app.packageName) { mutableStateOf(iconCache[app.packageName]) }
                    LaunchedEffect(appInfo) {
                        val cached = iconCache[app.packageName]
                        if (cached != null) {
                            icon = cached
                        } else {
                            val bmp = withContext(Dispatchers.IO) { appInfo?.loadIcon(pm)?.toBitmap() }
                            icon = bmp
                            if (bmp != null) iconCache[app.packageName] = bmp
                        }
                    }
                    AppScopeRow(
                        name = app.label,
                        packageName = app.packageName,
                        userId = app.userId,
                        checked = appKey in checkedKeys,
                        recommended = app.packageName in recommended,
                        icon = icon,
                        readOnly = isStaticScope,
                        onToggle = {
                            checkedKeys = if (appKey in checkedKeys) checkedKeys - appKey
                            else checkedKeys + appKey
                        },
                    )
                }
            }
        }

        // 底部应用栏：作为根 Column 末子节点沉底（LazyColumn weight(1f) 之后），不与列表重叠。
        if (dirty && !isStaticScope) {
            ApplyBar(
                added = added,
                removed = removed,
                onDiscard = { checkedKeys = initialKeys },
                onApply = {
                    val entries = checkedKeys.map { k ->
                        val (pkg, uid) = parseKey(k)
                        val entry = ScopeEntry()
                        entry.appPackageName = pkg
                        entry.userId = uid
                        entry
                    }
                    val ok = ManagerServiceClient.setModuleScope(module.packageName, entries)
                    ManagerServiceClient.setIncludeNewApps(module.packageName, includeNewApps)
                    if (ok || entries.isEmpty()) {
                        initialKeys = checkedKeys
                        sortTick++
                        Toast.makeText(context, "作用域已更新", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "更新失败（未连接 daemon）", Toast.LENGTH_SHORT).show()
                    }
                },
            )
        }
    }

        // 右下角「启动该模块」FAB（圆角方块 + 播放三角）：打开模块本体 App；
        // dirty（改动待应用）时隐藏，避免遮挡底部 ApplyBar —— 2026-09-06。
        if (!dirty) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .clickable {
                        // 2026-09-06：launcher 隐藏入口的模块 → openModuleApp 兜底直达主 activity
                        if (!openModuleApp(context, module.packageName)) {
                            Toast.makeText(context, "未找到可启动的应用入口", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = "启动模块",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
    }

    // 选择抽屉（静态作用域时隐藏）
    if (selectOpen && !isStaticScope) {
        ModalBottomSheet(onDismissRequest = { selectOpen = false }) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                SheetHeading("选择", Icons.Outlined.Checklist)
                if (recommended.isNotEmpty()) {
                    SheetAction(
                        title = "采用推荐",
                        subtitle = "清空其余勾选，仅保留模块推荐的 ${recommended.size} 个应用",
                        icon = Icons.Outlined.AutoAwesome,
                        onClick = {
                            checkedKeys = apps.filter { it.packageName in recommended }
                                .map { key(it.packageName, it.userId) }.toSet()
                            selectOpen = false
                        },
                    )
                }
                SheetAction(
                    title = "全选可见",
                    icon = Icons.Outlined.DoneAll,
                    onClick = {
                        checkedKeys = checkedKeys + filtered.map { key(it.packageName, it.userId) }
                        selectOpen = false
                    },
                )
                SheetAction(
                    title = "清除可见",
                    icon = Icons.Outlined.RemoveDone,
                    onClick = {
                        checkedKeys = checkedKeys - filtered.map { key(it.packageName, it.userId) }.toSet()
                        selectOpen = false
                    },
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                ToggleRow(
                    title = "包含新应用",
                    subtitle = "安装的新应用自动纳入该模块作用域",
                    icon = Icons.AutoMirrored.Outlined.PlaylistAdd,
                    checked = includeNewApps,
                    onCheckedChange = {
                        includeNewApps = it
                        ManagerServiceClient.setIncludeNewApps(module.packageName, it)
                        Toast.makeText(
                            context,
                            "新应用自动纳入：${if (it) "已开" else "已关"}",
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                )
            }
        }
    }

    // 过滤抽屉（静态作用域时隐藏）
    if (filterOpen && !isStaticScope) {
        ModalBottomSheet(onDismissRequest = { filterOpen = false }) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                SheetHeading("过滤", Icons.Outlined.FilterList)
                if (recommended.isNotEmpty()) {
                    ChoiceRow {
                        FilterChip(
                            selected = recommendedOnly,
                            onClick = { recommendedOnly = !recommendedOnly },
                            label = { Text("仅推荐") },
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                }
                ChoiceRow {
                    FilterChip(
                        selected = showSystem,
                        enabled = !recommendedOnly,
                        onClick = { showSystem = !showSystem },
                        label = { Text("系统应用") },
                    )
                    FilterChip(
                        selected = showModules,
                        enabled = !recommendedOnly,
                        onClick = { showModules = !showModules },
                        label = { Text("模块") },
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(
                    text = "按用户",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
                ChoiceRow {
                    FilterChip(
                        selected = selectedUserIdx == 0,
                        onClick = { selectedUserIdx = 0 },
                        label = { Text("全部") },
                    )
                    FilterChip(
                        selected = selectedUserIdx == 1,
                        onClick = { selectedUserIdx = 1 },
                        label = { Text("用户 0") },
                    )
                    FilterChip(
                        selected = selectedUserIdx == 2,
                        onClick = { selectedUserIdx = 2 },
                        label = { Text("用户 10") },
                    )
                }
            }
        }
    }

    // 排序抽屉（静态作用域时隐藏）
    if (sortOpen && !isStaticScope) {
        ModalBottomSheet(onDismissRequest = { sortOpen = false }) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                SheetHeading("排序", Icons.AutoMirrored.Outlined.Sort)
                ChoiceRow {
                    ScopeSort.entries.forEach { option ->
                        FilterChip(
                            selected = sortOrder == option,
                            onClick = { sortOrder = option },
                            label = { Text(option.label()) },
                        )
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                ToggleRow(
                    title = "反向排序",
                    icon = Icons.Outlined.SwapVert,
                    checked = reverseSort,
                    onCheckedChange = { reverseSort = it },
                )
            }
        }
    }
}

private fun ScopeSort.label(): String =
    when (this) {
        ScopeSort.Relevance -> "相关度"
        ScopeSort.Name -> "名称"
        ScopeSort.Package -> "包名"
        ScopeSort.Install -> "安装时间"
        ScopeSort.Update -> "更新时间"
    }

/**
 * 本地读模块推荐作用域 meta-data（drip_scope / xposedscope）+ scope.list，与 daemon
 * （ModuleApkParser.readRecommendedScope）行为一致：
 *  1. `drip_scope`——字符串（逗号/分号）或字符串数组资源；
 *  2. `xposedscope`——字符串数组资源或分号分隔字符串；
 *  3. `META-INF/xposed/scope.list`——每行一个包名。
 * 后台线程调用（withContext(Dispatchers.Default)）。
 */
private fun readRecommendedScope(context: Context, modulePackage: String): Set<String> = try {
    val pm = context.packageManager
    val info = pm.getPackageInfo(modulePackage, PackageManager.GET_META_DATA)
    val ai = info.applicationInfo ?: return emptySet()
    val meta = ai.metaData ?: return emptySet()
    val dripScope = readScopeFromMeta(meta, "drip_scope", pm, ai)
    if (dripScope.isNotEmpty()) return dripScope.toSet()
    val xposedScope = readScopeFromMeta(meta, "xposedscope", pm, ai)
    if (xposedScope.isNotEmpty()) return xposedScope.toSet()
    readScopeListFromApk(context, modulePackage)
} catch (_: Throwable) {
    emptySet()
}

/** meta-data 作用域声明解析：字符串（逗号/分号分隔）或字符串数组资源（@array/...）。 */
@Suppress("DEPRECATION") // Bundle.get 在 Android 16 标记 deprecated，仍可用
private fun readScopeFromMeta(
    meta: Bundle,
    key: String,
    pm: PackageManager,
    ai: ApplicationInfo,
): List<String> {
    val value = meta.get(key) ?: return emptyList()
    return when (value) {
        is String -> value.split(';', ',').map { it.trim() }.filter { it.isNotEmpty() }
        is Int -> {
            if (value == 0) emptyList()
            else try {
                // compileSdk 37 下 getStringArray 返回非空 Array<String>；资源缺失抛异常由 try 兜底。
                pm.getResourcesForApplication(ai).getStringArray(value).toList()
            } catch (_: Throwable) {
                emptyList()
            }
        }
        else -> emptyList()
    }
}

/** 读模块 APK 的 `META-INF/xposed/scope.list`（每行一个包名，# 注释忽略）。 */
private fun readScopeListFromApk(context: Context, modulePackage: String): Set<String> = try {
    val src = context.packageManager.getApplicationInfo(modulePackage, 0).sourceDir
        ?: return emptySet()
    ZipFile(src).use { zip ->
        val entry = zip.getEntry("META-INF/xposed/scope.list") ?: return emptySet()
        zip.getInputStream(entry).bufferedReader().useLines { lines ->
            lines.map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toSet()
        }
    }
} catch (_: Throwable) {
    emptySet()
}

@Composable
private fun AppScopeRow(
    name: String,
    packageName: String,
    userId: Int,
    checked: Boolean,
    recommended: Boolean,
    icon: Bitmap?,
    readOnly: Boolean = false,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (readOnly) Modifier else Modifier.clickable(onClick = onToggle))
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Image(
                bitmap = icon.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(36.dp),
            )
        } else {
            AppIconPlaceholder(name, size = 36.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "$packageName · user $userId",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 推荐应用横幅：放在点选开关（checkbox）左侧
        if (recommended) {
            Spacer(Modifier.width(8.dp))
            StatusPill(
                text = "推荐应用",
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Checkbox(
            checked = checked,
            onCheckedChange = if (readOnly) null else { _ -> onToggle() },
        )
    }
}

/** 底部应用栏：+N −M + 放弃/应用 */
@Composable
private fun ApplyBar(
    added: Int,
    removed: Int,
    onDiscard: () -> Unit,
    onApply: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "+$added −$removed",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "应用后写入该模块作用域",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onDiscard) { Text("放弃") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onApply) { Text("应用") }
        }
    }
}
