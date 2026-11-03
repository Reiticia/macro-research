package com.macroresearch.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.macroresearch.R
import com.macroresearch.data.BackendSettings
import com.macroresearch.data.CompanyLogoLoader
import com.macroresearch.ui.common.appLocale
import com.macroresearch.ui.common.dateLabel
import com.macroresearch.ui.theme.ResearchLayout
import java.time.LocalDate
import java.time.format.TextStyle

/** The default calendar: a familiar seven-day selector and an inline agenda, not a month grid. */
@Composable
internal fun CalendarWeekContent(
    state: MonthCalendarState,
    source: BackendSettings,
    logos: CompanyLogoLoader?,
    warning: String?,
    onDate: (LocalDate) -> Unit,
    onWeek: (Long) -> Unit,
    onToday: () -> Unit,
    onMonth: () -> Unit,
    onContent: (CalendarContent) -> Unit,
    onImportance: (Int) -> Unit,
    onRefresh: () -> Unit,
    onEvent: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onEarnings: ((com.macroresearch.data.model.EarningsEvent) -> Unit)? = null,
    earningsRepository: com.macroresearch.data.EarningsRepository? = null,
) {
    val dates = weekDates(state.weekDate)
    val locale = appLocale()
    Column(modifier.fillMaxSize().testTag("calendar-week-view").padding(horizontal = ResearchLayout.pagePadding),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.nav_calendar), style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.calendar_week_agenda), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onToday, modifier = Modifier.testTag("calendar-week-today")) {
                Text(stringResource(R.string.earnings_today))
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Outlined.Refresh, stringResource(R.string.calendar_refresh))
            }
            IconButton(onClick = onMonth, modifier = Modifier.testTag("calendar-open-month")) {
                Icon(Icons.Outlined.CalendarMonth, stringResource(R.string.calendar_open_month),
                    tint = MaterialTheme.colorScheme.primary)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onWeek(-1) }, modifier = Modifier.testTag("calendar-previous-week")) {
                Icon(Icons.Outlined.ChevronLeft, stringResource(R.string.previous_week))
            }
            Text("${dateLabel(dates.first())} — ${dateLabel(dates.last())}", Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            IconButton(onClick = { onWeek(1) }, modifier = Modifier.testTag("calendar-next-week")) {
                Icon(Icons.Outlined.ChevronRight, stringResource(R.string.next_week))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            dates.forEach { date ->
                val chosen = date == state.selectedDate
                val label = dateLabel(date)
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                        .background(if (chosen) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
                        .selectable(selected = chosen, role = Role.Tab, onClick = { onDate(date) })
                        .semantics { contentDescription = label }
                        .testTag("calendar-week-day-$date")
                        .padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale),
                        style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (chosen) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (chosen) FontWeight.Bold else FontWeight.Medium,
                        color = if (chosen) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        key(state.selectedDate) {
            CalendarDayContent(state, source, logos, warning,
                onDismiss = {}, onContent = onContent, onImportance = onImportance,
                onRefresh = onRefresh, onEvent = onEvent, modifier = Modifier.weight(1f), embedded = true,
                onEarnings = onEarnings, earningsRepository = earningsRepository)
        }
        Text(stringResource(R.string.calendar_timezones), Modifier.padding(bottom = 8.dp),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
