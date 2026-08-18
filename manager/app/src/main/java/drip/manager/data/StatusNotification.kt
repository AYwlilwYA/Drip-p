// 状态常驻通知：由 manager 宿主进程投递（daemon 是 root 进程，无 UI Context，
// 拿不到 NotificationManager）。daemon 只持久化开关；manager 连接成功后按开关投递/撤销。
package drip.manager.data

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import drip.manager.R
import drip.manager.ui.MainActivity
import java.io.FileInputStream

/** 常驻通知固定 ID（每次覆盖同一 ID，不叠加）。 */
private const val STATUS_NOTIFICATION_ID = 1001
private const val STATUS_CHANNEL_ID = "drip_status"
private const val TAG = "DripManager"

// 寄生模式（进程 = 宿主 com.android.shell）下 manager 无独立 Activity 组件可点。
// 改指宿主可启动的 BugreportWarningActivity + LAUNCH_MANAGER category，由宿主 framework
// 接管 → manager UI。独立模式保持原样。
private const val PARASITIC_HOST_PACKAGE = "com.android.shell"
private const val PARASITIC_HOST_ACTIVITY = "com.android.shell.BugreportWarningActivity"
private const val LAUNCH_MANAGER_CATEGORY = "org.lsposed.manager.LAUNCH_MANAGER"

/** 当前进程是否为寄生宿主（com.android.shell）。
 * 用 /proc/self/cmdline（与 Process.myProcessName 等效，且不受 minSdk 27 限制——
 * myProcessName 需 API 28+）。cmdline 以 NUL 结尾，substringBefore(0.toChar()) 去掉。 */
internal fun isParasiticHostProcess(): Boolean = try {
    val buf = ByteArray(64)
    val len = FileInputStream("/proc/self/cmdline").use { it.read(buf) }
    PARASITIC_HOST_PACKAGE == String(buf, 0, len).substringBefore(0.toChar())
} catch (_: Throwable) {
    false
}

// ==== 寄生模式通知图标修复 ====
// 寄生模式通知由宿主进程发出，manager 的资源 ID 不在宿主资源表中 → fallback 默认图标。
// 修复：寄生模式下将图标以 Bitmap Icon 内嵌到 Notification；独立模式保持资源 ID 不变。

/** 将 Drawable 转为 Bitmap（API 23+ 通知 Icon 需要）。 */
internal fun drawableToBitmap(drawable: android.graphics.drawable.Drawable): Bitmap {
    val bitmap = Bitmap.createBitmap(
        drawable.intrinsicWidth.coerceAtLeast(1),
        drawable.intrinsicHeight.coerceAtLeast(1),
        Bitmap.Config.ARGB_8888,
    )
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bitmap
}

/**
 * 获取 Drip 通知小图标（Icon 对象）。
 * - 寄生模式：返回 Bitmap Icon；
 * - 独立模式：返回 null（调用方直接用 R.drawable.ic_stat_drip 资源 ID）。
 */
internal fun dripSmallIcon(context: Context): Icon? {
    if (!isParasiticHostProcess()) return null
    val drawable = ContextCompat.getDrawable(context, R.drawable.ic_stat_drip) ?: return null
    return Icon.createWithBitmap(drawableToBitmap(drawable))
}

/** 通知本体点击打开 manager 的 Intent：寄生模式 → 宿主 Activity + LAUNCH_MANAGER；
 * 独立模式 → manager MainActivity 显式组件。 */
internal fun buildManagerOpenIntent(context: Context): Intent =
    if (isParasiticHostProcess()) {
        Intent().apply {
            component = ComponentName(PARASITIC_HOST_PACKAGE, PARASITIC_HOST_ACTIVITY)
            addCategory(LAUNCH_MANAGER_CATEGORY)
        }
    } else {
        Intent(context, MainActivity::class.java)
    }

/** Android 13+（TIRAMISU）需要运行时权限才可投递通知；更早版本默认有权限。 */
fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ActivityCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

/** 创建通知渠道（Android O+；幂等，重复创建无害）。低优先级 → 静默常驻、不响铃不打扰。 */
private fun createStatusNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val channel =
        NotificationChannel(
            STATUS_CHANNEL_ID,
            "Drip 框架状态",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Drip 框架运行状态常驻通知"
            setShowBadge(false)
        }
    context.getSystemService(NotificationManager::class.java)
        .createNotificationChannel(channel)
}

