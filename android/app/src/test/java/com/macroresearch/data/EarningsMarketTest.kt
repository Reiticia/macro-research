package com.macroresearch.data

import com.google.gson.JsonParser
import com.macroresearch.data.model.*
import org.junit.Assert.*
import org.junit.Test

class EarningsMarketTest {
    private val gson = com.google.gson.GsonBuilder().serializeNulls().create()
    private fun decode(market: EarningsMarket = stockMarket()) = decodeEarningsMarket(gson.toJson(market), stockDate, "AAPL")
    private fun reject(change: (com.google.gson.JsonObject) -> Unit) {
        val json = JsonParser.parseString(gson.toJson(stockMarket())).asJsonObject
        change(json)
        assertThrows(Exception::class.java) { decodeEarningsMarket(json.toString(), stockDate, "AAPL") }
    }

    @Test fun decodesTheBackendStockContractAndKeepsCoverageGaps() {
        val value = decode()
        assertEquals(3, value.snapshots.size)
        assertEquals(3, value.coverage.missingMinuteCount)
        assertFalse(value.releaseTimeExact)
        assertEquals("USD", value.tracking?.currency)
    }

    @Test fun changesUseFirstObservedOpenNotTheFirstMinuteClose() {
        val market = decode()
        val latest = market.pricePoints().last()
        assertEquals(106.0, latest.price, 0.0)
        assertEquals(6.0, market.changeFromFirst(latest)!!, .00001)
    }

    @Test fun aCorrectionAtTheSameQuoteTimeShowsTheLatestCloseAndPreservesTheFirstSampleBaseline() {
        val row = stockSnapshot(0).let { it.copy(lastQuoteAt = it.firstQuoteAt) }
        val market = decode(stockMarket(snapshots = listOf(row)))
        assertEquals(101.0, market.pricePoints().single().price, .0)
        assertEquals(100.0, market.firstPricePoint()!!.price, .0)
        assertEquals(1.0, market.changeFromFirst(market.pricePoints().single())!!, .00001)
    }

    @Test fun windowsUseOnlyPastPricesAndNeverAFutureClose() {
        val market = decode()
        val now = stockStart.plusSeconds(1200)
        assertEquals(101.0, market.priceAt(1, now)!!.price, .0)
        assertEquals(106.0, market.priceAt(5, now)!!.price, .0)
        assertNull(market.priceAt(0, now)) // first quote was five seconds after detection
        assertNull(market.priceAt(15, now)) // no nearby observation
        assertNull(market.priceAt(30, now)) // the target is still in the future
        assertNull(market.priceAt(5, stockStart.plusSeconds(299)))
    }

    @Test fun missingWindowsDoNotReuseAnOldPrice() {
        val market = decode(stockMarket(snapshots = listOf(stockSnapshot(0))))
        assertNull(market.priceAt(5, stockStart.plusSeconds(600)))
        assertNull(market.changeFromFirst(null))
    }

    @Test fun completedDoesNotMeanCompleteCoverage() {
        val market = decode(stockMarket(ended = true))
        assertTrue(market.coverage.windowEnded)
        assertEquals(57, market.coverage.missingMinuteCount)
        assertNull(market.priceAt(60, stockStart.plusSeconds(4000)))
    }

    @Test fun aMissingTrackingWindowIsNotSynthesized() {
        val market = stockMarket().copy(observationStatus = "already_reported", tracking = null, snapshots = emptyList(),
            coverage = EarningsStockCoverage(0, 0, 0, false))
        assertNull(decode(market).priceAt(5, stockStart.plusSeconds(600)))
        assertTrue(decode(market).pricePoints().isEmpty())
    }

    @Test fun rejectsWrongDateOrSymbol() {
        reject { it.addProperty("date", "2026-11-02") }
        reject { it.addProperty("symbol", "NVDA") }
    }

    @Test fun rejectsMisrepresentedReleaseTime() {
        reject { it.addProperty("releaseTimeExact", true) }
        reject { it.addProperty("timeBasis", "company_announcement") }
        reject { it.remove("releaseTimeExact") }
    }

    @Test fun rejectsMalformedCoverageAndMissingPayload() {
        reject { it.getAsJsonObject("coverage").addProperty("minuteCount", 0) }
        reject { it.getAsJsonObject("coverage").addProperty("missingMinuteCount", -1) }
        reject { it.getAsJsonObject("coverage").remove("windowEnded") }
        reject { it.remove("snapshots") }
        reject { it.remove("tracking") }
    }

    @Test fun rejectsPricesWithoutAWindowAndWindowsThatAreNotSixtyMinutes() {
        reject { it.add("tracking", com.google.gson.JsonNull.INSTANCE) }
        reject { it.getAsJsonObject("tracking").addProperty("endsAt", stockStart.plusSeconds(7200).toString()) }
    }

    @Test fun rejectsPreDetectionAndOutOfWindowSamples() {
        reject { it.getAsJsonArray("snapshots")[0].asJsonObject.addProperty("firstQuoteAt", stockStart.minusSeconds(1).toString()) }
        reject { it.getAsJsonArray("snapshots")[0].asJsonObject.addProperty("lastQuoteAt", stockStart.plusSeconds(3600).toString()) }
    }

    @Test fun rejectsInvalidPricesProvidersAndTimeBases() {
        reject { it.getAsJsonArray("snapshots")[0].asJsonObject.addProperty("price", 0) }
        reject { it.getAsJsonArray("snapshots")[0].asJsonObject.addProperty("high", 90) }
        reject { it.getAsJsonArray("snapshots")[0].asJsonObject.addProperty("provider", "unknown") }
        reject { it.getAsJsonArray("snapshots")[0].asJsonObject.addProperty("timestampBasis", "local_fetch_time") }
    }

    @Test fun acceptsClosedMinuteBarAndMixedTimestampLabels() {
        val market = stockMarket()
        val rows = market.snapshots.mapIndexed { index, row -> row.copy(timestampBasis = if (index == 0) "minute_bar_close" else "mixed") }
        assertEquals("minute_bar_close", decode(market.copy(snapshots = rows)).snapshots.first().timestampBasis)
    }
}
