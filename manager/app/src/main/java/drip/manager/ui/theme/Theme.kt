// 全局主题：动态取色为主 + AMOLED 纯黑暗色 + MotionScheme.expressive()。
package drip.manager.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Drip Manager 全局主题。
 *
 * @param darkTheme     跟随系统亮暗。
 * @param dynamicColor  Android 12+ 动态取色（跟随壁纸）。
 * @param amoledBlack   暗色下 surface 全置纯黑（AMOLED 省电）。
 */
@Composable
fun DripManagerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    amoledBlack: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val baseScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DripDarkColorScheme
        else -> DripLightColorScheme
    }
    val colorScheme = if (darkTheme && amoledBlack) baseScheme.applyAmoledBlack() else baseScheme

    // motionScheme：全局 Expressive 动效（spec 要点 10）；MaterialTheme 1.5 直接收 motionScheme 参数。
    MaterialTheme(
        colorScheme = colorScheme,
        typography = DripTypography,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}

// AMOLED 纯黑：暗色下把全部 surface 族压到纯黑，仅保留最低限度的层级灰。
private fun ColorScheme.applyAmoledBlack(): ColorScheme = copy(
    background = Color.Black,
    onBackground = Color(0xFFE5E1E9),
    surface = Color.Black,
    onSurface = Color(0xFFE5E1E9),
    surfaceDim = Color.Black,
    surfaceBright = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color.Black,
    surfaceContainer = Color(0xFF0E0E10),
    surfaceContainerHigh = Color(0xFF161618),
    surfaceContainerHighest = Color(0xFF1E1E20),
    surfaceVariant = Color(0xFF161618),
    onSurfaceVariant = Color(0xFFC4C0CC),
)
