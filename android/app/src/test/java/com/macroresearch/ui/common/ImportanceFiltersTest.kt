package com.macroresearch.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class ImportanceFiltersTest {
    @Test fun multipleLevelsCanBeSelectedAndToggledIndependently() {
        val all = setOf(1, 2, 3)
        val highAndLow = toggleImportanceSelection(all, 2)
        assertEquals(setOf(1, 3), highAndLow)
        val high = toggleImportanceSelection(highAndLow, 1)
        assertEquals(setOf(3), high)
        assertEquals(setOf(2, 3), toggleImportanceSelection(high, 2))
        assertEquals(all, toggleImportanceSelection(highAndLow, 2))
    }

    @Test fun lastSelectedLevelCannotBeRemoved() {
        for (level in 1..3) {
            assertEquals(setOf(level), toggleImportanceSelection(setOf(level), level))
        }
    }

    @Test fun unsupportedLevelsDoNotChangeTheSelection() {
        val selected = setOf(1, 3)
        assertEquals(selected, toggleImportanceSelection(selected, 0))
        assertEquals(selected, toggleImportanceSelection(selected, 4))
        assertEquals(selected, toggleImportanceSelection(selected, -1))
    }
}
