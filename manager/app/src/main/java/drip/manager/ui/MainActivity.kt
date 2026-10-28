// MainActivity：Scaffold + 浮动底栏 + enum/AnimatedContent 切换 + Scope 子页简单状态路由。
package drip.manager.ui

import android.Manifest
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import drip.manager.ModuleInfo
import drip.manager.data.KernelModeState
import drip.manager.data.ManagerServiceClient
import drip.manager.data.ModuleFailureWatcher
import drip.manager.data.InjectionFailureWatcher
import drip.manager.data.ModuleInstallWatcher
import drip.manager.data.SettingsState
import drip.manager.data.EXTRA_LOG_FILTER_TAG
import drip.manager.data.StatusNotificationController
import drip.manager.data.hasNotificationPermission
import drip.manager.ui.page.HomeScreen
import drip.manager.ui.page.KernelVerifyScreen
import drip.manager.ui.page.LogsScreen
import drip.manager.ui.page.ModulesScreen
import drip.manager.ui.page.ScopeScreen
import drip.manager.ui.page.SettingsScreen
import drip.manager.ui.theme.DripManagerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 底部 4 个 tab：Outlined 常态 / Filled 选中成对（spec 要点 10）。 */
enum class ManagerTab(
    val label: String,
    val iconOutlined: ImageVector,
    val iconFilled: ImageVector,
) {
    Home("首页", Icons.Outlined.Home, Icons.Filled.Home),
    Modules("模块", Icons.Outlined.Extension, Icons.Filled.Extension),
    Logs("日志", Icons.AutoMirrored.Outlined.ListAlt, Icons.AutoMirrored.Filled.ListAlt),
    Settings("设置", Icons.Outlined.Settings, Icons.Filled.Settings),
}

/**
 * M40：通知点击 → 直达**该模块的设置页**（ScopeScreen）的 extra，值 = 模块包名。
 * 三处同值：本文件、`ModuleInstallWatcher`、daemon `ModuleUpdateNotifier`
 * （framework 侧不用认它——重定向只换 component/ActivityInfo，extras 原样透传）。
 */
const val EXTRA_OPEN_MODULE = "drip.open_module"

/** 首启验证弹窗位（设计卡 §10-U3）：用户选择「以后不再自动弹出」后永久不再自动弹（requirements.md:35,:68）。 */
private const val PREFS_KERNEL_VERIFY = "kernel_verify_prompt"
private const val KEY_KERNEL_VERIFY_PROMPT_DONE = "kernel_verify_prompt_done"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 提取 Intent extras：通知点击跳日志页
        val openLogs = intent?.getBooleanExtra("drip.open_logs", false) == true
        val logFilterTag = intent?.getStringExtra(EXTRA_LOG_FILTER_TAG)
        // 通知点击跳「模块设置页」（模块更新通知用）
        val openModule = intent?.getStringExtra(EXTRA_OPEN_MODULE)
        setContent {
            val appContext = applicationContext
            var settings by remember { mutableStateOf(SettingsState.load(appContext)) }
            DripManagerTheme(
                dynamicColor = settings.dynamicColor,
                amoledBlack = settings.amoledBlack,
            ) {
                ManagerApp(
                    settings = settings,
                    onSettingsChange = {
                        settings = it
                        it.save(appContext)
                    },
                    initialTab = if (openLogs) ManagerTab.Logs else null,
                    initialLogTag = if (openLogs) logFilterTag else null,
                    initialModulePackage = openModule,
                )
            }
        }
    }
}

