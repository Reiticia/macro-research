package com.macroresearch.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.macroresearch.R
import com.macroresearch.data.model.EconomicEvent
import com.macroresearch.data.model.currentStatus
import com.macroresearch.ui.common.localizedDate as localDate
import com.macroresearch.ui.common.localizedValue as value
import com.macroresearch.ui.theme.AssetDown
import com.macroresearch.ui.theme.AssetUp
import com.macroresearch.ui.theme.ResearchLayout
import com.macroresearch.ui.theme.TabularStyle
import com.macroresearch.ui.theme.Upcoming

@Composable
fun EventCard(event: EconomicEvent, onClick: () -> Unit, showDate: Boolean = false) {
    val locale = LocalConfiguration.current.locales[0]
    val status = event.currentStatus()
    val railColor = when (event.importance) {
        3 -> Upcoming
        2 -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outlineVariant
    }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(ResearchLayout.cardCorner),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
        ) {
            // Left Time & Jurisdiction Rail
            Column(
                modifier = Modifier
                    .width(82.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (showDate) {
                        Text(
                            text = event.localDate().substringBefore("·").trim(),
                            style = MaterialTheme.typography.labelSmall.merge(TabularStyle),
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = event.localTime(),
                        style = MaterialTheme.typography.titleMedium.merge(TabularStyle),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                    Text(
                        text = "${flag(event.country)} ${countryLabel(event.country)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                ImportanceDots(
                    importance = event.importance,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            // Structural Importance Rail Divider
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(railColor),
            )

            // Right Event Title & Tabular Metric Matrix
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = event.localizedName(locale),
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    StatusPill(status)
                }

                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MatrixCell(
                        label = stringResource(R.string.previous),
                        value = event.value(event.previous),
                        modifier = Modifier.weight(1f),
                    )
                    MatrixCell(
                        label = stringResource(R.string.consensus),
                        value = event.value(event.consensus),
                        modifier = Modifier.weight(1f),
                    )
                    if (!event.actual.isNullOrBlank()) {
                        val surpriseSign = event.surprise()?.signum() ?: 0
                        val actualColor = when {
                            surpriseSign > 0 -> AssetUp
                            surpriseSign < 0 -> AssetDown
                            else -> MaterialTheme.colorScheme.primary
                        }
                        ActualMatrixCell(
                            label = stringResource(R.string.actual),
                            value = event.value(event.actual),
                            color = actualColor,
                            modifier = Modifier.weight(1f),
                        )
                    } else if (!event.forecast.isNullOrBlank() && event.forecast != event.consensus) {
                        MatrixCell(
                            label = stringResource(R.string.forecast),
                            value = event.value(event.forecast),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MatrixCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.merge(TabularStyle),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

@Composable
private fun ActualMatrixCell(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = 0.11f),
        border = BorderStroke(0.5.dp, color.copy(alpha = 0.35f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall.merge(TabularStyle),
                color = color,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun StatusPill(status: String) {
    val label = statusLabel(status)
    val (bg, fg, border) = when (status) {
        "completed", "released" -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
        )
        "watching", "collecting_market_data", "analyzing" -> Triple(
            Upcoming.copy(alpha = 0.12f),
            Upcoming,
            Upcoming.copy(alpha = 0.4f),
        )
        else -> Triple(
            MaterialTheme.colorScheme.surfaceContainerLow,
            MaterialTheme.colorScheme.onSurfaceVariant,
            MaterialTheme.colorScheme.outlineVariant,
        )
    }
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = bg,
        border = BorderStroke(0.5.dp, border),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = fg,
            maxLines = 1,
        )
    }
}

@Composable
fun ImportanceDots(importance: Int, modifier: Modifier = Modifier) {
    val count = importance.coerceIn(1, 3)
    val label = importanceLabel(importance)
    val activeColor = when (count) {
        3 -> Upcoming
        2 -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = label
        },
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        for (level in 1..3) {
            val active = level <= count
            val barHeight = when (level) {
                1 -> 6.dp
                2 -> 9.dp
                else -> 12.dp
            }
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(barHeight)
                    .clip(RoundedCornerShape(1.dp))
                    .background(
                        if (active) activeColor
                        else MaterialTheme.colorScheme.outlineVariant,
                    ),
            )
        }
    }
}
