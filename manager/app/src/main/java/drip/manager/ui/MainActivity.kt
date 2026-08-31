// MainActivity：Scaffold + 浮动底栏 + enum/AnimatedContent 切换 + Scope 子页简单状态路由。
package drip.manager.ui

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import drip.manager.ModuleInfo
import drip.manager.data.ManagerServiceClient
import drip.manager.data.ModuleFailureWatcher
import drip.manager.data.InjectionFailureWatcher
import drip.manager.data.ModuleInstallWatcher
import drip.manager.data.SettingsState
import drip.manager.data.EXTRA_LOG_FILTER_TAG
import drip.manager.data.hasNotificationPermission
import drip.manager.data.showStatusNotification
import drip.manager.ui.page.HomeScreen
import drip.manager.ui.page.LogsScreen
import drip.manager.ui.page.ModulesScreen
import drip.manager.ui.page.ScopeScreen
import drip.manager.ui.page.SettingsScreen
import drip.manager.ui.theme.DripManagerTheme

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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 提取 Intent extras：通知点击跳日志页
        val openLogs = intent?.getBooleanExtra("drip.open_logs", false) == true
        val logFilterTag = intent?.getStringExtra(EXTRA_LOG_FILTER_TAG)
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
) {
    var selectedTab by rememberSaveable { mutableStateOf(initialTab ?: ManagerTab.Home) }
    var initialLogTagApplied by rememberSaveable { mutableStateOf(false) }
    // #9 旋转不丢：存模块包名（String 可 saveable），重建时经 client 找回模块
    var scopeModulePackage by rememberSaveable { mutableStateOf<String?>(null) }
    var lastModule by remember { mutableStateOf<ModuleInfo?>(null) }
    val scopeModule = remember(scopeModulePackage) {
        scopeModulePackage?.let { pkg ->
            ManagerServiceClient.getModuleInfo(pkg) ?: lastModule?.takeIf { it.packageName == pkg }
        }
    }
    val snackbarHostState = remember { SnackbarHostState() }

    // 初始化 client（寄生注入前连接失败 → 各页显示未连接空态，调用留痕）
    val appContext = LocalContext.current.applicationContext
    // Android 13+ 请求 POST_NOTIFICATIONS 的 launcher：授权后若开关仍开则补发常驻通知。
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && ManagerServiceClient.isStatusNotificationEnabled()) {
            showStatusNotification(appContext)
        }
    }

    LaunchedEffect(Unit) {
        ManagerServiceClient.init(appContext)
        ManagerServiceClient.connect()
        // 进程级常驻注册应用安装/更新监听（幂等）。不再随 Activity 销毁注销——
        // 寄生模式下 Activity 退出后进程被 daemon 保活，广播检测保持在线；独立安装进程
        // 存活期间同样保持，进程死亡后 receiver 由系统自动回收。
        ModuleInstallWatcher.ensureRegistered(appContext)
        // 模块加载失败增量检测（寄生/独立均适用）。
        ModuleFailureWatcher.startWatching(appContext)
        // M5：注入失败检测（relay_fail / bridge_fail 标记 → 通知 + 自动 dumpLogs）。
        InjectionFailureWatcher.startWatching(appContext)
        // M3e：daemon 开关为开时投递常驻状态通知（无权限则请求，授权后补发）。
        if (ManagerServiceClient.isStatusNotificationEnabled()) {
            if (hasNotificationPermission(appContext)) {
                showStatusNotification(appContext)
            } else {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    BackHandler(enabled = scopeModulePackage != null) { scopeModulePackage = null }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                        ManagerTab.Home -> HomeScreen()
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
