package com.macroresearch.ui.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NumericTypeTest {
    @Test fun numericStylesScaleWithLayoutButKeepReadableFloor() {
        val normal = numericType(1f, FontFamily.Monospace)
        val compact = numericType(0.86f, FontFamily.Monospace)
        assertEquals(24f, normal.hero.fontSize.value)
        assertEquals(14f, normal.large.fontSize.value)
        assertEquals(12f, normal.body.fontSize.value)
        assertEquals(11f, normal.small.fontSize.value)
        assertEquals(FontWeight.Bold, normal.hero.fontWeight)
        assertEquals(FontWeight.Normal, normal.body.fontWeight)
        assertTrue(compact.hero.fontSize.value < normal.hero.fontSize.value)
        assertEquals(11f, compact.small.fontSize.value)
        assertTrue(compact.small.lineHeight.value >= 14f)
        assertEquals(FontFamily.Monospace, compact.body.fontFamily)
    }
}
