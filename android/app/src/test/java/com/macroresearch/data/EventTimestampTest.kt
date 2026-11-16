package com.macroresearch.data

import com.macroresearch.data.local.localDayBounds
import com.macroresearch.data.local.parseEventTimestamp
import org.junit.Assert.*
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class EventTimestampTest {
    @Test fun parsedKeysPreserveNanoPrecisionAndOffsetEquivalence() {
        val base = parseEventTimestamp("2026-11-13T00:00:00Z")!!
        val fractional = parseEventTimestamp("2026-11-13T00:00:00.000000001Z")!!
        assertEquals(base.epochSecond, fractional.epochSecond)
        assertEquals(1, fractional.nano)
        assertTrue(base < fractional)
        assertEquals(base, parseEventTimestamp("2026-11-13T08:00:00+08:00"))
        assertNull(parseEventTimestamp("invalid"))
        assertEquals(-1L, parseEventTimestamp("1969-12-31T23:59:59.999999999Z")!!.epochSecond)
    }

    @Test fun halfOpenDaysIncludeLastSecondAndNanosecondButNotNextMidnight() {
        val date = LocalDate.of(2026, 11, 13)
        val (from, until) = localDayBounds(date, date, ZoneId.of("UTC"))
        for (raw in listOf("2026-11-13T00:00:00Z", "2026-11-13T00:00:00.500Z", "2026-11-13T23:59:59Z", "2026-11-13T23:59:59.999999999Z")) {
            val time = Instant.parse(raw)
            assertTrue(time >= from && time < until)
        }
        assertEquals(Instant.parse("2026-11-14T00:00:00Z"), until)
    }

    @Test fun daylightSavingDaysAreNotAlwaysTwentyFourHours() {
        val zone = ZoneId.of("America/New_York")
        for ((raw, hours) in listOf("2026-03-08" to 23L, "2026-11-01" to 25L)) {
            val date = LocalDate.parse(raw)
            val (from, until) = localDayBounds(date, date, zone)
            assertEquals(hours, Duration.between(from, until).toHours())
        }
    }
}
