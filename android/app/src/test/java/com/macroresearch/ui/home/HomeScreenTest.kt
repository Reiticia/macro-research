package com.macroresearch.ui.home

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class HomeScreenTest {
    @Test fun pixelWidthShowsThreeMarketsAcrossAndFourAsTwoByTwo() {
        assertEquals(3, marketOverviewColumns(387f, 1f, 3))
        assertEquals(2, marketOverviewColumns(387f, 1f, 4))
        assertEquals(2, marketOverviewColumns(387f, 1f, 2))
    }

    @Test fun narrowWindowsAndLargeFontsReduceColumnsWithoutHorizontalScrolling() {
        assertEquals(2, marketOverviewColumns(296f, 1f, 3))
        assertEquals(2, marketOverviewColumns(387f, 1.3f, 3))
        assertEquals(1, marketOverviewColumns(296f, 2f, 4))
        assertEquals(3, marketOverviewColumns(732f, 1f, 3))
        assertEquals(1, marketOverviewColumns(0f, Float.NaN, 0))
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
