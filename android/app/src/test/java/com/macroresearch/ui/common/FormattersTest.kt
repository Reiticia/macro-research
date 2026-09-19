package com.macroresearch.ui.common

import com.macroresearch.data.model.EconomicEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.util.Locale

class FormattersTest {
    @Test
    fun formatsPercentageAndSurpriseWithoutFloatingPoint() {
        val event = event(actual = "3.2", consensus = "2.9", unit = "%")
        assertEquals("3.2%", event.value(event.actual))
        assertEquals("0.3", event.surprise()?.stripTrailingZeros()?.toPlainString())
    }

    @Test
    fun countdownHandlesUpcomingAndReleasedEvents() {
        assertEquals("01:01:01", countdown("2026-09-07T01:01:01Z", Instant.parse("2026-09-07T00:00:00Z")))
        assertEquals("Released", countdown("2026-09-06T23:59:59Z", Instant.parse("2026-09-07T00:00:00Z")))
        assertEquals("已公布", countdown("2026-09-06T23:59:59Z", Instant.parse("2026-09-07T00:00:00Z"), releasedLabel = "已公布"))
    }

    @Test
    fun missingExpectationHasNoSurprise() {
        // Neither consensus nor forecast means there is nothing to compare the actual against.
        assertNull(event(actual = "3.2", consensus = null, unit = "%").copy(forecast = null).surprise())
        assertNull(event(actual = null, consensus = "2.9", unit = "%").surprise())
    }

    @Test
    fun forecastFeedsSurpriseWhenNoSeparateConsensusIsPublished() {
        // Calendar sources expose a single market expectation; it must still yield a surprise,
        // otherwise every release collapses into a neutral signal.
        val surprise = event(actual = "3.2", consensus = null, unit = "%").surprise()
        assertEquals("0.2", surprise?.stripTrailingZeros()?.toPlainString())
    }

    @Test
    fun eventNameFollowsChineseScriptAndFallsBackToEnglish() {
        val event = event(actual = null, consensus = null, unit = null).copy(
            eventZhCn = "消费者价格指数同比",
            eventZhTw = "消費者價格指數同比",
        )
        assertEquals("CPI YoY", event.localizedName(Locale.ENGLISH))
        assertEquals("消费者价格指数同比", event.localizedName(Locale.SIMPLIFIED_CHINESE))
        assertEquals("消費者價格指數同比", event.localizedName(Locale.TRADITIONAL_CHINESE))
        assertEquals("CPI YoY", event.copy(eventZhCn = null, eventZhTw = null).localizedName(Locale.SIMPLIFIED_CHINESE))
    }

    private fun event(actual: String?, consensus: String?, unit: String?) = EconomicEvent(
        id = 1,
        provider = "test",
        providerId = "test-1",
        releaseGroupId = null,
        country = "United States",
        currency = "USD",
        category = "inflation",
        event = "CPI YoY",
        eventTime = "2026-09-07T01:01:01Z",
        importance = 3,
        actual = actual,
        previous = "2.8",
        consensus = consensus,
        forecast = "3.0",
        unit = unit,
        status = "released",
    )
}

