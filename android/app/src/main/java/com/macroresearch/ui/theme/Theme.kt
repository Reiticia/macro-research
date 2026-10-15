package com.macroresearch.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macroresearch.data.DisplayPreferences
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LocalResearchColors = staticCompositionLocalOf { LightResearchColors }

private val TerminalShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(8.dp),
)

val Hawkish: Color
    @Composable @ReadOnlyComposable get() = LocalResearchColors.current.hawkish
val Dovish: Color
    @Composable @ReadOnlyComposable get() = LocalResearchColors.current.dovish
val Upcoming: Color
    @Composable @ReadOnlyComposable get() = LocalResearchColors.current.upcoming
val AssetUp: Color
    @Composable @ReadOnlyComposable get() = LocalResearchColors.current.assetUp
val AssetDown: Color
    @Composable @ReadOnlyComposable get() = LocalResearchColors.current.assetDown

@Composable
fun MacroTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme ->
            dynamicDarkColorScheme(LocalContext.current)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            dynamicLightColorScheme(LocalContext.current)
        darkTheme -> DarkColors
        else -> LightColors
    }
    val context = LocalContext.current
    val preferences = remember(context.applicationContext) { DisplayPreferences(context.applicationContext) }
    val mode by preferences.mode.collectAsStateWithLifecycle()
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val layout = remember(maxWidth, fontScale, mode) { adaptiveLayout(maxWidth.value, fontScale, mode) }
        val typography = remember(layout.fontFactor) { adaptiveTypography(layout.fontFactor) }
        val numeric = remember(layout.fontFactor) { numericType(layout.fontFactor) }
        CompositionLocalProvider(
            LocalResearchColors provides if (darkTheme) DarkResearchColors else LightResearchColors,
            LocalAdaptiveLayout provides layout,
            LocalNumericType provides numeric,
            LocalDisplaySettings provides DisplaySettings(mode, preferences::setMode),
        ) {
            MaterialTheme(colorScheme = colors, typography = typography, shapes = TerminalShapes, content = content)
        }
    }
}
