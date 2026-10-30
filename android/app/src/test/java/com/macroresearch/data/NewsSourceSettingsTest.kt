package com.macroresearch.data

import org.junit.Assert.*
import org.junit.Test

class NewsSourceSettingsTest {
    @Test fun biquoteOnlyAllowsEmptyRssAndStaysEmptyAfterRestore() {
        assertTrue(validNewsSources(true, true, emptyList()))
        val saved = emptyList<String>().joinToString("\n")
        assertEquals(emptyList<String>(), restoredNewsSources(saved))
        assertEquals(emptyList<String>(), restoredNewsSources(" \n\t"))
        assertEquals(NewsPreferences.DEFAULT_NEWS_SOURCES, restoredNewsSources(null))
    }

    @Test fun enabledNewsRequiresAtLeastOneSourceButCanBeTurnedOff() {
        assertFalse(validNewsSources(true, false, emptyList()))
        assertTrue(validNewsSources(false, false, emptyList()))
        assertTrue(validNewsSources(true, false, listOf("https://example.com/rss")))
        assertFalse(validNewsSources(true, true, listOf("http://example.com/rss")))
        assertFalse(validNewsSources(true, true, listOf("not-a-url")))
        assertFalse(validNewsSources(true, true, (1..9).map { "https://example.com/$it" }))
    }

    @Test fun savedRssStillNormalizesAndRestoresWithoutChangingDefaults() {
        assertEquals(listOf("https://example.com/rss"), restoredNewsSources(" https://example.com/rss\n\nhttps://example.com/rss "))
        assertEquals(8, restoredNewsSources((1..10).joinToString("\n") { "https://example.com/$it" }).size)
    }
}
