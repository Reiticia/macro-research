package com.macroresearch.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.macroresearch.ui.theme.Upcoming

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ImportanceFilters(selected: Set<Int>, onToggle: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(3, 2, 1).forEach { level ->
            FilterChip(
                selected = level in selected,
                onClick = { onToggle(level) },
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
}

/** Both screens allow multiple levels, but always keep at least one selected. */
internal fun toggleImportanceSelection(current: Set<Int>, level: Int): Set<Int> {
    if (level !in 1..3) return current
    val updated = if (level in current) current - level else current + level
    return updated.ifEmpty { current }
}
