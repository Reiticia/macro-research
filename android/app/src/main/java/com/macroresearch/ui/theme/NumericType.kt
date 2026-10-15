package com.macroresearch.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.macroresearch.R

/** 数值专用等宽字体：价格、时间、倒计时等需要按位对齐的内容；中文字符由系统字体回退显示。 */
val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

@Immutable
data class NumericType(
    val hero: TextStyle,
    val large: TextStyle,
    val body: TextStyle,
    val small: TextStyle,
)

/** 与 adaptiveTypography 使用相同的下限；系统字体缩放仍在其后由 sp 生效。 */
internal fun numericType(factor: Float, family: FontFamily = JetBrainsMono): NumericType {
    fun style(size: Float, weight: FontWeight) = TextStyle(
        fontFamily = family,
        fontWeight = weight,
        fontSize = (size * factor).coerceAtLeast(11f).sp,
        lineHeight = (size * factor * 1.3f).coerceAtLeast(14f).sp,
    )
    return NumericType(
        hero = style(24f, FontWeight.Bold),
        large = style(14f, FontWeight.Bold),
        body = style(12f, FontWeight.Normal),
        small = style(11f, FontWeight.Normal),
    )
}

internal val LocalNumericType = staticCompositionLocalOf { numericType(1f, FontFamily.Monospace) }

val ResearchType: NumericType
    @Composable @ReadOnlyComposable get() = LocalNumericType.current
