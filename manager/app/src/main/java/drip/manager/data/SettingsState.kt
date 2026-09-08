// 设置项 UI 状态：daemon 项（statusNotification/verboseLog/forceAppIcon）最终以 daemon 生效值
// 为准，本地 SharedPreferences 存用户意图；主题项（amoledBlack/dynamicColor）纯本地持久化。
package drip.manager.data

import android.content.Context

data class SettingsState(
    val statusNotification: Boolean = true,
    val verboseLog: Boolean = false,
    val forceAppIcon: Boolean = false,
    val loggingSilenced: Boolean = false,
    val amoledBlack: Boolean = true,
    val dynamicColor: Boolean = true,
    /** 日志显示样式：0 = 终端（默认），1 = 卡片。 */
    val logDisplayStyle: Int = 0,
) {
    fun save(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_STATUS_NOTIFICATION, statusNotification)
            .putBoolean(KEY_VERBOSE_LOG, verboseLog)
            .putBoolean(KEY_FORCE_APP_ICON, forceAppIcon)
            .putBoolean(KEY_LOGGING_SILENCED, loggingSilenced)
            .putBoolean(KEY_AMOLED_BLACK, amoledBlack)
            .putBoolean(KEY_DYNAMIC_COLOR, dynamicColor)
            .putInt(KEY_LOG_DISPLAY_STYLE, logDisplayStyle)
            .apply()
    }

    companion object {
        const val PREFS_NAME = "drip_settings"

        private const val KEY_STATUS_NOTIFICATION = "status_notification"
        private const val KEY_VERBOSE_LOG = "verbose_log"
        private const val KEY_FORCE_APP_ICON = "force_app_icon"
        private const val KEY_LOGGING_SILENCED = "logging_silenced"
        private const val KEY_AMOLED_BLACK = "amoled_black"
        private const val KEY_DYNAMIC_COLOR = "dynamic_color"
        private const val KEY_LOG_DISPLAY_STYLE = "log_display_style"

        fun load(context: Context): SettingsState {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return SettingsState(
                statusNotification = prefs.getBoolean(KEY_STATUS_NOTIFICATION, true),
                verboseLog = prefs.getBoolean(KEY_VERBOSE_LOG, false),
                forceAppIcon = prefs.getBoolean(KEY_FORCE_APP_ICON, false),
                loggingSilenced = prefs.getBoolean(KEY_LOGGING_SILENCED, false),
                amoledBlack = prefs.getBoolean(KEY_AMOLED_BLACK, true),
                dynamicColor = prefs.getBoolean(KEY_DYNAMIC_COLOR, true),
                logDisplayStyle = prefs.getInt(KEY_LOG_DISPLAY_STYLE, 0),
            )
        }
    }
}
