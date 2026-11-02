package com.macroresearch.ui.calendar

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.macroresearch.R
import com.macroresearch.data.BackendSettings
import com.macroresearch.data.CompanyLogoLoader
import com.macroresearch.data.EarningsRepository
import com.macroresearch.data.MacroRepository
import com.macroresearch.ui.common.calendarWarningMessage
import com.macroresearch.ui.common.dateLabel
import com.macroresearch.ui.theme.ResearchLayout
import com.macroresearch.ui.viewModelFactory
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    repository: MacroRepository,
    padding: PaddingValues,
    earningsRepository: EarningsRepository? = null,
    onEvent: (Long) -> Unit,
) {
    val vm: MonthCalendarViewModel = viewModel(factory = viewModelFactory {
        MonthCalendarViewModel(RepositoryMonthEconomicSource(repository), earningsRepository)
    })
    val state by vm.state.collectAsStateWithLifecycle()
    val source by repository.dataSourceSettings.collectAsStateWithLifecycle()
    val warning by repository.calendarWarning.collectAsStateWithLifecycle()
    var showDay by rememberSaveable { mutableStateOf(false) }
    var showPicker by rememberSaveable { mutableStateOf(false) }
    CalendarMonthContent(
        state = state, source = source, logos = earningsRepository?.logos,
        modifier = Modifier.padding(padding),
        onMonth = vm::selectMonth, onToday = vm::selectToday,
        onPicker = { showPicker = true }, onRefresh = vm::refresh,
        onContent = vm::selectContent,
        onDate = { vm.selectDate(it); showDay = true },
    )
    if (showDay) CalendarDaySheet(
        state = state, source = source, logos = earningsRepository?.logos,
        warning = warning?.let { calendarWarningMessage(it) },
        onDismiss = { showDay = false }, onContent = vm::selectContent,
        onImportance = vm::toggleImportance, onRefresh = vm::refresh,
        onEvent = { showDay = false; onEvent(it) },
    )
    if (showPicker) {
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = state.selectedDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = { TextButton(onClick = {
                picker.selectedDateMillis?.let { millis ->
                    vm.selectDate(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                    showDay = true
                }
                showPicker = false
            }) { Text(stringResource(R.string.apply)) } },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(picker) }
    }
}

@Composable
internal fun CalendarMonthContent(
    state: MonthCalendarState,
    source: BackendSettings,
    logos: CompanyLogoLoader?,
    onMonth: (YearMonth) -> Unit,
    onToday: () -> Unit,
    onPicker: () -> Unit,
    onRefresh: () -> Unit,
    onContent: (CalendarContent) -> Unit,
    onDate: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    var todayRequest by remember { mutableIntStateOf(0) }
    Column(
        modifier.fillMaxSize().padding(horizontal = ResearchLayout.pagePadding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.calendar_overview), Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { onToday(); todayRequest++ }, modifier = Modifier.testTag("calendar-today")) {
                Text(stringResource(R.string.earnings_today))
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Outlined.Refresh, stringResource(R.string.calendar_refresh))
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onMonth(state.month.minusMonths(1)) }, modifier = Modifier.testTag("calendar-previous-month")) {
                Icon(Icons.Outlined.ChevronLeft, stringResource(R.string.calendar_previous_month))
            }
            TextButton(onClick = onPicker, modifier = Modifier.weight(1f)) {
                Text(dateLabel(state.month.atDay(1), monthOnly = true),
                    modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface)
                Icon(Icons.Outlined.ExpandMore, null, Modifier.size(20.dp))
            }
            IconButton(onClick = { onMonth(state.month.plusMonths(1)) }, modifier = Modifier.testTag("calendar-next-month")) {
                Icon(Icons.Outlined.ChevronRight, stringResource(R.string.calendar_next_month))
            }
        }
        CalendarContentFilters(state.content, state.earningsAvailable, onContent)
        Text(stringResource(R.string.calendar_legend), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        CalendarMonthGrid(state, source, logos, onDate, Modifier.weight(1f), focusRequest = todayRequest)
        if (state.economicLoading || state.earningsLoading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
            Text(stringResource(R.string.calendar_loading_month), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (state.showEarnings && monthDates(state.month).none {
            supportsEarnings(it, LocalDate.now(com.macroresearch.data.model.EARNINGS_ZONE))
        }) {
            Text(stringResource(R.string.calendar_earnings_range), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if ((state.showEconomic && state.economicFailed) ||
            (state.showEarnings && state.earnings.values.any { it.error != null || it.calendar?.stale == true })) {
            Text(stringResource(R.string.calendar_partial), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error)
        }
        Text(stringResource(R.string.calendar_timezones), Modifier.padding(bottom = 8.dp),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CalendarContentFilters(selected: CalendarContent, earningsAvailable: Boolean, onSelect: (CalendarContent) -> Unit) {
    val types = if (earningsAvailable) CalendarContent.entries else listOf(CalendarContent.ECONOMIC)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        types.forEachIndexed { index, type ->
            SegmentedButton(
                selected = type == selected || !earningsAvailable,
                onClick = { onSelect(type) },
                shape = SegmentedButtonDefaults.itemShape(index, types.size),
                modifier = Modifier.testTag("calendar-filter-${type.name}"),
                icon = {},
                label = { Text(stringResource(when (type) {
                    CalendarContent.ALL -> R.string.calendar_all
                    CalendarContent.EARNINGS -> R.string.earnings_tab
                    CalendarContent.ECONOMIC -> R.string.earnings_macro_tab
                }), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}
