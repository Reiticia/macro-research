package com.macroresearch.ui.common.terminal

import com.macroresearch.R
import com.macroresearch.data.model.LiveMarketQuote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuotesTest {
    private fun quote(change: Double? = 0.5, stale: Boolean = false, state: String? = "open") = LiveMarketQuote(
        symbol = "gold", timestamp = "2026-10-14T12:00:00Z", price = 4128.4, provider = "biquote",
        changePercent = change, high = null, low = null, marketState = state, stale = stale,
    )

    @Test fun changeOnlyShownForLiveOpenQuotes() {
        assertTrue(quoteChangeVisible(quote()))
        assertFalse(quoteChangeVisible(quote(stale = true)))
        assertFalse(quoteChangeVisible(quote(state = "CLOSED")))
        assertFalse(quoteChangeVisible(quote(change = null)))
        assertTrue(quoteChangeVisible(quote(state = null)))
    }

    @Test fun statusLabelDistinguishesStaleClosedAndLive() {
        assertEquals(R.string.market_stale, quoteStatusLabel(quote(stale = true)))
        assertEquals(R.string.market_closed, quoteStatusLabel(quote(state = "closed")))
        assertEquals(R.string.market_closed, quoteStatusLabel(quote(state = "CLOSED")))
        assertEquals(R.string.market_live, quoteStatusLabel(quote(change = null, state = "open")))
        assertEquals(R.string.market_live, quoteStatusLabel(quote(state = null)))
    }

    @Test fun providerNamesAreBranded() {
        assertEquals("BiQuote", providerLabel("biquote"))
        assertEquals("Yahoo Finance", providerLabel("YAHOO"))
        assertEquals("Binance", providerLabel("binance"))
        assertEquals("CNBC", providerLabel("cnbc"))
    }
}
