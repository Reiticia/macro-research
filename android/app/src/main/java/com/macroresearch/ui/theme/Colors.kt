package com.macroresearch.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

@Immutable
data class ResearchColors(
    val hawkish: Color,
    val dovish: Color,
    val upcoming: Color,
    val assetUp: Color,
    val assetDown: Color,
)

/**
 * Dark palette: Carbon Obsidian & Luminous Sapphire.
 * All foreground tokens maintain >= 4.5:1 contrast across background..surfaceContainerHighest.
 */
internal val DarkColors = darkColorScheme(
    background = Color(0xFF090D14),
    surface = Color(0xFF0E141F),
    surfaceVariant = Color(0xFF1A2434),
    surfaceContainerLowest = Color(0xFF0B1018),
    surfaceContainerLow = Color(0xFF121A27),
    surfaceContainer = Color(0xFF162030),
    surfaceContainerHigh = Color(0xFF1B2738),
    surfaceContainerHighest = Color(0xFF202E42),
    primary = Color(0xFF79B8FF),
    onPrimary = Color(0xFF041E3A),
    primaryContainer = Color(0xFF10243E),
    onPrimaryContainer = Color(0xFFE6F1FF),
    secondary = Color(0xFF8CC4FF),
    onSecondary = Color(0xFF06203C),
    secondaryContainer = Color(0xFF142A47),
    onSecondaryContainer = Color(0xFFE6F1FF),
    tertiary = Color(0xFFF2C46D),
    onTertiary = Color(0xFF2B1D00),
    tertiaryContainer = Color(0xFF3D2C07),
    onTertiaryContainer = Color(0xFFFFE6B8),
    onBackground = Color(0xFFEDF2F8),
    onSurface = Color(0xFFEDF2F8),
    onSurfaceVariant = Color(0xFFA8B6CC),
    outline = Color(0xFF52627A),
    outlineVariant = Color(0xFF253246),
    error = Color(0xFFFF8A9A),
    onError = Color(0xFF3B0610),
    errorContainer = Color(0xFF4F111D),
    onErrorContainer = Color(0xFFFFD9DE),
)

/**
 * Light palette: Cool Architectural Alabaster & Deep Institutional Cobalt.
 * All foreground tokens maintain >= 4.5:1 contrast across background..surfaceContainerHighest.
 */
internal val LightColors = lightColorScheme(
    background = Color(0xFFF3F5F9),
    surface = Color(0xFFF8FAFC),
    surfaceVariant = Color(0xFFE3E8F0),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEEF2F7),
    surfaceContainer = Color(0xFFE8EDF4),
    surfaceContainerHigh = Color(0xFFE2E8F0),
    surfaceContainerHighest = Color(0xFFDCE3ED),
    primary = Color(0xFF0E497C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDFECFA),
    onPrimaryContainer = Color(0xFF082846),
    secondary = Color(0xFF1A4B84),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCE9F8),
    onSecondaryContainer = Color(0xFF0A2644),
    tertiary = Color(0xFF754900),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFCE8C3),
    onTertiaryContainer = Color(0xFF331E00),
    onBackground = Color(0xFF0D1624),
    onSurface = Color(0xFF0D1624),
    onSurfaceVariant = Color(0xFF3E4C63),
    outline = Color(0xFF718096),
    outlineVariant = Color(0xFFCBD5E1),
    error = Color(0xFFA82032),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFCE4E7),
    onErrorContainer = Color(0xFF4A0812),
)

internal val DarkResearchColors = ResearchColors(
    hawkish = Color(0xFFFF8A9A),
    dovish = Color(0xFF6EC8FF),
    upcoming = Color(0xFFF2C46D),
    assetUp = Color(0xFF56D9A0),
    assetDown = Color(0xFFFF8A9A),
)

internal val LightResearchColors = ResearchColors(
    hawkish = Color(0xFFA82032),
    dovish = Color(0xFF0D5685),
    upcoming = Color(0xFF754900),
    assetUp = Color(0xFF0B6346),
    assetDown = Color(0xFFA82032),
)
