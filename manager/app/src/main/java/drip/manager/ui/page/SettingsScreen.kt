// Settings 页：框架/外观/关于三组设置。框架开关走 ManagerServiceClient，主题开关本地；快捷方式真实实现。
package drip.manager.ui.page

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import drip.manager.IFrameworkDumpReceiver
import drip.manager.R
import drip.manager.data.ManagerServiceClient
import drip.manager.data.SettingsState
import drip.manager.data.StatusNotificationController
import drip.manager.data.buildManagerOpenIntent
import drip.manager.data.hasNotificationPermission
import drip.manager.ui.component.GroupCard
import drip.manager.ui.component.SettingRow

/** Xposed 模块仓库地址（LSPosed 官方仓库；2026-09-06 由 GitHub 项目页改为仓库）。 */
private const val XP_MODULE_REPO_URL = "https://modules.lsposed.org/"

/** Settings 页：框架开关走 daemon（未连接留痕），主题开关本地，快捷方式真实创建。 */
@Composable
fun SettingsScreen(
    settings: SettingsState,
    onSettingsChange: (SettingsState) -> Unit,
) {
    val context = LocalContext.current
    // Android 13+ 权限引导 launcher：弹窗必须由 Activity 发起；授权结果转交控制器
    //（事件 D），投递与否由控制器统一决策，UI 不直投（drip-m8-fix-boot-notification.md）。
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            StatusNotificationController.onPermissionGranted(context)
        }
    }

    // 从 daemon 读真实开关值（未连接则保持本地默认）
    LaunchedEffect(Unit) {
        ManagerServiceClient.connect()
        onSettingsChange(
            settings.copy(
                statusNotification = ManagerServiceClient.isStatusNotificationEnabled(),
                verboseLog = ManagerServiceClient.isVerboseLogEnabled(),
                randomLogTag = ManagerServiceClient.isRandomLogTagEnabled(),
                forceAppIcon = ManagerServiceClient.isForcedLauncherIcons(),
                loggingSilenced = ManagerServiceClient.isLoggingSilenced(),
            ),
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 96.dp),
    ) {
        CenterAlignedTopAppBar(title = { Text("Settings") })

        // 框架设置
        Text(
            text = "框架设置",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 8.dp),
        )
        GroupCard(Modifier.padding(horizontal = 16.dp)) {
            SettingRow(
                title = "状态栏通知",
                subtitle = "通知栏常驻显示框架状态",
                trailing = {
                    Switch(
                        checked = settings.statusNotification,
                        onCheckedChange = { enabled ->
                            ManagerServiceClient.setStatusNotificationEnabled(enabled)
                            onSettingsChange(settings.copy(statusNotification = enabled))
                            // 开关只改状态；投递/撤销由控制器统一决策（事件 C，UI 不直投）。
                            StatusNotificationController.onToggleChanged(enabled, context)
                            // 打开但缺权限 → 引导系统授权（Android 13+ 硬约束）；授权后事件 D 补投。
                            if (enabled && !hasNotificationPermission(context)) {
                                notificationPermissionLauncher.launch(
                                    Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                    )
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingRow(
                title = "详细日志 (verbose)",
                subtitle = "记录 debug 级详细日志",
                trailing = {
                    Switch(
                        checked = settings.verboseLog,
                        onCheckedChange = {
                            ManagerServiceClient.setVerboseLogEnabled(it)
                            onSettingsChange(settings.copy(verboseLog = it))
                        },
                    )
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingRow(
                title = "随机日志标签",
                subtitle = "随机化 logcat tag 防按 Drip 过滤；切换后重启应用生效",
                trailing = {
                    Switch(
                        checked = settings.randomLogTag,
                        onCheckedChange = { enabled ->
                            ManagerServiceClient.setRandomLogTagEnabled(enabled)
                            onSettingsChange(settings.copy(randomLogTag = enabled))
                            Toast.makeText(
                                context,
                                "重启目标应用 / 重启 daemon 后生效",
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                    )
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingRow(
                title = "完全静默",
                subtitle = "不写日志文件也不打 logcat",
                trailing = {
                    Switch(
                        checked = settings.loggingSilenced,
                        onCheckedChange = {
                            ManagerServiceClient.setLoggingSilenced(it)
                            onSettingsChange(settings.copy(loggingSilenced = it))
                        },
                    )
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingRow(
                title = "强制应用图标",
                trailing = {
                    Switch(
                        checked = settings.forceAppIcon,
                        onCheckedChange = {
                            ManagerServiceClient.setForcedLauncherIcons(it)
                            onSettingsChange(settings.copy(forceAppIcon = it))
                        },
                    )
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingRow(
                title = "创建桌面快捷方式",
                onClick = { createShortcut(context) },
                trailing = {
                    Icon(
                        Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingRow(
                title = "转储 hook 信息",
                subtitle = "输出全部 hook 信息",
                onClick = { dumpHookInfo(context) },
                trailing = {
                    Icon(
                        Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingRow(
                title = "转储日志",
                subtitle = "转储框架日志到 zip 文件",
                onClick = { dumpLogs(context) },
                trailing = {
                    Icon(
                        Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingRow(
                title = "模块仓库",
                subtitle = "Xposed 模块仓库",
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(XP_MODULE_REPO_URL))
                    context.startActivity(intent)
                },
                trailing = {
                    Icon(
                        Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }

        // 外观
        Text(
            text = "外观",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp),
        )
        GroupCard(Modifier.padding(horizontal = 16.dp)) {
            SettingRow(
                title = "主题",
                subtitle = "亮/暗跟随系统",
                trailing = {
                    Text(
                        text = "系统",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingRow(
                title = "动态取色",
                subtitle = "Android 12+ 跟随壁纸",
                trailing = {
                    Switch(
                        checked = settings.dynamicColor,
                        onCheckedChange = { onSettingsChange(settings.copy(dynamicColor = it)) },
                    )
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingRow(
                title = "AMOLED 纯黑",
                subtitle = "暗色下 surface 全置纯黑",
                trailing = {
                    Switch(
                        checked = settings.amoledBlack,
                        onCheckedChange = { onSettingsChange(settings.copy(amoledBlack = it)) },
                    )
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingRow(
                title = "日志显示样式",
                subtitle = if (settings.logDisplayStyle == 0) "终端" else "卡片",
                trailing = {
                    Icon(
                        Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = {
                    val next = if (settings.logDisplayStyle == 0) 1 else 0
                    onSettingsChange(settings.copy(logDisplayStyle = next))
                },
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = if (ManagerServiceClient.connected) "Drip Manager · 已连接 daemon"
                else "Drip Manager · 未连接 daemon",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 创建桌面快捷方式（本地 ShortcutManager 真实实现）。寄生模式用宿主 Activity + LAUNCH_MANAGER，
 * 由宿主 framework 重定向到 manager UI；独立模式直接指向 MainActivity。 */
private fun createShortcut(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
        Toast.makeText(context, "当前系统不支持动态快捷方式", Toast.LENGTH_SHORT).show()
        return
    }
    val shortcutIntent = buildManagerOpenIntent(context).apply {
        action = Intent.ACTION_MAIN
    }
    val shortcut = ShortcutInfoCompat.Builder(context, "drip_manager")
        .setShortLabel("Drip Manager")
        .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
        .setIntent(shortcutIntent)
        .build()
    val accepted = ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
    Toast.makeText(
        context,
        if (accepted) "已创建桌面快捷方式" else "快捷方式请求被拒绝",
        Toast.LENGTH_SHORT,
    ).show()
}

/** 转储 hook 信息：走 daemon dumpHookInfo（未连接降级留痕 + Toast）。 */
private fun dumpHookInfo(context: Context) {
    val receiver = object : IFrameworkDumpReceiver.Stub() {
        override fun onDumpResult(success: Boolean, path: String) {
            // daemon 回调（binder 线程）：转储成功/失败都会回调 onDumpResult
        }
    }
    val ok = ManagerServiceClient.dumpHookInfo(receiver)
    Toast.makeText(
        context,
        if (ok) "已触发转储，结果写入 /data/adb/drip/log"
        else "转储不可用（未连接 daemon，已留痕）",
        Toast.LENGTH_SHORT,
    ).show()
}

/** 转储日志：走 daemon dumpLogs（未连接降级留痕 + Toast）。 */
private fun dumpLogs(context: Context) {
    Toast.makeText(context, "正在转储…", Toast.LENGTH_SHORT).show()
    val path = ManagerServiceClient.dumpLogs()
    Toast.makeText(
        context,
        if (path != null) "日志已转储到 $path"
        else "转储失败（未连接 daemon）",
        Toast.LENGTH_LONG,
    ).show()
}