@Composable
private fun ManagerApp(
    settings: SettingsState,
    onSettingsChange: (SettingsState) -> Unit,
    initialTab: ManagerTab? = null,
    initialLogTag: String? = null,
    initialModulePackage: String? = null,
) {
    var selectedTab by rememberSaveable { mutableStateOf(initialTab ?: ManagerTab.Home) }
    var initialLogTagApplied by rememberSaveable { mutableStateOf(false) }
    var initialModuleApplied by rememberSaveable { mutableStateOf(false) }
    // #9 旋转不丢：存模块包名（String 可 saveable），重建时经 client 找回模块
    var scopeModulePackage by rememberSaveable { mutableStateOf<String?>(null) }
    var lastModule by remember { mutableStateOf<ModuleInfo?>(null) }
    val scopeModule = remember(scopeModulePackage) {
        scopeModulePackage?.let { pkg ->
            ManagerServiceClient.getModuleInfo(pkg) ?: lastModule?.takeIf { it.packageName == pkg }
        }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // 内核模式全屏验证 overlay（首启弹出 / 主界面「内核模式」入口），进程内 overlay（U8 不做第二 Activity）
    var showVerify by remember { mutableStateOf(false) }
    var verifyFirstLaunch by remember { mutableStateOf(false) }
    // 安全模式全屏警示（设计卡 §7 方案 A：每次打开 manager 且 safe_mode 在都弹，无「不再提示」）
    var showSafeMode by remember { mutableStateOf(false) }

    // 初始化 client（寄生注入前连接失败 → 各页显示未连接空态，调用留痕）
    val appContext = LocalContext.current.applicationContext
    // Android 13+ 权限引导 launcher：系统权限弹窗必须由 Activity 发起，这是通知模块唯一
    // 需要 UI 的地方；授权结果转交控制器决策（事件 D），UI 不直接投递通知。
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            StatusNotificationController.onPermissionGranted(appContext)
        }
    }

    LaunchedEffect(Unit) {
        // 解耦（2026-09-06）：进程级常驻后台（watcher / 通知）已由 DripManagerApp.onCreate
        // + StatusNotificationController 在进程 bind 时完成（通知独立触发通道事件 B，
        // 见 doc/spec/drip-m8-fix-boot-notification.md），不再依赖本 Activity 是否进入。
        // 这里只做 UI 侧：初始化 client 引用 + 连接（注入后即真实数据）+ 权限引导。
        ManagerServiceClient.init(appContext)
        ManagerServiceClient.connect()
        // M40：通知直达模块设置页。**必须在 connect() 之后**——scopeModule 是
        // `remember(scopeModulePackage)` 算出来的（见下），若在未连接时就设，getModuleInfo
        // 返回 null 且不会重算 ⇒ 会静默落回列表页。同 initialLogTag 的「一次性 applied」语义。
        val targetModule = initialModulePackage
        if (!initialModuleApplied && !targetModule.isNullOrEmpty()) {
            initialModuleApplied = true
            // 返回时落在模块列表而非首页（用户是冲着"某个模块"来的）
            selectedTab = ManagerTab.Modules
            scopeModulePackage = targetModule
        }
        // 通知投递完全由控制器驱动；此处仅处理「开关开但缺权限」→ 引导系统授权，
        // 授权成功后控制器自动补投。
        if (ManagerServiceClient.isStatusNotificationEnabled()
            && !hasNotificationPermission(appContext)) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // 内核模式 / 安全模式状态快照（设计卡 §7 / §10-U3）
        KernelModeState.refresh()
        // 安全模式：打开 manager 且 safe_mode 在 → 每次都弹全屏（方案 A，无「不再提示」）
        if (KernelModeState.status.safeMode) {
            showSafeMode = true
            Log.i(
                "DripManager",
                "safe mode fullscreen shown on open (crashCount=${KernelModeState.status.crashCount})",
            )
        }
        // 首启验证：已通过加载验证（kmod.ok）视为完成，不再自动弹
        val prefs = appContext.getSharedPreferences(PREFS_KERNEL_VERIFY, Context.MODE_PRIVATE)
        val promptDone = prefs.getBoolean(KEY_KERNEL_VERIFY_PROMPT_DONE, false)
        val verified = KernelModeState.status.verified
        if (verified && !promptDone) {
            prefs.edit().putBoolean(KEY_KERNEL_VERIFY_PROMPT_DONE, true).apply()
        }
        if (!promptDone && !verified && !KernelModeState.status.safeMode) {
            verifyFirstLaunch = true
            showVerify = true
            Log.i("DripManager", "first-launch kernel verify prompt shown")
        }
    }

    BackHandler(enabled = scopeModulePackage != null) { scopeModulePackage = null }

    // 退出安全模式：只写待生效标记，重启后由 daemon 应用
    val exitSafeMode: () -> Unit = {
        scope.launch {
            withContext(Dispatchers.IO) { ManagerServiceClient.exitSafeMode() }
            KernelModeState.refresh()
            showSafeMode = false
            Toast.makeText(appContext, "已标记退出，重启后生效", Toast.LENGTH_SHORT).show()
            Log.i("DripManager", "safe mode exit requested (after reboot)")
        }
    }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (KernelModeState.status.safeMode) {
                SafeModeBanner(count = KernelModeState.status.crashCount, onExit = exitSafeMode)
            }
        },
        bottomBar = {
            if (scopeModulePackage == null) {
                FloatingBottomBar(selected = selectedTab, onSelect = { selectedTab = it })
            }
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            AnimatedContent(
                targetState = if (scopeModule != null) scopeModule else selectedTab,
                transitionSpec = {
                    // push/pop 横向滑动 + 淡入淡出：进 Scope 右滑入、退 Scope 右滑出。
                    // tab 切换按目标与当前 tab 的相对位置决定方向：
                    //   目标在右（index 增大）→ 新页从右滑入、旧页向左滑出；
                    //   目标在左（index 减小）→ 新页从左滑入、旧页向右滑出。
                    // 自定义 sizeTransform 禁用大小动画（tween 0ms + clip=false），
                    // 避免内容高度变化（Scope 全屏 vs tab 有底栏）触发默认裁切导致的"瞬移到上方再消失"。
                    val sizeTransform = SizeTransform(clip = false, sizeAnimationSpec = { _, _ -> tween(0) })
                    val enterFromRight = when {
                        // 进 Scope：新页从右侧压入
                        targetState is ModuleInfo -> true
                        // 退 Scope：新页从左侧回退
                        initialState is ModuleInfo -> false
                        // tab 间切换：目标 index > 当前 index → 从右滑入
                        targetState is ManagerTab && initialState is ManagerTab ->
                            (targetState as ManagerTab).ordinal > (initialState as ManagerTab).ordinal
                        else -> false
                    }
                    if (enterFromRight) {
                        ContentTransform(
                            slideInHorizontally { it } + fadeIn(),
                            slideOutHorizontally { -it } + fadeOut(),
                            0f,
                            sizeTransform,
                        )
                    } else {
                        ContentTransform(
                            slideInHorizontally { -it } + fadeIn(),
                            slideOutHorizontally { it } + fadeOut(),
                            0f,
                            sizeTransform,
                        )
                    }
                },
                label = "root",
            ) { target ->
                when (target) {
                    is ModuleInfo -> ScopeScreen(
                        module = target,
                        onBack = { scopeModulePackage = null },
                    )
                    is ManagerTab -> when (target) {
                        ManagerTab.Home -> HomeScreen(
                            onOpenVerify = {
                                verifyFirstLaunch = false
                                showVerify = true
                            },
                        )
                        ManagerTab.Modules -> ModulesScreen(
                            onModuleClick = {
                                lastModule = it
                                scopeModulePackage = it.packageName
                            },
                        )
                        ManagerTab.Logs -> LogsScreen(
                            settings,
                            onSettingsChange,
                            initialTag = if (!initialLogTagApplied) {
                                initialLogTagApplied = true
                                initialLogTag
                            } else null,
                        )
                        ManagerTab.Settings -> SettingsScreen(settings, onSettingsChange)
                    }
                    else -> Unit
                }
            }
        }
    }

        // 内核模式全屏验证 overlay（首启 / 主界面「内核模式」入口；设计卡 §10-U2/U3/U8）
        AnimatedVisibility(
            visible = showVerify,
            enter = fadeIn(tween(280)) + slideInVertically(tween(280)) { it },
            exit = fadeOut(tween(220)) + slideOutVertically(tween(220)) { it },
        ) {
            KernelVerifyScreen(
                onClose = {
                    showVerify = false
                    verifyFirstLaunch = false
                    scope.launch { KernelModeState.refresh() }
                },
                firstLaunch = verifyFirstLaunch,
                onDisableAutoPrompt = {
                    // 用户明确选择：以后不再自动弹验证界面
                    appContext.getSharedPreferences(PREFS_KERNEL_VERIFY, Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean(KEY_KERNEL_VERIFY_PROMPT_DONE, true)
                        .apply()
                    showVerify = false
                    verifyFirstLaunch = false
                    Toast.makeText(appContext, "以后不再自动弹出验证界面", Toast.LENGTH_SHORT).show()
                    Log.i("DripManager", "kernel verify auto prompt disabled by user")
                },
            )
        }

        // 安全模式全屏警示（每次打开都弹；点退出为标记，重启后生效）
        if (showSafeMode) {
            SafeModeWarning(
                count = KernelModeState.status.crashCount,
                onExit = exitSafeMode,
            )
        }
    }
}

