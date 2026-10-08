package com.macroresearch.ui.common

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
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.macroresearch.data.model.EconomicEvent
import com.macroresearch.data.model.currentStatus
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
                Surface(shape = RoundedCornerShape(6.dp), color = statusColor(status).copy(alpha = 0.08f)) {
                    Text(statusLabel(status), Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        color = statusColor(status), style = MaterialTheme.typography.labelMedium)
                }
            }
            // Lists are schedule summaries only. Values, surprises and asset impact live in detail.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(event.localizedName(LocalConfiguration.current.locales[0]),
                    Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                ImportanceDots(event.importance)
            }
        }
    }
}

@Composable
fun ImportanceDots(importance: Int) {
    val label = importanceLabel(importance)
    Text("●".repeat(importance.coerceIn(0, 3)),
        Modifier.clearAndSetSemantics { contentDescription = label },
        color = importanceColor(importance), style = MaterialTheme.typography.labelMedium)
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
