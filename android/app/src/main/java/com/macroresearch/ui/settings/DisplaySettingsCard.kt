package com.macroresearch.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.macroresearch.R
import com.macroresearch.data.DisplayMode
import com.macroresearch.ui.common.terminal.TerminalFilterChip
import com.macroresearch.ui.theme.LocalDisplaySettings
import com.macroresearch.ui.theme.ResearchLayout

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DisplaySettingsCard() {
    val settings = LocalDisplaySettings.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = ResearchLayout.pagePadding, vertical = ResearchLayout.gap),
        verticalArrangement = Arrangement.spacedBy(ResearchLayout.smallGap),
    ) {
        Text(
            stringResource(R.string.display_size_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(ResearchLayout.smallGap)) {
            DisplayMode.entries.forEach { mode ->
                TerminalFilterChip(
                    selected = settings.mode == mode,
                    onClick = { settings.setMode(mode) },
                    label = stringResource(
                        when (mode) {
                            DisplayMode.AUTO -> R.string.display_auto
                            DisplayMode.COMPACT -> R.string.display_compact
                            DisplayMode.SYSTEM -> R.string.display_system
                        },
                    ),
                )
            }
        }
    }
}