/** 安全模式顶部横幅（设计卡 §7：横幅 + 退出按钮；退出需重启生效）。 */
@Composable
private fun SafeModeBanner(count: Int, onExit: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onError,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "安全模式",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onError,
                )
                Text(
                    "已检测到 $count 次系统崩溃 · 退出需重启生效",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onError.copy(alpha = 0.85f),
                )
            }
            TextButton(onClick = onExit) {
                Text("退出", color = MaterialTheme.colorScheme.onError)
            }
        }
    }
}

/** 安全模式全屏警示（方案 A：每次打开都弹，无「不再提示」；退出需重启生效）。 */
@Composable
private fun SafeModeWarning(count: Int, onExit: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.error) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Outlined.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onError,
                modifier = Modifier.height(48.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "安全模式",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onError,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "检测到 $count 次系统崩溃",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onError,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "已禁止加载模块，仅保留核心组件与界面。退出将在重启后生效。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onError.copy(alpha = 0.85f),
            )
            Spacer(Modifier.height(24.dp))
            TextButton(onClick = onExit) {
                Text("退出", color = MaterialTheme.colorScheme.onError)
            }
        }
    }
}

/** Magisk 式浮动底栏：28dp 圆角 + 6dp 阴影 + surfaceContainer + 64dp 高。 */
@Composable
private fun FloatingBottomBar(
    selected: ManagerTab,
    onSelect: (ManagerTab) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().height(64.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            shadowElevation = 6.dp,
        ) {
            Row(Modifier.fillMaxSize()) {
                ManagerTab.entries.forEach { tab ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable { onSelect(tab) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = if (tab == selected) tab.iconFilled else tab.iconOutlined,
                            contentDescription = tab.label,
                            tint = if (tab == selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = tab.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (tab == selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (tab == selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
    }
}
