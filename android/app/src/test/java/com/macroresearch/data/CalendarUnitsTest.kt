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
        assertEquals("-246B (unit unconfirmed)", named("Current Account").value("-246000000000"))
        assertEquals("\$-246B", named("Explicit Dollar Amount").value("-246000000000"))
        assertEquals("\$-246B", named("Explicit Dollar Amount").copy(country = "Canada", currency = "CAD").value("-246000000000"))
        assertEquals("197 (unit unconfirmed)", named("Unknown Scale").value("197"))
        assertEquals(ReleaseImpactStatus.DIRECTIONAL, ReleaseImpactEvaluator.evaluate(claims).status)
        assertEquals("-4000", ReleaseImpactEvaluator.evaluate(claims).surprise!!.stripTrailingZeros().toPlainString())
    }
}