/** 投递常驻状态通知。调用方需先确认 [hasNotificationPermission]，否则被系统静默丢弃。 */
fun showStatusNotification(context: Context) {
    try {
        createStatusNotificationChannel(context)
        val intent =
            buildManagerOpenIntent(context).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP }
        val pendingIntent =
            PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        // Notification.Builder + Icon.createWithBitmap；
        // 独立模式：NotificationCompat.Builder + 资源 ID（标准路径）。
        val notification =
            if (isParasiticHostProcess()) {
                Notification.Builder(context, STATUS_CHANNEL_ID)
                    .setSmallIcon(dripSmallIcon(context)!!)
                    .setContentTitle("Drip 框架已运行")
                    .setContentText("点击打开 Drip Manager")
                    .setContentIntent(pendingIntent)
                    .setOngoing(true)
                    .setCategory(Notification.CATEGORY_STATUS)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .build()
            } else {
                NotificationCompat.Builder(context, STATUS_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_drip)
                    .setContentTitle("Drip 框架已运行")
                    .setContentText("点击打开 Drip Manager")
                    .setContentIntent(pendingIntent)
                    .setOngoing(true) // 常驻：用户不可滑动移除
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .build()
            }
        NotificationManagerCompat.from(context).notify(STATUS_NOTIFICATION_ID, notification)
        Log.i(TAG, "status notification posted (id=$STATUS_NOTIFICATION_ID)")
    } catch (e: Exception) {
        // 权限事后被撤销等场景 notify() 抛 SecurityException：投递失败不影响主流程，
        // 但留 logcat 便于诊断「通知不出现」。
        Log.w(TAG, "showStatusNotification failed: $e", e)
    }
}

/** 移除常驻状态通知。 */
fun cancelStatusNotification(context: Context) {
    NotificationManagerCompat.from(context).cancel(STATUS_NOTIFICATION_ID)
}

// ==== 模块加载失败通知 ====

const val FAILURE_CHANNEL_ID = "drip_module_failure"
private const val EXTRA_OPEN_LOGS = "drip.open_logs"
const val EXTRA_LOG_FILTER_TAG = "drip.log_filter_tag"

/** 创建失败通知渠道（IMPORTANCE_DEFAULT，有声音提醒）。幂等。 */
fun createFailureNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val channel = NotificationChannel(
        FAILURE_CHANNEL_ID,
        "模块加载失败",
        NotificationManager.IMPORTANCE_DEFAULT,
    ).apply {
        description = "模块加载失败时通知"
        setShowBadge(true)
    }
    context.getSystemService(NotificationManager::class.java)
        .createNotificationChannel(channel)
}

// ==== 注入失败通知 ====

const val INJECTION_FAILURE_CHANNEL_ID = "drip_inject_fail"
const val INJECTION_FAILURE_NOTIFICATION_ID = 2001

/** 创建注入失败通知渠道（IMPORTANCE_HIGH，有声音提醒）。幂等。 */
fun createInjectionFailureChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val channel = NotificationChannel(
        INJECTION_FAILURE_CHANNEL_ID,
        "注入失败",
        NotificationManager.IMPORTANCE_HIGH,
    ).apply {
        description = "Drip 注入失败时通知"
        setShowBadge(true)
    }
    context.getSystemService(NotificationManager::class.java)
        .createNotificationChannel(channel)
}

/**
 * 构建点击通知后打开日志页的 Intent。
 * 寄生模式 → 宿主 Activity + LAUNCH_MANAGER + extras；独立模式 → MainActivity + extras。
 */
fun buildFailureIntent(context: Context, packageName: String, label: String): Intent =
    if (isParasiticHostProcess()) {
        Intent().apply {
            component = ComponentName(PARASITIC_HOST_PACKAGE, PARASITIC_HOST_ACTIVITY)
            addCategory(LAUNCH_MANAGER_CATEGORY)
            putExtra(EXTRA_OPEN_LOGS, true)
            putExtra(EXTRA_LOG_FILTER_TAG, label) // 用模块名过滤日志
        }
    } else {
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_LOGS, true)
            putExtra(EXTRA_LOG_FILTER_TAG, label)
        }
    }
