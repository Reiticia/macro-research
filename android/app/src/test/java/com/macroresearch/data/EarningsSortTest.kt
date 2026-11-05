package com.macroresearch.data

import com.macroresearch.data.model.*
import org.junit.Assert.*
import org.junit.Test

class EarningsSortTest {
    private fun event(symbol: String, cap: String? = null, session: String = "unknown") =
        EarningsEvent(symbol, "Company $symbol", "nasdaq", session, marketCap = cap)

    private fun sorted(events: List<EarningsEvent>, field: EarningsSortField,
        direction: EarningsSortDirection = EarningsSortDirection.ASCENDING) =
        sortEarnings(events, EarningsSort(field, direction)).map { it.symbol }

    @Test fun defaultAndInvalidStoredValuesUseSymbolAscending() {
        assertEquals(EarningsSort(), EarningsSort(
            EarningsSortField.fromStored(null), EarningsSortDirection.fromStored(null)))
        assertEquals(EarningsSort(), EarningsSort(
            EarningsSortField.fromStored("invalid"), EarningsSortDirection.fromStored("invalid")))
        EarningsSortField.entries.forEach { assertEquals(it, EarningsSortField.fromStored(it.name)) }
        EarningsSortDirection.entries.forEach { assertEquals(it, EarningsSortDirection.fromStored(it.name)) }
    }

    @Test fun symbolOrderWorksInBothDirectionsForEitherProvider() {
        val events = listOf(event("ZZZ"), event("BRK.B"), event("AAPL").copy(source = "tradingview"))
        assertEquals(listOf("AAPL", "BRK.B", "ZZZ"), sorted(events, EarningsSortField.SYMBOL))
        assertEquals(listOf("ZZZ", "BRK.B", "AAPL"), sorted(events, EarningsSortField.SYMBOL, EarningsSortDirection.DESCENDING))
    }

    @Test fun marketCapsAreComparedNumericallyWithFullDecimalPrecision() {
        val events = listOf(event("SMALL", "9"), event("BIG", "100000000000000000000.01"),
            event("MEDIUM", "100"), event("BIGGER", "100000000000000000000.02"), event("TINY", "0.5"))
        val ascending = listOf("TINY", "SMALL", "MEDIUM", "BIG", "BIGGER")
        assertEquals(ascending, sorted(events, EarningsSortField.MARKET_CAP))
        assertEquals(ascending.reversed(), sorted(events, EarningsSortField.MARKET_CAP, EarningsSortDirection.DESCENDING))
    }

    @Test fun missingInvalidAndNonPositiveMarketCapsStayLastInBothDirections() {
        val events = listOf(event("B", "N/A"), event("C", "0"), event("D", "-1"), event("E", "NaN"),
            event("A"), event("SMALL", "1"), event("BIG", "100"))
        assertEquals(listOf("SMALL", "BIG", "A", "B", "C", "D", "E"), sorted(events, EarningsSortField.MARKET_CAP))
        assertEquals(listOf("BIG", "SMALL", "A", "B", "C", "D", "E"),
            sorted(events, EarningsSortField.MARKET_CAP, EarningsSortDirection.DESCENDING))
    }

    @Test fun releaseTimeUsesSessionsAndKeepsUnknownTimesLast() {
        val events = listOf(event("UNKNOWN"), event("POST", session = "after_hours"),
            event("PRE", session = "pre_market"), event("INVALID", session = "unsupported"))
        assertEquals(listOf("PRE", "POST", "INVALID", "UNKNOWN"), sorted(events, EarningsSortField.RELEASE_TIME))
        assertEquals(listOf("POST", "PRE", "INVALID", "UNKNOWN"),
            sorted(events, EarningsSortField.RELEASE_TIME, EarningsSortDirection.DESCENDING))
    }

    @Test fun equalValuesAlwaysUseSymbolAscendingIncludingTradingViewFallback() {
        val events = listOf(event("Z", "10.00", "pre_market"), event("A", "10", "pre_market"))
        for (direction in EarningsSortDirection.entries) {
            for (field in listOf(EarningsSortField.MARKET_CAP, EarningsSortField.RELEASE_TIME)) {
                assertEquals(listOf("A", "Z"), sorted(events, field, direction))
                assertEquals(listOf("A", "Z"), sorted(events.map {
                    it.copy(source = "tradingview", marketCap = null, session = "unknown")
                }, field, direction))
            }
        }
    }

    @Test fun filteringAndSortingDoNotMutateTheSourceSnapshot() {
        val events = listOf(event("Z", "9", "pre_market"), event("A", "100", "pre_market"),
            event("B", "20", "after_hours"))
        val sort = EarningsSort(EarningsSortField.MARKET_CAP, EarningsSortDirection.DESCENDING)
        val filtered = filterEarnings(events, "company", "pre_market")
        assertEquals(listOf("A", "Z"), sortEarnings(filtered, sort).map { it.symbol })
        assertEquals(listOf("Z", "A", "B"), events.map { it.symbol })
        assertEquals(sortEarnings(filtered, sort), filterEarnings(sortEarnings(events, sort), "company", "pre_market"))
    }
}
