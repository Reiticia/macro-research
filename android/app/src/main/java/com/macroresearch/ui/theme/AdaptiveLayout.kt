package com.macroresearch.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import com.macroresearch.data.DisplayMode

/** Visual spacing only; Material's minimum 48dp interactive targets remain untouched. */
data class AdaptiveLayout(
    val fontFactor: Float = 1f,
    val spacingFactor: Float = 1f,
    val stackMetadata: Boolean = false,
) {
    val pagePadding: Dp get() = (16 * spacingFactor).dp
    val cardPadding: Dp get() = (16 * spacingFactor).dp
    val gap: Dp get() = (12 * spacingFactor).dp
    val smallGap: Dp get() = (8 * spacingFactor).dp

    /** Tighter rhythm for the home hero and mini cards, so the event list keeps the remaining height. */
    val denseGap: Dp get() = (6 * spacingFactor).dp
    val miniCardPadding: Dp get() = (8 * spacingFactor).dp
}

/** Uses window dp (not physical pixels): rotation, split-screen and display zoom work naturally. */
internal fun adaptiveLayout(widthDp: Float, fontScale: Float, mode: DisplayMode): AdaptiveLayout {
    val width = widthDp.takeIf { it.isFinite() && it > 0 } ?: 440f
    val scale = when (mode) {
        DisplayMode.AUTO -> (width / 440f).coerceIn(0.86f, 1f)
        DisplayMode.COMPACT -> 0.86f
        DisplayMode.SYSTEM -> 1f
    }
    return AdaptiveLayout(
        fontFactor = scale,
        spacingFactor = if (scale < 1f) (scale - 0.10f).coerceAtLeast(0.75f) else 1f,
        stackMetadata = width / fontScale.coerceAtLeast(1f) < 340f,
    )
}

internal val LocalAdaptiveLayout = staticCompositionLocalOf { AdaptiveLayout() }
val ResearchLayout: AdaptiveLayout
    @Composable @ReadOnlyComposable get() = LocalAdaptiveLayout.current

data class DisplaySettings(
    val mode: DisplayMode = DisplayMode.AUTO,
    val setMode: (DisplayMode) -> Unit = {},
)
val LocalDisplaySettings = staticCompositionLocalOf { DisplaySettings() }

/** Retains sp units, weights and font families; system font scaling still applies afterwards. */
internal fun adaptiveTypography(factor: Float, base: Typography = Typography()): Typography {
    fun TextStyle.scaled() = copy(
        fontSize = if (fontSize.isSpecified) (fontSize.value * factor).coerceAtLeast(11f).sp else fontSize,
        lineHeight = if (lineHeight.isSpecified) (lineHeight.value * factor).coerceAtLeast(14f).sp else lineHeight,
    )
    return base.copy(
        displayLarge = base.displayLarge.scaled(), displayMedium = base.displayMedium.scaled(),
        displaySmall = base.displaySmall.scaled(), headlineLarge = base.headlineLarge.scaled(),
        headlineMedium = base.headlineMedium.scaled(), headlineSmall = base.headlineSmall.scaled(),
        titleLarge = base.titleLarge.scaled(), titleMedium = base.titleMedium.scaled(),
        titleSmall = base.titleSmall.scaled(), bodyLarge = base.bodyLarge.scaled(),
        bodyMedium = base.bodyMedium.scaled(), bodySmall = base.bodySmall.scaled(),
        labelLarge = base.labelLarge.scaled(), labelMedium = base.labelMedium.scaled(),
        labelSmall = base.labelSmall.scaled(),
    )
}
