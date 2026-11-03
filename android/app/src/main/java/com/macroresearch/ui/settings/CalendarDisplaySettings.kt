package com.macroresearch.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.macroresearch.R
import com.macroresearch.data.CalendarDisplayMode

@Composable
internal fun CalendarDisplaySettings(selected: CalendarDisplayMode, onSelect: (CalendarDisplayMode) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.calendar_display_content), fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.calendar_display_note), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.selectableGroup()) {
                CalendarDisplayMode.entries.forEach { mode ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .testTag("calendar-display-${mode.name}")
                            .selectable(selected = selected == mode, role = Role.RadioButton, onClick = { onSelect(mode) }),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(selected = selected == mode, onClick = null)
                        Text(calendarDisplayLabel(mode), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

@Composable
internal fun calendarDisplayLabel(mode: CalendarDisplayMode): String = stringResource(when (mode) {
    CalendarDisplayMode.ALL -> R.string.calendar_display_all
    CalendarDisplayMode.EARNINGS -> R.string.calendar_display_earnings
    CalendarDisplayMode.ECONOMIC -> R.string.calendar_display_economic
})
