package com.macroresearch.ui.earnings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.macroresearch.R
import com.macroresearch.data.EarningsRepository
import com.macroresearch.data.model.*
import com.macroresearch.ui.common.appLocale
import com.macroresearch.ui.common.dateLabel
import com.macroresearch.ui.common.LoadingHint
import com.macroresearch.ui.theme.ResearchLayout
import com.macroresearch.ui.viewModelFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EarningsScreen(repository: EarningsRepository) {
    val vm: EarningsViewModel = viewModel(factory = viewModelFactory { EarningsViewModel(repository) })
    val state by vm.state.collectAsStateWithLifecycle()
    val source by repository.settings.collectAsStateWithLifecycle()
    var search by rememberSaveable { mutableStateOf("") }
    var session by rememberSaveable { mutableStateOf<String?>(null) }
    var picker by remember { mutableStateOf(false) }
    val locale = appLocale()
    val today = LocalDate.now(EARNINGS_ZONE)
    val minDate = today.minusDays(120)
    val maxDate = today.plusDays(60)
    val filtered = remember(state.calendar, search, session) { filterEarnings(state.calendar?.events.orEmpty(), search, session) }

    Column(Modifier.fillMaxSize().padding(horizontal = ResearchLayout.pagePadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.earnings_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.earnings_basis), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { vm.refresh(force = true) }, enabled = !state.loading) { Icon(Icons.Outlined.Refresh, stringResource(R.string.earnings_refresh)) }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.selectDate(state.date.minusDays(1)) }, enabled = state.date > minDate) { Icon(Icons.Outlined.ChevronLeft, stringResource(R.string.earnings_previous_day)) }
            TextButton(onClick = { picker = true }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(dateLabel(state.date), fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = { vm.selectDate(today) }) { Text(stringResource(R.string.earnings_today)) }
            IconButton(onClick = { vm.selectDate(state.date.plusDays(1)) }, enabled = state.date < maxDate) { Icon(Icons.Outlined.ChevronRight, stringResource(R.string.earnings_next_day)) }
        }
        OutlinedTextField(value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.earnings_search)) }, singleLine = true)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf<String?>(null, "pre_market", "after_hours", "unknown")) { value ->
                FilterChip(selected = session == value, onClick = { session = value }, label = {
                    Text(stringResource(if (value == null) R.string.earnings_all else sessionLabel(value)))
                })
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.earnings_count, filtered.size), style = MaterialTheme.typography.labelLarge)
            state.calendar?.let { Text(stringResource(R.string.earnings_updated, updatedLabel(it.updatedAt, locale)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ResearchLayout.gap), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (state.loading) item { LoadingHint() }
            state.error?.let { reason -> item {
                Text(stringResource(when (reason) { "unauthorized" -> R.string.earnings_unauthorized; "unsupported" -> R.string.earnings_unsupported; else -> R.string.earnings_failed }), color = MaterialTheme.colorScheme.error)
            } }
            state.calendar?.let { calendar ->
                if (calendar.stale) item { WarningText(R.string.earnings_stale) }
                calendar.warnings.distinct().forEach { warning -> item {
                    WarningText(when (warning) {
                        "nasdaq_unavailable" -> R.string.earnings_nasdaq_unavailable
                        "tradingview_unavailable" -> R.string.earnings_tv_unavailable
                        "date_conflict" -> R.string.earnings_conflict
                        "refresh_cooldown" -> R.string.earnings_cooldown
                        else -> R.string.earnings_failed
                    })
                } }
            }
            if (!state.loading && state.calendar != null && filtered.isEmpty()) item {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Text(stringResource(R.string.earnings_empty), Modifier.fillMaxWidth().padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(filtered, key = { "${it.source}:${it.tradingView?.ticker ?: it.symbol}" }) { event -> EarningsCard(event, repository.logos, source) }
            state.calendar?.let { calendar -> item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.earnings_logo_source), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.earnings_disclaimer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.earnings_source_times,
                        calendar.nasdaqUpdatedAt?.let { updatedLabel(it, locale) } ?: "—",
                        calendar.tradingViewUpdatedAt?.let { updatedLabel(it, locale) } ?: "—"),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } }
        }
    }
    if (picker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = remember(minDate, maxDate) { object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val date = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
                    return date >= minDate && date <= maxDate
                }
                override fun isSelectableYear(year: Int) = year in minDate.year..maxDate.year
            } },
        )
        DatePickerDialog(onDismissRequest = { picker = false }, confirmButton = {
            TextButton(onClick = {
                pickerState.selectedDateMillis?.let { vm.selectDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                picker = false
            }) { Text(stringResource(R.string.apply)) }
        }, dismissButton = { TextButton(onClick = { picker = false }) { Text(stringResource(R.string.cancel)) } }) { DatePicker(pickerState) }
    }
}

internal fun sessionLabel(session: String): Int = when (session) {
    "pre_market" -> R.string.earnings_pre_market
    "after_hours" -> R.string.earnings_after_hours
    else -> R.string.earnings_unknown
}

internal fun updatedLabel(value: String, locale: java.util.Locale): String = runCatching {
    DateTimeFormatter.ofPattern("MM-dd HH:mm", locale).withZone(ZoneId.systemDefault()).format(Instant.parse(value))
}.getOrDefault("—")

@Composable
private fun WarningText(resource: Int) {
    Text(stringResource(resource), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

@Composable
internal fun EarningsCard(event: EarningsEvent, logos: com.macroresearch.data.CompanyLogoLoader?, source: com.macroresearch.data.BackendSettings) {
    var expanded by rememberSaveable(event.symbol, event.source) { mutableStateOf(false) }
    Surface(shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CompanyLogo(event.symbol, event.name, logos, source)
                Spacer(Modifier.width(12.dp))
                Text(event.symbol, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text(stringResource(sessionLabel(event.session)), Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
                }
            }
            Text(event.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.earnings_quarter, event.fiscalQuarterEnding ?: "—"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Metric(stringResource(R.string.earnings_eps_forecast), event.epsForecast, Modifier.weight(1f))
                Metric(stringResource(R.string.earnings_analysts), event.analystCount?.toString(), Modifier.weight(1f))
            }
            if (event.actualEps != null) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Metric(stringResource(R.string.earnings_actual_eps), event.actualEps, Modifier.weight(1f))
                Metric(stringResource(R.string.earnings_surprise), event.surprisePercent?.let { "$it%" }, Modifier.weight(1f))
            }
            Text(stringResource(R.string.earnings_primary_source, if (event.source == "nasdaq") "Nasdaq" else "TradingView"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            event.tradingView?.let { tv ->
                if (tv.dateConflict) WarningText(R.string.earnings_conflict)
                TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                    Text(stringResource(if (expanded) R.string.earnings_hide_tv else R.string.earnings_show_tv))
                }
                if (expanded) {
                    Text(stringResource(R.string.earnings_tv_date, tv.nextDate ?: "—"), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Metric(stringResource(R.string.earnings_tv_eps), tv.nextEpsForecast, Modifier.weight(1f))
                        Metric(stringResource(R.string.earnings_tv_revenue), tv.nextRevenueForecast, Modifier.weight(1f))
                    }
                    Text(stringResource(R.string.earnings_alignment_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value ?: "—", style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace)
    }
}
