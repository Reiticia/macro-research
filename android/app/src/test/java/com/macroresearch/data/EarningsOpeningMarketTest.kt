package com.macroresearch.data

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.macroresearch.data.model.*
import com.macroresearch.ui.earnings.shouldPollEarningsMarket
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class EarningsOpeningMarketTest {
    private val gson = GsonBuilder().serializeNulls().create()
    private val open = Instant.parse("2026-11-04T14:30:00Z")
    private fun market(scheduled: Boolean = false): EarningsMarket {
        val base = stockMarket(ended = true)
        val row = stockSnapshot(0, 200.0, 210.0)
        val shift = java.time.Duration.between(stockStart, open).seconds
        val snapshots = if (scheduled) emptyList() else listOf(row.copy(
            timestamp = Instant.parse(row.timestamp).plusSeconds(shift).toString(),
            firstQuoteAt = Instant.parse(row.firstQuoteAt).plusSeconds(shift).toString(),
            lastQuoteAt = Instant.parse(row.lastQuoteAt).plusSeconds(shift).toString(),
        ))
        return base.copy(openingSession = EarningsOpeningSession("regular_session_open",
            requireNotNull(base.tracking).copy(startsAt = open.toString(), endsAt = open.plusSeconds(3600).toString(),
                status = if (scheduled) "scheduled" else "collecting", lastSuccessAt = null),
            snapshots, EarningsStockCoverage(snapshots.size, if (scheduled) 0 else 1, 0, false)))
    }
    private fun decode(value: EarningsMarket) = decodeEarningsMarket(gson.toJson(value), stockDate, "AAPL")
    private fun reject(change: (JsonObject) -> Unit) {
        val json = JsonParser.parseString(gson.toJson(market())).asJsonObject
        change(json.getAsJsonObject("openingSession"))
        assertThrows(Exception::class.java) { decodeEarningsMarket(json.toString(), stockDate, "AAPL") }
    }

    @Test fun openingUsesItsOwnStartSamplesAndBaselineNotDetectionOrReleasePrices() {
        val value = decode(market())
        val opening = requireNotNull(value.forOpeningSession())
        assertEquals("regular_session_open", opening.timeBasis)
        assertEquals(stockStart.toString(), opening.tracking?.detectedAt)
        assertEquals(open.toString(), opening.tracking?.windowStart)
        assertEquals(200.0, opening.firstPricePoint()!!.price, 0.0)
        assertEquals(210.0, opening.priceAt(1, open.plusSeconds(60))!!.price, 0.0)
        assertEquals(5.0, opening.changeFromFirst(opening.priceAt(1, open.plusSeconds(60)))!!, .00001)
        assertNull(opening.priceAt(1, open.plusSeconds(59)))
        assertNull(opening.priceAt(5, open.plusSeconds(600)))
        assertEquals(100.0, value.firstPricePoint()!!.price, 0.0)
        assertEquals(106.0, value.priceAt(5, open)!!.price, 0.0)
    }

    @Test fun scheduledOpeningIsEmptyAndKeepsPollingAfterReleaseWindowEnds() {
        val value = decode(market(scheduled = true))
        assertTrue(value.coverage.windowEnded)
        val opening = requireNotNull(value.forOpeningSession())
        assertEquals("scheduled", opening.tracking?.status)
        assertTrue(opening.pricePoints().isEmpty())
        assertTrue(shouldPollEarningsMarket(value, stockDate, open.minusSeconds(60)))
        assertTrue(shouldPollEarningsMarket(decode(market()), stockDate, open.plusSeconds(60)))
        val finished = value.copy(openingSession = value.openingSession!!.copy(
            tracking = value.openingSession.tracking.copy(status = "completed"),
            coverage = EarningsStockCoverage(0, 60, 60, true)))
        assertFalse(shouldPollEarningsMarket(decode(finished), stockDate, open.plusSeconds(3600)))
    }

    @Test fun oldBackendWithoutOpeningSessionRemainsCompatible() {
        val json = JsonParser.parseString(gson.toJson(stockMarket(ended = true))).asJsonObject
        json.remove("openingSession")
        val result = decodeEarningsMarket(json.toString(), stockDate, "AAPL")
        assertNull(result.forOpeningSession())
        assertEquals(stockStart.toString(), result.tracking?.windowStart)
    }

    @Test fun openingMustHaveExplicitTimeOriginAndValidIndependentWindow() {
        reject { it.addProperty("timeBasis", "first_detected_actual_eps") }
        reject { it.getAsJsonObject("tracking").remove("startsAt") }
        reject { it.getAsJsonObject("tracking").addProperty("detectedAt", open.toString()) }
        reject { it.getAsJsonObject("tracking").addProperty("endsAt", open.plusSeconds(7200).toString()) }
        reject { it.getAsJsonObject("tracking").addProperty("status", "scheduled") }
        reject { it.getAsJsonObject("coverage").remove("windowEnded") }
        reject { it.getAsJsonObject("coverage").addProperty("minuteCount", 0) }
        reject { it.getAsJsonArray("snapshots")[0].asJsonObject.addProperty("firstQuoteAt", stockStart.toString()) }
        reject { it.getAsJsonArray("snapshots")[0].asJsonObject.addProperty("lastQuoteAt", open.plusSeconds(3600).toString()) }
    }
}
