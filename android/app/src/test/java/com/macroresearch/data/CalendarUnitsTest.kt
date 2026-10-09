package com.macroresearch.data

import com.google.gson.JsonParser
import com.macroresearch.data.remote.EconomicCalendarClient
import com.macroresearch.ui.common.value
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** Shared provider samples are also verified by Rust. No event-title magnitude assumptions. */
class CalendarUnitsTest {
    @Test fun tradingViewKeepsExplicitScaleWithoutUsingRawValuesTwice() {
        val raw = javaClass.getResourceAsStream("/calendar-units/tradingview.json")!!
            .bufferedReader().use { it.readText() }
        val events = EconomicCalendarClient(OkHttpClient()).parseTradingView(raw)
        fun named(name: String) = events.single { it.event == name }
        val claims = named("Initial Jobless Claims")
        assertEquals("197", claims.actual)
        assertEquals("201", claims.consensus)
        assertEquals("K", claims.unit)
        assertEquals("197K people", claims.value(claims.actual))
        assertEquals("-4000", ReleaseImpactEvaluator.evaluate(claims).surprise!!.toPlainString())
        val oil = named("EIA Crude Oil Stocks Change")
        assertEquals("M", oil.unit)
        assertEquals("2.969M barrels", oil.value(oil.actual))
        assertEquals("3569000", ReleaseImpactEvaluator.evaluate(oil).surprise!!.stripTrailingZeros().toPlainString())
        val account = named("Current Account")
        assertEquals("B $", account.unit)
        assertEquals("-246", account.actual)
        assertEquals("\$-246B", account.value(account.actual))
        assertEquals("54.6 index points", named("ISM Manufacturing PMI").value("54.6"))
        assertEquals("0.684 M", named("New Home Sales").value("0.684"))
        assertEquals("1.2%", named("Retail Sales MoM").value("1.2"))
    }

    @Test fun unknownTickerOrAbsentScaleIsNotInferredFromACommonTitle() {
        val raw = """{"status":"ok","result":[{"id":"1","title":"Initial Jobless Claims",
            "country":"US","date":"2026-09-24T12:30:00Z","actual":197,"unit":"None","scale":"None"},
            {"id":"2","title":"ISM Manufacturing PMI","country":"US","ticker":"OTHER",
            "date":"2026-09-24T12:30:00Z","actual":52}]}"""
        val events = EconomicCalendarClient(OkHttpClient()).parseTradingView(raw)
        assertTrue(events.all { it.unit == null })
    }

    @Test fun formattedSourceValuesAreExpandedExactlyOnceAndNotAssumedToBeDollars() {
        val raw = javaClass.getResourceAsStream("/calendar-units/forex-factory.json")!!
            .bufferedReader().use { it.readText() }
        val day = LocalDate.of(2026, 9, 24)
        val events = EconomicCalendarClient(OkHttpClient()).parseForexFactory(
            raw, Instant.parse("2026-09-24T14:00:00Z"), day, day,
        )
        val rows = JsonParser.parseString(raw).asJsonArray
        assertEquals(rows.size(), events.size)
        rows.forEach { element ->
            val row = element.asJsonObject
            val event = events.single { it.event == row["title"].asString }
            assertEquals(event.event, row["expectedActual"].asString, event.actual)
            assertEquals(event.event, row["expectedUnit"].takeUnless { it.isJsonNull }?.asString, event.unit)
        }
        fun named(name: String) = events.single { it.event == name }
        val claims = named("Initial Jobless Claims")
        assertEquals("197K people", claims.value(claims.actual))
        assertEquals("-2M barrels", named("Crude Oil Inventories").value("-2000000"))
        assertEquals("-246B", named("Current Account").value("-246000000000"))
        assertEquals("\$-246B", named("Explicit Dollar Amount").value("-246000000000"))
        assertEquals("\$-246B", named("Explicit Dollar Amount").copy(country = "Canada", currency = "CAD").value("-246000000000"))
        assertEquals("197", named("Unknown Scale").value("197"))
        assertEquals(ReleaseImpactStatus.DIRECTIONAL, ReleaseImpactEvaluator.evaluate(claims).status)
        assertEquals("-4000", ReleaseImpactEvaluator.evaluate(claims).surprise!!.stripTrailingZeros().toPlainString())
    }
}
