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
import androidx.compose.material3.HorizontalDivider
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
import com.macroresearch.data.model.EarningsSort
import com.macroresearch.data.model.EarningsSortDirection
import com.macroresearch.data.model.EarningsSortField

@Composable
internal fun EarningsSortSettings(selected: EarningsSort, onSelect: (EarningsSort) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.earnings_sort), fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.earnings_sort_note), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.earnings_sort_field), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
            Column(Modifier.selectableGroup()) {
                EarningsSortField.entries.forEach { field ->
                    SortChoice(earningsSortFieldLabel(field), selected.field == field, "earnings-sort-${field.name}") {
                        onSelect(selected.copy(field = field))
                    }
                }
            }
            HorizontalDivider()
            Text(stringResource(R.string.earnings_sort_direction), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
            Column(Modifier.selectableGroup()) {
                EarningsSortDirection.entries.forEach { direction ->
                    SortChoice(earningsSortDirectionLabel(direction), selected.direction == direction,
                        "earnings-sort-${direction.name}") { onSelect(selected.copy(direction = direction)) }
                }
            }
        }
    }
}

@Composable
private fun SortChoice(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)
        .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
internal fun earningsSortFieldLabel(field: EarningsSortField): String = stringResource(when (field) {
    EarningsSortField.MARKET_CAP -> R.string.earnings_sort_market_cap
    EarningsSortField.SYMBOL -> R.string.earnings_sort_symbol
    EarningsSortField.RELEASE_TIME -> R.string.earnings_sort_release_time
})

@Composable
internal fun earningsSortDirectionLabel(direction: EarningsSortDirection): String = stringResource(when (direction) {
    EarningsSortDirection.ASCENDING -> R.string.earnings_sort_ascending
    EarningsSortDirection.DESCENDING -> R.string.earnings_sort_descending
})
