package com.macroresearch.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

// 数据终端风：近黑/纯白中性表面，用细线代替卡片，唯一强调色为琥珀。
// 补全所有 container/on-container 角色，避免 Material 组件回落到默认紫色。
internal val DarkColors = darkColorScheme(
    primary = Color(0xFFF0B90B),
    onPrimary = Color(0xFF1A1300),
    primaryContainer = Color(0xFF3A2D05),
    onPrimaryContainer = Color(0xFFFBE3A0),
    inversePrimary = Color(0xFF8A6100),
    secondary = Color(0xFFA9B4C0),
    onSecondary = Color(0xFF1B232C),
    secondaryContainer = Color(0xFF2A323B),
    onSecondaryContainer = Color(0xFFD7DCE2),
    tertiary = Color(0xFFA9B4C0),
    onTertiary = Color(0xFF1B232C),
    tertiaryContainer = Color(0xFF2A323B),
    onTertiaryContainer = Color(0xFFD7DCE2),
    background = Color(0xFF0B0E11),
    onBackground = Color(0xFFD7DCE2),
    surface = Color(0xFF0B0E11),
    onSurface = Color(0xFFD7DCE2),
    surfaceVariant = Color(0xFF1F2730),
    onSurfaceVariant = Color(0xFF8A96A3),
    surfaceTint = Color(0xFF0B0E11),
    surfaceDim = Color(0xFF0B0E11),
    surfaceBright = Color(0xFF1F2730),
    surfaceContainerLowest = Color(0xFF0B0E11),
    surfaceContainerLow = Color(0xFF12171C),
    surfaceContainer = Color(0xFF161C22),
    surfaceContainerHigh = Color(0xFF181F26),
    surfaceContainerHighest = Color(0xFF1F2730),
    inverseSurface = Color(0xFFD7DCE2),
    inverseOnSurface = Color(0xFF181F26),
    outline = Color(0xFF5C6773),
    outlineVariant = Color(0xFF1E252C),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF5C0A06),
    errorContainer = Color(0xFF3B1513),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color(0xFF000000),
)

internal val LightColors = lightColorScheme(
    primary = Color(0xFF8A6100),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF7EBCB),
    onPrimaryContainer = Color(0xFF4A3400),
    inversePrimary = Color(0xFFF0B90B),
    secondary = Color(0xFF4D5866),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE9ECEF),
    onSecondaryContainer = Color(0xFF11161B),
    tertiary = Color(0xFF4D5866),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE9ECEF),
    onTertiaryContainer = Color(0xFF11161B),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF11161B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF11161B),
    surfaceVariant = Color(0xFFE6E9ED),
    onSurfaceVariant = Color(0xFF5A6470),
    surfaceTint = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE6E9ED),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F7F9),
    surfaceContainer = Color(0xFFF1F3F5),
    surfaceContainerHigh = Color(0xFFEEF0F3),
    surfaceContainerHighest = Color(0xFFE6E9ED),
    inverseSurface = Color(0xFF1F2730),
    inverseOnSurface = Color(0xFFEEF0F3),
    outline = Color(0xFF8A939D),
    outlineVariant = Color(0xFFE3E6EA),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    scrim = Color(0xFF000000),
)

// 宏观方向不是资产方向：鹰/鸽用紫/蓝，与资产涨跌的绿/红分开。
// "待公布"与强调色同为琥珀；浅色下使用更深的墨色保证小字可读。
@Immutable
internal data class ResearchColors(
    val hawkish: Color,
    val dovish: Color,
    val upcoming: Color,
    val assetUp: Color,
    val assetDown: Color,
)

internal val DarkResearchColors = ResearchColors(
    hawkish = Color(0xFFC5AFE8),
    dovish = Color(0xFF8ABFE0),
    upcoming = Color(0xFFF0B90B),
    assetUp = Color(0xFF2EBD85),
    assetDown = Color(0xFFF75F72),
)

internal val LightResearchColors = ResearchColors(
    hawkish = Color(0xFF7856A8),
    dovish = Color(0xFF236A91),
    upcoming = Color(0xFF8A6100),
    assetUp = Color(0xFF08704A),
    assetDown = Color(0xFFC62839),
)
