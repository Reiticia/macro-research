package com.macroresearch.ui.calendar

import com.macroresearch.data.CalendarDisplayMode
import com.macroresearch.data.model.EARNINGS_ZONE
import com.macroresearch.data.model.EarningsCalendar
import com.macroresearch.data.model.EconomicEvent
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

enum class CalendarContent { ALL, EARNINGS, ECONOMIC }
enum class CalendarPresentation { WEEK, MONTH }

internal fun contentForDisplay(mode: CalendarDisplayMode, earningsAvailable: Boolean): CalendarContent = when (mode) {
    CalendarDisplayMode.ALL -> if (earningsAvailable) CalendarContent.ALL else CalendarContent.ECONOMIC
    CalendarDisplayMode.EARNINGS -> CalendarContent.EARNINGS
    CalendarDisplayMode.ECONOMIC -> CalendarContent.ECONOMIC
}

data class MonthEarningsDay(
    val calendar: EarningsCalendar? = null,
    val error: String? = null,
    val loaded: Boolean = false,
)

data class MonthCalendarState(
    val month: YearMonth = YearMonth.now(EARNINGS_ZONE),
    val selectedDate: LocalDate = LocalDate.now(EARNINGS_ZONE),
    val weekDate: LocalDate = selectedDate,
    val presentation: CalendarPresentation = CalendarPresentation.WEEK,
    val content: CalendarContent = CalendarContent.ALL,
    val displayMode: CalendarDisplayMode = CalendarDisplayMode.ALL,
    val economic: List<EconomicEvent> = emptyList(),
    val earnings: Map<LocalDate, MonthEarningsDay> = emptyMap(),
    val countries: Set<String> = emptySet(),
    val importance: Set<Int> = setOf(1, 2, 3),
    val economicLoading: Boolean = false,
    val economicLoaded: Boolean = false,
    val economicFailed: Boolean = false,
    val earningsLoading: Boolean = false,
    val earningsAvailable: Boolean = true,
) {
    val visibleDates get() = if (presentation == CalendarPresentation.MONTH) monthDates(month) else weekDates(weekDate)
    val showEconomic get() = displayMode != CalendarDisplayMode.EARNINGS && content != CalendarContent.EARNINGS
    val showEarnings get() = displayMode != CalendarDisplayMode.ECONOMIC && content != CalendarContent.ECONOMIC && earningsAvailable
    val filteredEconomic get() = economic.filter { it.country in countries && it.importance in importance }
}

/** Monday-first date strip matching the original calendar; only this week is loaded by default. */
internal fun weekDates(anchor: LocalDate): List<LocalDate> {
    val monday = anchor.minusDays((anchor.dayOfWeek.value - 1).toLong())
    return List(7) { monday.plusDays(it.toLong()) }
}

/** Sunday-first, six complete weeks; spillover dates remain real selectable dates. */
internal fun monthDates(month: YearMonth): List<LocalDate> {
    val first = month.atDay(1)
    val start = first.minusDays((first.dayOfWeek.value % 7).toLong())
    return List(42) { start.plusDays(it.toLong()) }
}

internal fun supportsEarnings(date: LocalDate, today: LocalDate): Boolean =
    date >= today.minusDays(120) && date <= today.plusDays(60)

/** Economic instants use device dates; earnings stay on the upstream New York market date. */
internal fun economicByDate(events: List<EconomicEvent>, zone: ZoneId): Map<LocalDate, List<EconomicEvent>> =
    events.mapNotNull { event ->
        runCatching { Instant.parse(event.eventTime).atZone(zone).toLocalDate() to event }.getOrNull()
    }.groupBy({ it.first }, { it.second }).mapValues { (_, rows) -> rows.sortedWith(
        compareBy<EconomicEvent> { it.eventTime }.thenByDescending { it.importance },
    ) }
