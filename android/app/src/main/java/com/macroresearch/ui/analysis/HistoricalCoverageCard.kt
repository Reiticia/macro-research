package com.macroresearch.ui.analysis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.macroresearch.R
import com.macroresearch.data.model.HistoricalEvidence
import com.macroresearch.ui.common.assetLabel
import com.macroresearch.ui.common.terminal.Hairline
import com.macroresearch.ui.common.terminal.SectionHeader
import com.macroresearch.ui.theme.ResearchLayout
import com.macroresearch.ui.theme.Upcoming

@Composable
fun HistoricalCoverageCard(evidence: HistoricalEvidence) {
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(stringResource(R.string.historical_coverage))
        Column(Modifier.padding(horizontal = ResearchLayout.pagePadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.historical_method), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (evidence.revisedDataPossible) {
                Text(stringResource(R.string.historical_revision_warning), style = MaterialTheme.typography.bodySmall, color = Upcoming)
            }
            evidence.coverage.forEach { coverage ->
                Hairline()
                val state = when (coverage.status) {
                    "complete" -> R.string.coverage_complete
                    "partial" -> R.string.coverage_partial
                    else -> R.string.coverage_unavailable
                }
                Text(stringResource(R.string.coverage_asset, assetLabel(coverage.symbol), stringResource(state)), fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.coverage_source, coverage.source, coverage.intervalSeconds?.let { "${it / 60}" } ?: "--"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (coverage.availableHorizons.isNotEmpty()) {
                    Text(stringResource(R.string.coverage_windows, coverage.availableHorizons.joinToString(" / ")), style = MaterialTheme.typography.bodySmall)
                }
                coverage.reason?.let { reason ->
                    val label = when (reason) {
                        "retention_limit" -> R.string.coverage_retention
                        "provider_error" -> R.string.coverage_provider_error
                        "approximate_release_time" -> R.string.coverage_approximate_time
                        else -> R.string.coverage_missing_samples
                    }
                    Text(stringResource(label), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
