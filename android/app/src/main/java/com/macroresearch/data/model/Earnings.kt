package com.macroresearch.data.model

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

val EARNINGS_ZONE: ZoneId = ZoneId.of("America/New_York")

/** A market-date calendar, NOT a list of exact release instants. Decimal amounts are strings. */
data class EarningsCalendar(
    val date: String,
    val timezone: String = "America/New_York",
    val updatedAt: String,
    val nasdaqUpdatedAt: String? = null,
    val tradingViewUpdatedAt: String? = null,
    val stale: Boolean = false,
    val warnings: List<String> = emptyList(),
    val events: List<EarningsEvent> = emptyList(),
)

data class EarningsEvent(
    val symbol: String,
    val name: String,
    val source: String,
    val session: String = "unknown",
    val fiscalQuarterEnding: String? = null,
    val epsForecast: String? = null,
    val analystCount: Int? = null,
    val marketCap: String? = null,
    val actualEps: String? = null,
    val surprisePercent: String? = null,
    val lastYearEps: String? = null,
    val tradingView: TradingViewForecast? = null,
)

/** Kept separate: date agreement alone cannot prove fiscal-period / adjusted-EPS alignment. */
data class TradingViewForecast(
    val ticker: String,
    val nextDate: String? = null,
    val nextEpsForecast: String? = null,
    val nextRevenueForecast: String? = null,
    val quoteCurrency: String? = null,
    val dateConflict: Boolean = false,
)

fun earningsTtl(date: LocalDate, now: Instant): Duration {
    val days = java.time.temporal.ChronoUnit.DAYS.between(now.atZone(EARNINGS_ZONE).toLocalDate(), date)
    return Duration.ofHours(when (days) { in -7..0 -> 1; in 1..7 -> 6; else -> 24 })
}

fun filterEarnings(events: List<EarningsEvent>, search: String, session: String?): List<EarningsEvent> {
    val query = search.trim()
    return events.filter { (session == null || it.session == session) &&
        (query.isEmpty() || it.symbol.contains(query, true) || it.name.contains(query, true)) }
}
