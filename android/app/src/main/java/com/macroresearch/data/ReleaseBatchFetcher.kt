package com.macroresearch.data

import com.macroresearch.data.model.EconomicEvent
import com.macroresearch.data.remote.EconomicCalendarClient
import com.macroresearch.data.remote.CalendarFetchResult
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** One provider request per local date, or one backend sync plus one calendar read per UTC date. */
internal object ReleaseBatchFetcher {
    suspend fun fetch(events: List<EconomicEvent>, source: DataSource, zone: ZoneId = ZoneId.systemDefault()): CalendarFetchResult {
        require(events.isNotEmpty())
        val groupingZone = if (source.mode == DataSourceMode.BACKEND) ZoneOffset.UTC else zone
        val day = Instant.parse(events.first().eventTime).atZone(groupingZone).toLocalDate()
        require(events.all { Instant.parse(it.eventTime).atZone(groupingZone).toLocalDate() == day })
        if (source.mode == DataSourceMode.DIRECT) {
            return source.calendar(day, day, EconomicCalendarClient.codesFor(events.map { it.country }.distinct()))
        }
        val detail = source.refreshRelease(events.first().id)
        // DataSource.calendar uses device-local dates: cover the UTC day even east/west of UTC.
        val calendar = source.calendar(day.minusDays(1), day.plusDays(1))
        val rows = (calendar.events.filter { !it.actual.isNullOrBlank() } + listOfNotNull(detail?.event) + calendar.events).filter {
            runCatching { Instant.parse(it.eventTime).atZone(ZoneOffset.UTC).toLocalDate() == day }.getOrDefault(false)
        }.distinctBy { it.id }
        return CalendarFetchResult(rows, calendar.warning)
    }
}
