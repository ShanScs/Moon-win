package com.muse.moonpc.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Moon PC 主题：Surge Mac 风格。
 * 中性色（白/浅灰/深灰）+ iOS 蓝 #007AFF 点缀，替代 Material3 默认紫色。
 */
object MoonColors {
    val Accent = Color(0xFF007AFF)          // iOS 蓝：主强调色
    val AccentDark = Color(0xFF0A84FF)      // 深色模式下的蓝
    val Background = Color(0xFFF5F5F7)      // 主区浅灰
    val SidebarBg = Color(0xFFFFFFFF)       // 侧边栏白
    val CardBg = Color(0xFFFFFFFF)          // 卡片白
    val Divider = Color(0xFFE5E5EA)         // 分隔线
    val TextPrimary = Color(0xFF1C1C1E)     // 主文字
    val TextSecondary = Color(0xFF8E8E93)   // 辅助文字
    val Success = Color(0xFF34C759)         // 绿：运行中/低延迟
    val Warning = Color(0xFFFF9500)         // 橙：中延迟
    val Danger = Color(0xFFFF3B30)          // 红：高延迟/错误
}

private val MoonLightScheme = lightColorScheme(
    primary = MoonColors.Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE5F1FF),
    onPrimaryContainer = MoonColors.Accent,
    secondary = MoonColors.Accent,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE5F1FF),
    onSecondaryContainer = MoonColors.Accent,
    tertiary = MoonColors.Accent,
    tertiaryContainer = Color(0xFFE5F1FF),
    onTertiaryContainer = MoonColors.Accent,
    background = MoonColors.Background,
    onBackground = MoonColors.TextPrimary,
    surface = MoonColors.CardBg,
    onSurface = MoonColors.TextPrimary,
    surfaceVariant = Color(0xFFF2F2F7),
    onSurfaceVariant = MoonColors.TextSecondary,
    outline = MoonColors.Divider,
    outlineVariant = MoonColors.Divider,
    error = MoonColors.Danger,
)

private val MoonDarkScheme = darkColorScheme(
    primary = MoonColors.AccentDark,
    onPrimary = Color.White,
    background = Color(0xFF1C1C1E),
    onBackground = Color.White,
    surface = Color(0xFF2C2C2E),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF2C2C2E),
    onSurfaceVariant = Color(0xFF98989F),
    outline = Color(0xFF38383A),
    error = MoonColors.Danger,
)

/** Moon PC 主题入口：包住整个窗口内容 */
@Composable
fun MoonTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) MoonDarkScheme else MoonLightScheme,
        content = content,
    )
}
