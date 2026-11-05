package com.macroresearch.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.macroresearch.R
import com.macroresearch.data.BackendSettings
import com.macroresearch.data.CompanyLogoLoader
import com.macroresearch.data.model.EARNINGS_ZONE
import com.macroresearch.data.model.EarningsEvent
import com.macroresearch.data.model.sortEarnings
import com.macroresearch.ui.common.appLocale
import com.macroresearch.ui.earnings.CompanyLogo
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle

@Composable
internal fun CalendarMonthGrid(
    state: MonthCalendarState,
    source: BackendSettings,
    logos: CompanyLogoLoader?,
    onDate: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    focusRequest: Int = 0,
) {
    val dates = remember(state.month) { monthDates(state.month) }
    val weeks = rememberLazyListState()
    LaunchedEffect(state.month, state.selectedDate, focusRequest) {
        val selectedWeek = (dates.indexOf(state.selectedDate).coerceAtLeast(0) / 7)
        if (weeks.layoutInfo.visibleItemsInfo.none { it.index == selectedWeek }) {
            weeks.animateScrollToItem(selectedWeek)
        }
    }
    val economic = remember(state.economic, state.countries, state.importance) {
        economicByDate(state.filteredEconomic, ZoneId.systemDefault())
    }
    val today = LocalDate.now(EARNINGS_ZONE)
    val locale = appLocale()
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            listOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY).forEach { day ->
                Text(day.getDisplayName(TextStyle.NARROW, locale), Modifier.weight(1f),
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        BoxWithConstraints(Modifier.weight(1f)) {
            val rowHeight = (maxHeight / 6).coerceAtLeast((112 * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp)
            LazyColumn(Modifier.fillMaxSize().testTag("calendar-month-weeks"), state = weeks) {
                items(dates.chunked(7), key = { it.first().toString() }) { week ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                    Row(Modifier.fillMaxWidth().height(rowHeight)) {
                        week.forEach { date ->
                            val earnings = remember(state.showEarnings, state.earnings[date], state.earningsSort) {
                                if (state.showEarnings) sortEarnings(
                                    state.earnings[date]?.calendar?.events.orEmpty(), state.earningsSort,
                                ) else emptyList()
                            }
                            val events = if (state.showEconomic) economic[date].orEmpty() else emptyList()
                            val unsupported = state.showEarnings && !supportsEarnings(date, today)
                            val pending = (state.showEarnings && supportsEarnings(date, today) && state.earnings[date]?.loaded != true) ||
                                (state.showEconomic && !state.economicLoaded && !state.economicFailed)
                            val failed = (state.showEarnings && state.earnings[date]?.let { it.error != null || it.calendar?.stale == true } == true) ||
                                (state.showEconomic && state.economicFailed)
                            val label = stringResource(R.string.calendar_day_description, date.toString(), earnings.size, events.size) +
                                if (failed) " · " + stringResource(R.string.calendar_day_unavailable)
                                else if (unsupported) " · " + stringResource(R.string.calendar_earnings_range)
                                else if (pending) " · " + stringResource(R.string.calendar_day_pending) else ""
                            Column(
                                Modifier.weight(1f).fillMaxHeight().padding(horizontal = 1.dp, vertical = 3.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (date == state.selectedDate) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface.copy(alpha = 0f))
                                    .clickable { onDate(date) }
                                    .testTag("calendar-day-$date")
                                    .semantics(mergeDescendants = true) {
                                        selected = date == state.selectedDate
                                        contentDescription = label
                                    }
                                    .alpha(if (YearMonth.from(date) == state.month) 1f else 0.4f)
                                    .padding(top = 3.dp, start = 2.dp, end = 2.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Box(
                                    Modifier.size(24.dp).clip(CircleShape).background(when (date) {
                                        state.selectedDate -> MaterialTheme.colorScheme.primary
                                        today -> MaterialTheme.colorScheme.primaryContainer
                                        else -> MaterialTheme.colorScheme.surface.copy(alpha = 0f)
                                    }), contentAlignment = Alignment.Center,
                                ) {
                                    Text(date.dayOfMonth.toString(), fontSize = 12.sp,
                                        fontWeight = if (date == today || date == state.selectedDate) FontWeight.Bold else FontWeight.Normal,
                                        color = if (date == state.selectedDate) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                                }
                                if (earnings.isNotEmpty()) EarningsPreview(earnings, logos, source)
                                if (events.isNotEmpty()) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Box(Modifier.size(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary))
                                        Text(events.size.toString(), fontSize = 10.sp, color = MaterialTheme.colorScheme.tertiary,
                                            modifier = Modifier.testTag("calendar-economic-count-$date"))
                                    }
                                }
                                if (failed) Text("!", fontSize = 10.sp, color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.testTag("calendar-day-warning-$date"))
                                else if (pending && earnings.isEmpty() && events.isEmpty()) Text("···", fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                else if (unsupported && earnings.isEmpty() && events.isEmpty()) Text("—", fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EarningsPreview(events: List<EarningsEvent>, logos: CompanyLogoLoader?, source: BackendSettings) {
    val visible = events.take(if (events.size > 4) 3 else 4)
    val slots: List<EarningsEvent?> = visible + if (events.size > 4) listOf(null) else emptyList()
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        val iconSize = (maxWidth / 2 - 1.dp).coerceAtMost(18.dp)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            slots.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    row.forEach { event ->
                        if (event != null) CompanyLogo(event.symbol, event.name, logos, source,
                            compact = true, modifier = Modifier.size(iconSize))
                        else Box(Modifier.size(iconSize).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            contentAlignment = Alignment.Center) {
                            Text("+${events.size - visible.size}", fontSize = 8.sp, maxLines = 1,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
