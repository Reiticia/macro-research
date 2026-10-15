package com.macroresearch.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.macroresearch.R
import com.macroresearch.data.DisplayMode
import com.macroresearch.ui.theme.LocalDisplaySettings
import com.macroresearch.ui.theme.ResearchLayout

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DisplaySettingsCard() {
    val settings = LocalDisplaySettings.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(ResearchLayout.cardCorner),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            Modifier.padding(ResearchLayout.cardPadding),
            verticalArrangement = Arrangement.spacedBy(ResearchLayout.smallGap),
        ) {
            SettingsSectionHeader(stringResource(R.string.display_size))
            Text(
                stringResource(R.string.display_size_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(ResearchLayout.smallGap)) {
                DisplayMode.entries.forEach { mode ->
                    val isSelected = settings.mode == mode
                    FilterChip(
                        selected = isSelected,
                        onClick = { settings.setMode(mode) },
                        shape = RoundedCornerShape(ResearchLayout.innerCorner),
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                        ),
                        label = {
                            Text(
                                stringResource(
                                    when (mode) {
                                        DisplayMode.AUTO -> R.string.display_auto
                                        DisplayMode.COMPACT -> R.string.display_compact
                                        DisplayMode.SYSTEM -> R.string.display_system
                                    },
                                ),
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            )
                        },
                    )
                }
            }
        }
    }
}
