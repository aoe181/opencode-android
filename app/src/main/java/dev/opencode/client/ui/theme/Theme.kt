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
    onBackground = Color(0xFFA8AEBA),
    surface = Color(0xFF0C0E13),
    onSurface = Color(0xFFA8AEBA),
    surfaceVariant = Color(0xFF151920),
    onSurfaceVariant = Color(0xFF727B8C),
    surfaceTint = Color.Transparent,
    error = Color(0xFFFF5C7A),
    onError = Color(0xFF2B040D),
    outlineVariant = Color(0xFF2A313E),
    inverseSurface = Color(0xFFB4BAC6),
    inverseOnSurface = Color(0xFF07080B),
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
