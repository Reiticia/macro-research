package com.macroresearch.ui.market

import com.macroresearch.data.model.LiveMarketQuote
import com.macroresearch.data.model.MarketQuotesResponse
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

class QuotePresentationTest {
    private fun quote(symbol: String, price: Double = 100.0) = LiveMarketQuote(
        symbol, "2026-11-13T10:00:00Z", price, "fixture", 1.0, null, null, "open", false,
    )

    @Test fun failedRefreshKeepsTimestampAndPriceButNotFreshness() {
        val original = MarketQuotesResponse(listOf(quote("gold")), emptyList())
        val stale = original.asStale().quotes.single()
        assertTrue(stale.stale)
        assertEquals(original.quotes.single().timestamp, stale.timestamp)
        assertEquals(100.0, stale.price, 0.0)
        assertFalse(original.quotes.single().stale)
    }

    @Test fun partialRefreshRetainsMissingSymbolsWithoutDuplicatingSuccessfulOnes() {
        val previous = MarketQuotesResponse(listOf(quote("gold"), quote("dxy")), emptyList())
        val result = mergeQuoteRefresh(previous, MarketQuotesResponse(listOf(quote("gold", 101.0)), listOf("dxy")))
        assertEquals(2, result.quotes.size)
        assertEquals(101.0, result.quotes.first().price, 0.0)
        assertFalse(result.quotes.first().stale)
        assertTrue(result.quotes.last().stale)
        assertEquals(listOf("dxy"), result.unavailable)
    }

    @Test fun emptyInitialResponseDoesNotInventQuotes() {
        assertTrue(mergeQuoteRefresh(null, MarketQuotesResponse(emptyList(), listOf("gold"))).quotes.isEmpty())
    }

    @Test fun quoteTimeUsesObservationTimezoneAndRejectsInvalidValues() {
        assertNull(quoteTimeLabel("invalid", Locale.US))
        val utc = quoteTimeLabel("2026-11-13T10:00:00Z", Locale.US, ZoneId.of("UTC"))
        val local = quoteTimeLabel("2026-11-13T10:00:00Z", Locale.US, ZoneId.of("Asia/Shanghai"))
        assertNotNull(utc)
        assertNotEquals(utc, local)
    }

    @Test fun treasuryYieldsFormatAsPercentAndBasisPointsWhilePricesKeepPercentMove() {
        val previousYield = 4.050
        val currentYield = 4.123
        val yieldChangePercent = (currentYield - previousYield) / previousYield * 100.0
        val us10y = LiveMarketQuote(
            "us10y", "2026-11-13T10:00:00Z", currentYield, "cnbc", yieldChangePercent, 4.13, 4.01, "open", false,
        )
        assertEquals("4.123%", formatMarketQuoteValue("us10y", us10y.price, Locale.US))
        assertEquals("4.123%", formatMarketQuoteValue("US2Y", us10y.price, Locale.US))
        assertEquals("+7.3bp", formatMarketQuoteChange(us10y, Locale.US, "bp"))
        assertEquals("+7.3基点", formatMarketQuoteChange(us10y, Locale.SIMPLIFIED_CHINESE, "基点"))

        val downYield = us10y.copy(symbol = "us2y", price = 4.859, changePercent = (4.859 - 4.864) / 4.864 * 100.0)
        assertEquals("-0.5bp", formatMarketQuoteChange(downYield, Locale.US, "bp"))

        val gold = quote("gold", 3900.07).copy(changePercent = -1.98)
        assertEquals("3,900.07", formatMarketQuoteValue("gold", gold.price, Locale.US))
        assertEquals("-1.98%", formatMarketQuoteChange(gold, Locale.US, "bp"))
        assertNull(formatMarketQuoteChange(gold.copy(changePercent = null), Locale.US, "bp"))
        assertNull(formatMarketQuoteChange(us10y.copy(changePercent = -100.0), Locale.US, "bp"))
    }
}
