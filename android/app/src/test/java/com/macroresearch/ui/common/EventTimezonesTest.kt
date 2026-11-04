package com.macroresearch.ui.common

import com.macroresearch.data.model.EconomicEvent
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class EventTimezonesTest {
    private fun event(time: String = "2026-11-04T13:30:00Z", country: String = "United States") = EconomicEvent(
        1, "test", "1", null, country, "USD", "inflation", "CPI",
        eventTime = time, importance = 3, actual = null, previous = null, consensus = null,
        forecast = null, unit = "%", status = "watching",
    )
    private val shanghai = ZoneId.of("Asia/Shanghai")

    @Test fun sameInstantHasDeviceAndRegionalClocksWithExplicitDatesAndOffsets() {
        val times = event().displayTimes(shanghai)
        assertEquals("2026-11-04 21:30 (UTC+08:00)", eventClockText(times.deviceTime!!, true, Locale.ENGLISH))
        assertEquals("2026-11-04 08:30 (UTC-05:00)", eventClockText(times.regionTime!!, true, Locale.ENGLISH))
        assertEquals(times.deviceTime.toInstant(), times.regionTime.toInstant())
        assertEquals("21:30 (UTC+08:00)", eventClockText(times.deviceTime, false, Locale.ENGLISH))
    }

    @Test fun supportedCountryNamesAndProviderCodesResolveToTheSameExplicitZones() {
        val aliases = mapOf(
            "America/New_York" to listOf("United States", "us", " USA ", "United States of America"),
            "Europe/Berlin" to listOf("Euro Area", "EU", "Eurozone", "Euro Zone", "European Union"),
            "Asia/Shanghai" to listOf("China", "CN", "CHN"),
            "Asia/Tokyo" to listOf("Japan", "JP"),
            "Europe/London" to listOf("United Kingdom", "UK", "GB"),
        )
        aliases.forEach { (zone, countries) -> countries.forEach { assertEquals(it, ZoneId.of(zone), macroRegionZone(it)) } }
    }

    @Test fun usSummerAndWinterOffsetsComeFromTheEventDateNotToday() {
        val summer = event("2026-07-15T12:30:00Z").displayTimes(shanghai)
        val winter = event("2026-12-15T13:30:00Z").displayTimes(shanghai)
        assertEquals(8, summer.regionTime!!.hour)
        assertEquals(8, winter.regionTime!!.hour)
        assertEquals(-4 * 3600, summer.regionTime.offset.totalSeconds)
        assertEquals(-5 * 3600, winter.regionTime.offset.totalSeconds)
        assertEquals(8 * 3600, summer.deviceTime!!.offset.totalSeconds)
        assertEquals(8 * 3600, winter.deviceTime!!.offset.totalSeconds)
    }

    @Test fun usDstTransitionSkipsTheNonexistentHourAndDistinguishesTheRepeatedHour() {
        val before = event("2026-03-08T06:59:00Z").displayTimes(shanghai).regionTime!!
        val after = event("2026-03-08T07:00:00Z").displayTimes(shanghai).regionTime!!
        assertEquals("01:59 (UTC-05:00)", eventClockText(before, false, Locale.ENGLISH))
        assertEquals("03:00 (UTC-04:00)", eventClockText(after, false, Locale.ENGLISH))
        val first = event("2026-11-01T05:30:00Z").displayTimes(shanghai).regionTime!!
        val repeated = event("2026-11-01T06:30:00Z").displayTimes(shanghai).regionTime!!
        assertEquals("01:30 (UTC-04:00)", eventClockText(first, false, Locale.ENGLISH))
        assertEquals("01:30 (UTC-05:00)", eventClockText(repeated, false, Locale.ENGLISH))
    }

    @Test fun europeAndUsDoNotShareDstTransitionDates() {
        val us = event("2026-03-16T12:30:00Z").displayTimes(shanghai).regionTime!!
        val eu = event("2026-03-16T12:30:00Z", "Euro Area").displayTimes(shanghai).regionTime!!
        val laterEu = event("2026-03-30T12:30:00Z", "Euro Area").displayTimes(shanghai).regionTime!!
        assertEquals(-4 * 3600, us.offset.totalSeconds)
        assertEquals(3600, eu.offset.totalSeconds)
        assertEquals(7200, laterEu.offset.totalSeconds)
        val uk = event("2026-07-15T12:30:00Z", "United Kingdom").displayTimes(shanghai).regionTime!!
        assertEquals(3600, uk.offset.totalSeconds)
    }

    @Test fun regionCanBeOnThePreviousDayOrYearWithoutChangingTheDeviceDate() {
        val times = event("2026-01-01T01:00:00Z").displayTimes(shanghai)
        assertEquals(LocalDate.of(2026, 1, 1), times.deviceTime!!.toLocalDate())
        assertEquals(LocalDate.of(2025, 12, 31), times.regionTime!!.toLocalDate())
        assertEquals("2025-12-31 20:00 (UTC-05:00)", eventClockText(times.regionTime, true, Locale.ENGLISH))
    }

    @Test fun southernHemisphereAndFractionalOffsetsAreNotAssumedToMatchTheUs() {
        val summer = event("2026-01-15T00:00:00Z", "Australia").displayTimes(shanghai).regionTime!!
        val winter = event("2026-07-15T00:00:00Z", "Australia").displayTimes(shanghai).regionTime!!
        assertEquals(11 * 3600, summer.offset.totalSeconds)
        assertEquals(10 * 3600, winter.offset.totalSeconds)
        val india = event(country = "India").displayTimes(shanghai).regionTime!!
        assertTrue(eventClockText(india, true, Locale.ENGLISH).endsWith("(UTC+05:30)"))
    }

    @Test fun unknownCountryNeverGuessesFromUsdCurrencyOrTheDeviceZone() {
        for (country in listOf("World", "WW", "", "Unknown", "USD")) {
            val times = event(country = country).displayTimes(shanghai)
            assertNull(times.regionZone)
            assertNull(times.regionTime)
            assertNotNull(times.deviceTime)
        }
    }

    @Test fun dateOnlyOrMalformedTimestampsNeverProduceInventedMidnightClocks() {
        val dateOnly = event().copy(timeExact = false).displayTimes(shanghai)
        assertNull(dateOnly.deviceTime)
        assertNull(dateOnly.regionTime)
        assertNotNull(dateOnly.regionZone)
        for (invalid in listOf("", "not a date", "2026-11-04")) {
            val times = event(invalid).displayTimes(shanghai)
            assertNull(times.deviceTime)
            assertNull(times.regionTime)
        }
    }

    @Test fun identicalZonesStillExposeBothRolesAndUtcZeroIsUnambiguous() {
        val times = event().displayTimes(ZoneId.of("America/New_York"))
        assertEquals(times.deviceTime, times.regionTime)
        val utc = event().displayTimes(ZoneId.of("UTC"))
        assertEquals("13:30 (UTC+00:00)", eventClockText(utc.deviceTime!!, false, Locale.ENGLISH))
    }

    @Test fun inlinePairOmitsADuplicateDateButKeepsRegionalOffset() {
        val times = event().displayTimes(shanghai)
        assertEquals("08:30 (UTC-05:00)", times.inlineRegionClockText(Locale.ENGLISH))
        assertEquals("2026-11-04 21:30 (UTC+08:00)", eventClockText(times.deviceTime!!, true, Locale.ENGLISH))
    }

    @Test fun inlinePairKeepsDateAcrossMidnightAndYearBoundaries() {
        val times = event("2026-01-01T01:00:00Z").displayTimes(shanghai)
        assertEquals("2025-12-31 20:00 (UTC-05:00)", times.inlineRegionClockText(Locale.ENGLISH))
    }

    @Test fun inlinePairDoesNotInventUnknownOrDateOnlyRegionalTimes() {
        assertNull(event(country = "World").displayTimes(shanghai).inlineRegionClockText(Locale.ENGLISH))
        assertNull(event().copy(timeExact = false).displayTimes(shanghai).inlineRegionClockText(Locale.ENGLISH))
    }

    @Test fun defaultDeviceZoneIsReadAtCallTimeRatherThanHardCodedToChina() {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"))
            assertEquals(ZoneId.of("Pacific/Auckland"), event().displayTimes().deviceZone)
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"))
            assertEquals(ZoneId.of("Europe/London"), event().displayTimes().deviceZone)
        } finally { TimeZone.setDefault(previous) }
    }
}
