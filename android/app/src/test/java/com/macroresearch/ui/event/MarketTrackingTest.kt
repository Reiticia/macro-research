package com.macroresearch.ui.event

import com.macroresearch.data.model.MarketReaction
import com.macroresearch.data.model.MarketSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketTrackingTest {
    private fun snapshot(symbol: String) = MarketSnapshot(
        id = 1L,
        eventId = 7L,
        symbol = symbol,
        timestamp = "2026-09-18T12:00:00Z",
        price = 100.0,
        open = null,
        high = null,
        low = null,
        close = 100.0,
        volume = null,
    )

    private fun reaction(symbol: String) = MarketReaction(
        eventId = 7L,
        symbol = symbol,
        baselinePrice = 99.5,
        reactionUnit = "percent",
        change1m = 0.1,
        change5m = 0.2,
        change15m = null,
        change30m = null,
        change60m = null,
    )

    @Test
    fun liveStatesKeepTheFullPlaceholderList() {
        for (status in listOf("scheduled", "released", "collecting_market_data", "analyzing")) {
            assertEquals(
                MARKET_TRACKING_SYMBOLS,
                visibleMarketSymbols(status, emptyList(), emptyList()),
            )
        }
    }

    @Test
    fun terminalStatesListOnlySymbolsWithData() {
        for (status in listOf("completed", "historical", "data_unavailable", "timeout")) {
            assertEquals(
                listOf("gold", "us10y"),
                visibleMarketSymbols(status, listOf(snapshot("gold")), listOf(reaction("us10y"))),
            )
        }
    }

    @Test
    fun terminalStateWithoutAnyDataProducesNoRows() {
        assertTrue(visibleMarketSymbols("completed", emptyList(), emptyList()).isEmpty())
        assertTrue(visibleMarketSymbols("historical", emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun aReactionWithoutSnapshotsKeepsItsRow() {
        val rows = visibleMarketSymbols("historical", emptyList(), listOf(reaction("dxy")))
        assertEquals(listOf("dxy"), rows)
    }

    @Test
    fun rowsFollowTheTrackedSymbolOrder() {
        val rows = visibleMarketSymbols(
            "completed",
            listOf(snapshot("bitcoin"), snapshot("gold")),
            emptyList(),
        )
        assertEquals(listOf("gold", "bitcoin"), rows)
    }
}
