package com.macroresearch.ui.home

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class HomeScreenTest {
    @Test fun narrowPhonesKeepReadableCardsInsteadOfStackingRows() {
        val width = marketOverviewCardWidth(360f, 1f, 3)
        assertEquals(140f, width, 0.01f)
        assertTrue(width * 3 + 12 > 360) // Horizontal overflow, not vertical rows.
        assertEquals(280f, marketOverviewCardWidth(360f, 2f, 4), 0.01f)
    }

    @Test fun wideWindowsFitCardsAndEmptySelectionIsSafe() {
        assertEquals(240f, marketOverviewCardWidth(732f, 1f, 3), 0.01f)
        assertTrue(marketOverviewCardWidth(0f, Float.NaN, 0).isFinite())
    }

    @Test fun sameDayTimeIsShortButOldQuotesKeepTheDate() {
        val now = Instant.parse("2026-11-13T08:00:00Z")
        val zone = ZoneId.of("Asia/Shanghai")
        val today = homeQuoteTime("2026-11-13T07:50:00Z", Locale.US, now, zone)!!
        val yesterday = homeQuoteTime("2026-11-12T07:50:00Z", Locale.US, now, zone)!!
        assertFalse(today.contains("/"))
        assertTrue(yesterday.contains("11/12/26"))
        assertNull(homeQuoteTime("invalid", Locale.US, now, zone))
    }
}
