package com.macroresearch.ui.calendar

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.macroresearch.R
import com.macroresearch.data.BackendSettings
import com.macroresearch.data.CompanyLogoLoader
import com.macroresearch.data.model.EARNINGS_ZONE
import com.macroresearch.data.model.EarningsEvent
import com.macroresearch.data.model.filterEarnings
import com.macroresearch.ui.common.*
import com.macroresearch.ui.earnings.EarningsCard
import com.macroresearch.ui.earnings.sessionLabel
import com.macroresearch.ui.earnings.updatedLabel
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CalendarDaySheet(
    state: MonthCalendarState,
    source: BackendSettings,
    logos: CompanyLogoLoader?,
    warning: String?,
    onDismiss: () -> Unit,
    onContent: (CalendarContent) -> Unit,
    onImportance: (Int) -> Unit,
    onRefresh: () -> Unit,
    onEvent: (Long) -> Unit,
    onEarnings: ((EarningsEvent) -> Unit)? = null,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        CalendarDayContent(state, source, logos, warning, onDismiss, onContent, onImportance, onRefresh, onEvent,
            Modifier.fillMaxHeight(0.92f), onEarnings = onEarnings)
    }
}

@Composable
internal fun CalendarDayContent(
    state: MonthCalendarState,
    source: BackendSettings,
    logos: CompanyLogoLoader?,
    warning: String?,
    onDismiss: () -> Unit,
    onContent: (CalendarContent) -> Unit,
    onImportance: (Int) -> Unit,
    onRefresh: () -> Unit,
    onEvent: (Long) -> Unit,
    modifier: Modifier = Modifier,
    embedded: Boolean = false,
    onEarnings: ((EarningsEvent) -> Unit)? = null,
) {
    var search by rememberSaveable(state.selectedDate) { mutableStateOf("") }
    var session by rememberSaveable(state.selectedDate) { mutableStateOf<String?>(null) }
    val locale = appLocale()
    val day = state.earnings[state.selectedDate]
    val calendar = day?.calendar
    val supported = supportsEarnings(state.selectedDate, LocalDate.now(EARNINGS_ZONE))
    val earnings = filterEarnings(calendar?.events.orEmpty(), search, session)
    val economic = remember(state.economic, state.countries, state.importance, state.selectedDate, search, locale) {
        economicByDate(state.filteredEconomic, ZoneId.systemDefault())[state.selectedDate].orEmpty().filter {
            search.isBlank() || it.localizedName(locale).contains(search.trim(), true) || it.event.contains(search.trim(), true)
        }
    }
    Column(modifier.fillMaxWidth().padding(horizontal = if (embedded) 0.dp else 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(dateLabel(state.selectedDate), style = if (embedded) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("calendar-selected-date"))
                if (!embedded) Text(stringResource(R.string.calendar_day_agenda), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!embedded) {
                IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, stringResource(R.string.calendar_refresh)) }
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, stringResource(R.string.calendar_close_day)) }
            }
        }
        CalendarContentFilters(state.content, state.earningsAvailable, onContent)
        OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.calendar_search)) }, singleLine = true)
        LazyColumn(Modifier.weight(1f).testTag("calendar-day-list"), verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 32.dp)) {
            if (state.showEconomic) {
                item(key = "economic-header") {
                    AgendaHeading(stringResource(R.string.earnings_macro_tab), economic.size)
                    Text(stringResource(R.string.calendar_economic_timezone, ZoneId.systemDefault().id),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ImportanceFilters(state.importance, onImportance)
                }
                warning?.let { item(key = "economic-warning") { AgendaWarning(it) } }
                if (state.economicFailed) item(key = "economic-error") { AgendaWarning(stringResource(R.string.calendar_economic_failed)) }
                if (state.economicLoading) item(key = "economic-loading") { LoadingHint() }
                if (!state.economicLoading && !state.economicFailed && economic.isEmpty()) item(key = "economic-empty") {
                    Text(stringResource(R.string.no_filtered_events), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(economic, key = { "economic-${it.id}" }) { event -> EventCard(event, { onEvent(event.id) }) }
            }
            if (state.showEarnings) {
                item(key = "earnings-header") {
                    AgendaHeading(stringResource(R.string.earnings_title), earnings.size)
                    Text(stringResource(R.string.earnings_basis), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (supported) {
                    item(key = "sessions") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(listOf<String?>(null, "pre_market", "after_hours", "unknown")) { value ->
                                FilterChip(session == value, { session = value }, label = {
                                    Text(stringResource(if (value == null) R.string.earnings_all else sessionLabel(value)))
                                })
                            }
                        }
                    }
                    if (state.earningsLoading && day?.loaded != true) item(key = "earnings-loading") { LoadingHint() }
                    day?.error?.let { reason -> item(key = "earnings-error") {
                        AgendaWarning(stringResource(when (reason) {
                            "unauthorized" -> R.string.earnings_unauthorized
                            "unsupported" -> R.string.earnings_unsupported
                            else -> R.string.earnings_failed
                        }))
                    } }
                    if (calendar?.stale == true) item(key = "earnings-stale") { AgendaWarning(stringResource(R.string.earnings_stale)) }
                    calendar?.warnings?.distinct()?.forEach { message -> item(key = "warning-$message") {
                        AgendaWarning(stringResource(when (message) {
                            "nasdaq_unavailable" -> R.string.earnings_nasdaq_unavailable
                            "tradingview_unavailable" -> R.string.earnings_tv_unavailable
                            "date_conflict" -> R.string.earnings_conflict
                            "refresh_cooldown" -> R.string.earnings_cooldown
                            else -> R.string.earnings_failed
                        }))
                    } }
                    if (day?.loaded == true && calendar != null && earnings.isEmpty()) item(key = "earnings-empty") {
                        Text(stringResource(R.string.earnings_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(earnings, key = { "earnings-${it.source}:${it.tradingView?.ticker ?: it.symbol}" }) { event ->
                        EarningsCard(event, logos, source, onMarket = onEarnings?.let { open -> { open(event) } })
                    }
                    if (calendar != null) item(key = "earnings-sources") {
                        Text(stringResource(R.string.earnings_source_times,
                            calendar.nasdaqUpdatedAt?.let { updatedLabel(it, locale) } ?: "—",
                            calendar.tradingViewUpdatedAt?.let { updatedLabel(it, locale) } ?: "—"),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else item(key = "earnings-range") {
                    AgendaWarning(stringResource(R.string.calendar_earnings_range))
                }
                item(key = "earnings-disclaimer") {
                    Text(stringResource(R.string.earnings_disclaimer), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.earnings_logo_source), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun AgendaHeading(title: String, count: Int) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(count.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun AgendaWarning(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}
