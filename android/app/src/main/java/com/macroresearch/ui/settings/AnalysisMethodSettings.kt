package com.macroresearch.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macroresearch.R
import com.macroresearch.data.AnalysisMethod
import com.macroresearch.data.MacroRepository
import com.macroresearch.ui.theme.ResearchLayout

/**
 * Lets the user decide whether the briefing may see the market's reaction. The default two-stage
 * method asks for the expectation first, so the chain cannot be fitted to moves that already
 * happened.
 */
@Composable
fun AnalysisMethodSettings(repository: MacroRepository) {
    val selected by repository.analysisMethod.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxWidth().padding(horizontal = ResearchLayout.pagePadding, vertical = ResearchLayout.smallGap),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.selectableGroup()) {
            AnalysisMethod.entries.forEach { method ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .selectable(
                            selected = selected == method,
                            role = Role.RadioButton,
                            onClick = { repository.setAnalysisMethod(method) },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RadioButton(selected = selected == method, onClick = null)
                    Column {
                        Text(methodLabel(method), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            methodNote(method),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Text(
            stringResource(R.string.ai_analysis_method_note),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun methodLabel(method: AnalysisMethod) = stringResource(
    when (method) {
        AnalysisMethod.NUMBERS_ONLY -> R.string.ai_method_numbers_only
        AnalysisMethod.EX_ANTE_THEN_COMPARE -> R.string.ai_method_two_stage
        AnalysisMethod.SINGLE_PASS -> R.string.ai_method_single_pass
    },
)

@Composable
private fun methodNote(method: AnalysisMethod) = stringResource(
    when (method) {
        AnalysisMethod.NUMBERS_ONLY -> R.string.ai_method_numbers_only_note
        AnalysisMethod.EX_ANTE_THEN_COMPARE -> R.string.ai_method_two_stage_note
        AnalysisMethod.SINGLE_PASS -> R.string.ai_method_single_pass_note
    },
)
