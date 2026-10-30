package dev.opencode.client.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 科技感深色主题：深空底 + 电光青主色 + 紫绿点缀
private val Cyan = Color(0xFF22D3EE)
private val CyanDim = Color(0xFF164E5F)
private val Purple = Color(0xFFA78BFA)
private val Green = Color(0xFF4ADE80)

private val DarkColors = darkColorScheme(
    primary = Cyan,
    onPrimary = Color(0xFF04252B),
    primaryContainer = CyanDim,
    onPrimaryContainer = Color(0xFFB8F2FF),
    secondary = Purple,
    tertiary = Green,
    // 层级对比拉大 + 整体压暗（用户反馈白色区域太亮）
    background = Color(0xFF050609),
    onBackground = Color(0xFF9BA3B0),
    surface = Color(0xFF0B0D12),
    onSurface = Color(0xFF9BA3B0),
    surfaceVariant = Color(0xFF14181F),
    onSurfaceVariant = Color(0xFF6B7383),
    surfaceTint = Color.Transparent,
    // 对话框/菜单底色压暗，避免弹层发白
    surfaceContainerHigh = Color(0xFF10141A),
    surfaceContainerHighest = Color(0xFF161B23),
    surfaceContainer = Color(0xFF0D1015),
    surfaceContainerLow = Color(0xFF0A0C10),
    surfaceContainerLowest = Color(0xFF050609),
    error = Color(0xFFFF5C7A),
    onError = Color(0xFF2B040D),
    outline = Color(0xFF39414F),
    outlineVariant = Color(0xFF242A34),
    inverseSurface = Color(0xFF9BA3B0),
    inverseOnSurface = Color(0xFF050609),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0E7490),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFF6FE),
    onPrimaryContainer = Color(0xFF04252B),
    secondary = Color(0xFF6D28D9),
    tertiary = Color(0xFF15803D),
    background = Color(0xFFF7F8FB),
    onBackground = Color(0xFF171A21),
    surface = Color.White,
    onSurface = Color(0xFF171A21),
    surfaceVariant = Color(0xFFEAEDF4),
    onSurfaceVariant = Color(0xFF4B5566),
    error = Color(0xFFB3261E),
    outlineVariant = Color(0xFFCBD3E1),
)

@Composable
fun OpenCodeClientTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    androidx.compose.runtime.CompositionLocalProvider(LocalAppDarkTheme provides darkTheme) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}
