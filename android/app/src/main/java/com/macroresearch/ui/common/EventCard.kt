package com.macroresearch.ui.common

import androidx.compose.ui.res.stringResource
import com.macroresearch.R
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.macroresearch.data.model.EconomicEvent
import com.macroresearch.data.model.currentStatus
import com.macroresearch.ui.theme.AssetDown
import com.macroresearch.ui.theme.AssetUp
import com.macroresearch.ui.theme.Dovish
import com.macroresearch.ui.theme.Upcoming
import com.macroresearch.ui.theme.ResearchLayout

@Composable
fun EventCard(
    event: EconomicEvent,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showDate: Boolean = false,
) {
    val surprise = event.surprise()
    val status = event.currentStatus()
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(ResearchLayout.cardPadding),
            verticalArrangement = Arrangement.spacedBy(ResearchLayout.smallGap),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Lists that span several days (history) need the date; day views already show it in the header.
                val timestamp = if (showDate) "${event.localizedShortDate()}  ${event.localTime()}" else event.localTime()
                Text(
                    "$timestamp  ${flag(event.country)}  ${countryLabel(event.country)}",
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(statusLabel(status), color = statusColor(status), style = MaterialTheme.typography.labelMedium)
            }
            Text(event.localizedName(LocalConfiguration.current.locales[0]), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (event.actual == null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            stringResource(R.string.previous_value, event.localizedValue(event.previous)),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            stringResource(R.string.consensus_value, event.localizedValue(event.consensus)),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    ImportanceDots(event.importance)
                }
            } else {
                Text(stringResource(R.string.actual_consensus, event.localizedValue(event.actual), event.localizedValue(event.consensus)), style = MaterialTheme.typography.bodyLarge)
                surprise?.let {
                    Text(
                        text = if (it.signum() == 0) stringResource(R.string.surprise_equal) else stringResource(
                            if (it.signum() > 0) R.string.surprise_above else R.string.surprise_below,
                            signed(it, if (event.unit == "%") "%" else ""),
                        ),
                        color = when { it.signum() > 0 -> AssetUp; it.signum() < 0 -> AssetDown; else -> MaterialTheme.colorScheme.onSurfaceVariant },
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

@Composable
fun ImportanceDots(importance: Int) {
    Text("●".repeat(importance.coerceIn(0, 3)), color = importanceColor(importance))
}

@Composable
private fun statusColor(status: String) = when (status) {
    "watching" -> Upcoming
    "released", "collecting_market_data", "analyzing" -> Dovish
    "completed" -> AssetUp
    "timeout" -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun importanceColor(importance: Int) = when (importance) {
    3 -> Upcoming
    2 -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
