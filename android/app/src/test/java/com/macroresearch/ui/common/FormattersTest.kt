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
    fun invalidConsensusFallsBackAndExplicitScaleRemainsVisible() {
        val event = event(actual = "250", consensus = " N/A ", unit = "K").copy(forecast = "200")
        assertEquals("200", event.marketExpectation())
        assertEquals("50", event.surprise()!!.toPlainString())
        assertEquals("250 K", event.value(event.actual))
        val oil = event.copy(provider = "forex_factory", event = "Crude Oil Inventories", unit = "currency", actual = "-2000000")
        assertEquals("-2M barrels", oil.value(oil.actual))
    }

    @Test
    fun currencySymbolIsPrefixedRatherThanRenderedAsAUnitSuffix() {
        val currentAccount = event(actual = "-246", consensus = "-255", unit = "$")
            .copy(event = "Current Account")
        assertEquals("\$-246 (scale unconfirmed)", currentAccount.value(currentAccount.actual))
    }

    @Test
    fun missingUnitsDoNotInventAScaleFromTheEventTitle() {
        val source = event(actual = "197", consensus = "201", unit = null)
            .copy(event = "Initial Jobless Claims")
        assertEquals("197 (unit unconfirmed)", source.value(source.actual))
        assertEquals("197 (单位未确认)", source.value(source.actual, Locale.SIMPLIFIED_CHINESE, unknownUnitLabel = "单位未确认"))
        assertEquals("197 (unit unconfirmed)", source.copy(unit = " ").value(source.actual))
    }

    @Test
    fun normalizedCountsHaveDimensionsButNoFabricatedCurrency() {
        val claims = event(actual = "197000", consensus = "201000", unit = "number")
            .copy(event = "Initial Jobless Claims", provider = "forex_factory")
        assertEquals("197K people", claims.value(claims.actual))
        assertEquals("19.7万 人", claims.value(claims.actual, Locale.SIMPLIFIED_CHINESE, peopleLabel = "人"))
        val oil = claims.copy(event = "Crude Oil Inventories", actual = "-2000000")
        assertEquals("-2M barrels", oil.value(oil.actual))
        val monetary = claims.copy(event = "Current Account", actual = "-246000000000")
        assertEquals("-246B (unit unconfirmed)", monetary.value(monetary.actual))
        assertEquals("-246B (unit unconfirmed)", monetary.copy(unit = "currency").value(monetary.actual))
        assertEquals("-246B USD", monetary.copy(unit = "USD").value(monetary.actual))
        assertEquals("-246B USD", monetary.copy(provider = "trading_view", unit = "B USD", actual = "-246").value("-246"))
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

