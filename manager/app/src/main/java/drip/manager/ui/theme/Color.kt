// Drip 手调紫色兜底色板：动态取色不可用（Android 12 以下）时使用。
package drip.manager.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// 品牌紫
val DripPurple = Color(0xFF6C5CE7)
val DripPurpleLight = Color(0xFF8F7FFF)
val DripPurpleDark = Color(0xFFB8A9FF)

// 状态色（与品牌紫同族的绿/红）
val DripGreen = Color(0xFF59B463)   // 完成态固定绿
val DripRed = Color(0xFFE5484D)
val DripAmber = Color(0xFFF5A524)

// 亮色兜底
val DripLightColorScheme = lightColorScheme(
    primary = DripPurple,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7E2FF),
    onPrimaryContainer = Color(0xFF241A54),
    secondary = Color(0xFF5C5771),
    secondaryContainer = Color(0xFFE2DDF5),
    onSecondaryContainer = Color(0xFF191527),
    tertiary = Color(0xFF7A5433),
    tertiaryContainer = Color(0xFFFFDCC2),
    error = DripRed,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFAF9FF),
    onBackground = Color(0xFF1B1B21),
    surface = Color(0xFFFAF9FF),
    onSurface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFFE6E1EC),
    onSurfaceVariant = Color(0xFF49454F),
    outline = Color(0xFF7A7580),
    outlineVariant = Color(0xFFCAC4D0),
    surfaceBright = Color(0xFFFBFAFF),
    surfaceContainerLow = Color(0xFFF4F3F9),
    surfaceContainer = Color(0xFFEFEEF4),
    surfaceContainerHigh = Color(0xFFE9E8EE),
    surfaceContainerHighest = Color(0xFFE3E2E9),
)

// 暗色兜底
val DripDarkColorScheme = darkColorScheme(
    primary = DripPurpleDark,
    onPrimary = Color(0xFF2A2260),
    primaryContainer = Color(0xFF4A3D96),
    onPrimaryContainer = Color(0xFFE7E2FF),
    secondary = Color(0xFFC6C0DE),
    secondaryContainer = Color(0xFF443F58),
    onSecondaryContainer = Color(0xFFE2DDF5),
    tertiary = Color(0xFFEFBD8E),
    tertiaryContainer = Color(0xFF5F3D1F),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF14131A),
    onBackground = Color(0xFFE5E1E9),
    surface = Color(0xFF14131A),
    onSurface = Color(0xFFE5E1E9),
    surfaceVariant = Color(0xFF49454F),
    onSurfaceVariant = Color(0xFFCAC4D0),
    outline = Color(0xFF948F99),
    outlineVariant = Color(0xFF49454F),
    surfaceBright = Color(0xFF3A3941),
    surfaceContainerLow = Color(0xFF1D1C23),
    surfaceContainer = Color(0xFF212027),
    surfaceContainerHigh = Color(0xFF2B2A32),
    surfaceContainerHighest = Color(0xFF36353D),
)
