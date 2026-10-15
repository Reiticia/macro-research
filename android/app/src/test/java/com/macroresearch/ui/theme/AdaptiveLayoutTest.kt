package com.macroresearch.ui.theme

import androidx.compose.material3.Typography
import com.macroresearch.data.DisplayMode
import org.junit.Assert.*
import org.junit.Test

class AdaptiveLayoutTest {
    @Test fun targetPhoneUsesCompactVisualsWithoutChangingDensity() {
        // fb54e284: 1080px at 440dpi = approximately 393dp.
        val layout = adaptiveLayout(1080f / (440f / 160f), 1f, DisplayMode.AUTO)
        assertEquals(0.893f, layout.fontFactor, 0.002f)
        assertTrue(layout.cardPadding.value in 12f..13f)
        assertFalse(layout.stackMetadata)
    }

    @Test fun autoRespondsToWindowWidthAndIsBounded() {
        val narrow = adaptiveLayout(320f, 1f, DisplayMode.AUTO)
        val phone = adaptiveLayout(393f, 1f, DisplayMode.AUTO)
        val tablet = adaptiveLayout(840f, 1f, DisplayMode.AUTO)
        assertEquals(0.86f, narrow.fontFactor)
        assertTrue(narrow.fontFactor < phone.fontFactor)
        assertEquals(1f, tablet.fontFactor)
        assertTrue(narrow.stackMetadata)
        assertEquals(1f, adaptiveLayout(Float.POSITIVE_INFINITY, 1f, DisplayMode.AUTO).fontFactor)
        assertEquals(1f, adaptiveLayout(0f, 1f, DisplayMode.AUTO).fontFactor)
    }

    @Test fun eventRowsStackEarlierThanOtherMetadata() {
        val phone360 = adaptiveLayout(360f, 1f, DisplayMode.AUTO)
        assertTrue(phone360.stackEventRows)
        assertFalse(phone360.stackMetadata)
        assertFalse(adaptiveLayout(393f, 1f, DisplayMode.AUTO).stackEventRows)
        assertTrue(adaptiveLayout(412f, 1.15f, DisplayMode.AUTO).stackEventRows)
        assertFalse(adaptiveLayout(840f, 1f, DisplayMode.AUTO).stackEventRows)
    }

    @Test fun explicitModesArePredictableAndPreferencesFallBackSafely() {
        assertEquals(1f, adaptiveLayout(320f, 1f, DisplayMode.SYSTEM).fontFactor)
        assertEquals(16f, adaptiveLayout(320f, 1f, DisplayMode.SYSTEM).cardPadding.value)
        assertEquals(0.86f, adaptiveLayout(840f, 1f, DisplayMode.COMPACT).fontFactor)
        assertEquals(DisplayMode.AUTO, DisplayMode.fromStored(null))
        assertEquals(DisplayMode.AUTO, DisplayMode.fromStored("old-mode"))
        DisplayMode.entries.forEach { assertEquals(it, DisplayMode.fromStored(it.name)) }
    }

    @Test fun accessibilityScalingIsNotCancelledOut() {
        val normal = adaptiveLayout(393f, 1f, DisplayMode.AUTO)
        val largeFont = adaptiveLayout(393f, 2f, DisplayMode.AUTO)
        assertEquals(normal.fontFactor, largeFont.fontFactor)
        assertTrue(largeFont.stackMetadata)
        // sp is scaled by Android after these styles are supplied; we never override LocalDensity.
        val typography = adaptiveTypography(largeFont.fontFactor)
        assertTrue(typography.bodyLarge.fontSize.value * 2 > TerminalTypography.bodyLarge.fontSize.value)
        assertTrue(typography.labelSmall.fontSize.value >= 11)
        assertTrue(typography.bodyLarge.lineHeight.value > typography.bodyLarge.fontSize.value)
    }

    @Test fun systemModeUsesTerminalTypography() {
        assertEquals(TerminalTypography, adaptiveTypography(1f))
    }

    @Test fun terminalTypographyIsOneStepSmallerThanMaterial() {
        val material = Typography()
        assertEquals(14f, TerminalTypography.bodyLarge.fontSize.value)
        assertEquals(18f, TerminalTypography.titleLarge.fontSize.value)
        assertEquals(11f, TerminalTypography.labelSmall.fontSize.value)
        assertTrue(TerminalTypography.bodyLarge.fontSize.value < material.bodyLarge.fontSize.value)
        assertTrue(TerminalTypography.titleLarge.fontSize.value < material.titleLarge.fontSize.value)
        assertTrue(TerminalTypography.bodyMedium.fontSize.value < material.bodyMedium.fontSize.value)
        listOf(
            TerminalTypography.headlineSmall, TerminalTypography.titleLarge, TerminalTypography.titleMedium,
            TerminalTypography.titleSmall, TerminalTypography.bodyLarge, TerminalTypography.bodyMedium,
            TerminalTypography.bodySmall, TerminalTypography.labelLarge, TerminalTypography.labelMedium,
            TerminalTypography.labelSmall,
        ).forEach { assertTrue(it.lineHeight.value > it.fontSize.value) }
    }

    @Test fun tableRowsKeepTouchTargetsWhileCompact() {
        val compact = adaptiveLayout(320f, 1f, DisplayMode.COMPACT)
        val system = adaptiveLayout(320f, 1f, DisplayMode.SYSTEM)
        assertEquals(8f, system.rowVertical.value)
        assertEquals(12f, system.sectionTop.value)
        assertTrue(compact.rowVertical < system.rowVertical)
        assertTrue(compact.rowVertical.value >= 6f)
    }
}
