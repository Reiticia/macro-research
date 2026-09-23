package com.macroresearch.ui.calendar

import androidx.compose.ui.res.stringResource
import com.macroresearch.R
import com.macroresearch.ui.common.appLocale
import com.macroresearch.ui.common.dateLabel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.macroresearch.data.MacroRepository
import com.macroresearch.ui.CalendarViewModel
import com.macroresearch.ui.common.EventCard
import com.macroresearch.ui.common.LoadingHint
import com.macroresearch.ui.common.calendarWarningMessage
import com.macroresearch.ui.common.importanceLabel
import com.macroresearch.ui.theme.Upcoming
import com.macroresearch.ui.theme.ResearchLayout
import com.macroresearch.ui.viewModelFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.TextStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(repository: MacroRepository, padding: PaddingValues, onEvent: (Long) -> Unit) {
    val vm: CalendarViewModel = viewModel(factory = viewModelFactory { CalendarViewModel(repository) })
    val state by vm.state.collectAsStateWithLifecycle()
    val warning by repository.calendarWarning.collectAsStateWithLifecycle()
    var showDatePicker by remember { mutableStateOf(false) }
    // Translations are keyed by event name and can be corrected on the detail screen;
    // re-read them whenever this screen is shown again so corrections appear immediately.
    LaunchedEffect(Unit) { vm.refreshTranslations() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = ResearchLayout.pagePadding, vertical = ResearchLayout.gap),
        verticalArrangement = Arrangement.spacedBy(ResearchLayout.gap),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(stringResource(R.string.nav_calendar), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(dateLabel(state.date, monthOnly = true), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { showDatePicker = true }) {
                Icon(Icons.Outlined.CalendarMonth, stringResource(R.string.choose_date), tint = MaterialTheme.colorScheme.primary)
            }
        }
        DateSelector(state.date, vm::selectDate)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(3, 2, 1).forEach { level ->
                FilterChip(
                    selected = level in state.importance,
                    onClick = { vm.toggleImportance(level) },
                    leadingIcon = {
                        Text(
                            "●",
                            color = when (level) {
                                3 -> Upcoming
                                2 -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                    label = { Text(importanceLabel(level)) },
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(dateLabel(state.date), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.event_count, state.filtered.size), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(ResearchLayout.gap),
            contentPadding = PaddingValues(bottom = 4.dp),
        ) {
            warning?.let { item { Text(calendarWarningMessage(it), color = MaterialTheme.colorScheme.error) } }
            state.error?.let { item { Text(stringResource(R.string.load_failed, it), color = MaterialTheme.colorScheme.error) } }
            if (state.loading) item { LoadingHint() }
            if (!state.loading && state.filtered.isEmpty()) item {
                Card { Text(stringResource(R.string.no_filtered_events), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(state.filtered, key = { it.id }) { event -> EventCard(event, { onEvent(event.id) }) }
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

@Composable
private fun DateSelector(selected: LocalDate, onSelect: (LocalDate) -> Unit) {
    val monday = remember(selected) {
        selected.minusDays((selected.dayOfWeek.value - 1).toLong())
    }
    val dates = remember(monday) { (0L..6L).map(monday::plusDays) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { onSelect(selected.minusWeeks(1)) }) {
                Icon(Icons.Outlined.ChevronLeft, stringResource(R.string.previous_week))
            }
            Text(
                "${dateLabel(monday)}  —  ${dateLabel(monday.plusDays(6))}",
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            IconButton(onClick = { onSelect(selected.plusWeeks(1)) }) {
                Icon(Icons.Outlined.ChevronRight, stringResource(R.string.next_week))
            }
        }
        LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            items(dates) { date ->
                FilterChip(
                    selected = date == selected,
                    onClick = { onSelect(date) },
                    label = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(date.dayOfWeek.getDisplayName(TextStyle.SHORT, appLocale()))
                            Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.titleMedium)
                        }
                    },
                )
            }
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
