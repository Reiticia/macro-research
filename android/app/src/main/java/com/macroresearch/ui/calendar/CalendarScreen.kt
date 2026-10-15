package com.macroresearch.ui.calendar

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.macroresearch.R
import com.macroresearch.data.MacroRepository
import com.macroresearch.ui.CalendarViewModel
import com.macroresearch.ui.common.ImportanceFilters
import com.macroresearch.ui.common.LoadingHint
import com.macroresearch.ui.common.appLocale
import com.macroresearch.ui.common.calendarWarningMessage
import com.macroresearch.ui.common.dateLabel
import com.macroresearch.ui.common.terminal.EmptyHint
import com.macroresearch.ui.common.terminal.EventRow
import com.macroresearch.ui.common.terminal.EventTableHeader
import com.macroresearch.ui.common.terminal.Hairline
import com.macroresearch.ui.common.terminal.NoticeStrip
import com.macroresearch.ui.common.terminal.NoticeTone
import com.macroresearch.ui.common.terminal.PageHeader
import com.macroresearch.ui.theme.ResearchLayout
import com.macroresearch.ui.theme.ResearchType
import com.macroresearch.ui.viewModelFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.TextStyle

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CalendarScreen(repository: MacroRepository, padding: PaddingValues, onEvent: (Long) -> Unit) {
    val vm: CalendarViewModel = viewModel(factory = viewModelFactory { CalendarViewModel(repository) })
    val state by vm.state.collectAsStateWithLifecycle()
    val warning by repository.calendarWarning.collectAsStateWithLifecycle()
    var showDatePicker by remember { mutableStateOf(false) }
    // Translations are keyed by event name and can be corrected on the detail screen;
    // re-read them whenever this screen is shown again so corrections appear immediately.
    LaunchedEffect(Unit) { vm.refreshTranslations() }

    Column(Modifier.fillMaxSize().padding(padding)) {
        PageHeader(
            title = stringResource(R.string.nav_calendar),
            subtitle = dateLabel(state.date, monthOnly = true),
            actions = {
                IconButton(onClick = { showDatePicker = true }) {
                    Icon(Icons.Outlined.CalendarMonth, stringResource(R.string.choose_date), tint = MaterialTheme.colorScheme.primary)
                }
            },
        )
        DateSelector(state.date, vm::selectDate)
        Box(Modifier.padding(horizontal = ResearchLayout.pagePadding, vertical = ResearchLayout.denseGap)) {
            ImportanceFilters(state.importance, vm::toggleImportance)
        }
        Hairline()
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = ResearchLayout.gap)) {
            stickyHeader(key = "day") {
                Column(Modifier.background(MaterialTheme.colorScheme.background)) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = ResearchLayout.pagePadding, vertical = ResearchLayout.smallGap),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            dateLabel(state.date),
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            stringResource(R.string.event_count, state.filtered.size),
                            style = ResearchType.small,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    EventTableHeader()
                }
            }
            warning?.let { item(key = "warning") { NoticeStrip(calendarWarningMessage(it), tone = NoticeTone.Error) } }
            state.error?.let { item(key = "error") { NoticeStrip(stringResource(R.string.load_failed, it), tone = NoticeTone.Error) } }
            if (state.loading) item(key = "loading") { LoadingHint() }
            if (!state.loading && state.filtered.isEmpty()) item(key = "empty") {
                EmptyHint(stringResource(R.string.no_filtered_events))
            }
            items(state.filtered, key = { it.id }) { event -> EventRow(event, { onEvent(event.id) }) }
        }
    }

    if (showDatePicker) {
        CalendarDatePicker(
            selected = state.date,
            onDismiss = { showDatePicker = false },
            onSelect = {
                vm.selectDate(it)
                showDatePicker = false
            },
        )
    }
}

/** 以周一开始的 7 天，与原日期选择器一致。 */
internal fun weekOf(selected: LocalDate): List<LocalDate> {
    val monday = selected.minusDays((selected.dayOfWeek.value - 1).toLong())
    return (0L..6L).map(monday::plusDays)
}

@Composable
private fun DateSelector(selected: LocalDate, onSelect: (LocalDate) -> Unit) {
    val dates = remember(selected) { weekOf(selected) }
    val today = LocalDate.now()
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onSelect(selected.minusWeeks(1)) }) {
                Icon(Icons.Outlined.ChevronLeft, stringResource(R.string.previous_week), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "${dateLabel(dates.first())}  —  ${dateLabel(dates.last())}",
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            IconButton(onClick = { onSelect(selected.plusWeeks(1)) }) {
                Icon(Icons.Outlined.ChevronRight, stringResource(R.string.next_week), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth().selectableGroup()) {
            dates.forEach { date ->
                DayCell(date, date == selected, date == today, { onSelect(date) }, Modifier.weight(1f))
            }
        }
        Hairline()
    }
}

@Composable
private fun DayCell(date: LocalDate, selected: Boolean, today: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        modifier
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .drawBehind {
                if (selected) {
                    drawRect(
                        accent,
                        topLeft = Offset(size.width * 0.2f, size.height - 2.dp.toPx()),
                        size = Size(size.width * 0.6f, 2.dp.toPx()),
                    )
                }
            }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            date.dayOfWeek.getDisplayName(TextStyle.SHORT, appLocale()),
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                date.dayOfMonth.toString(),
                style = ResearchType.body,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
            )
            if (today) Text("•", color = accent, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarDatePicker(
    selected: LocalDate,
    onDismiss: () -> Unit,
    onSelect: (LocalDate) -> Unit,
) {
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = selected.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        onSelect(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                },
            ) { Text(stringResource(R.string.apply)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    ) {
        DatePicker(state = pickerState)
    }
}
