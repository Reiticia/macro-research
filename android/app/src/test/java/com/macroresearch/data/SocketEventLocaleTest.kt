package com.macroresearch.data

import com.macroresearch.data.model.SocketEvent
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class SocketEventLocaleTest {
    private val event = SocketEvent("economic_event_released", 7, "CPI", "消费者物价", "消費者物價")

    @Test fun notificationsSelectEnglishSimplifiedAndTraditionalIndependently() {
        assertEquals("CPI", event.localizedName(Locale.ENGLISH))
        assertEquals("CPI", event.localizedName(Locale.FRENCH))
        assertEquals("消费者物价", event.localizedName(Locale.SIMPLIFIED_CHINESE))
        for (tag in listOf("zh-TW", "zh-HK", "zh-MO", "zh-Hant", "zh-Hant-US")) {
            assertEquals(tag, "消費者物價", event.localizedName(Locale.forLanguageTag(tag)))
        }
    }

    @Test fun missingOrBlankTranslationFallsBackOnlyToOriginalName() {
        val missing = event.copy(eventZhCn = " ", eventZhTw = null)
        assertEquals("CPI", missing.localizedName(Locale.SIMPLIFIED_CHINESE))
        assertEquals("CPI", missing.localizedName(Locale.TRADITIONAL_CHINESE))
        assertNull(event.copy(event = null).localizedName(Locale.ENGLISH))
        assertNull(missing.copy(event = "").localizedName(Locale.TRADITIONAL_CHINESE))
    }
}
