package com.macroresearch.data.local

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Two integer columns preserve ordering down to nanoseconds, without SQLite date rounding. */
internal fun parseEventTimestamp(value: String): Instant? = runCatching { Instant.parse(value) }.getOrNull()

internal fun localDayBounds(start: LocalDate, end: LocalDate, zone: ZoneId): Pair<Instant, Instant> {
    require(!end.isBefore(start))
    return start.atStartOfDay(zone).toInstant() to end.plusDays(1).atStartOfDay(zone).toInstant()
}
