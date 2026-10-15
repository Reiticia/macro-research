package com.macroresearch.ui.common.terminal

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.macroresearch.ui.common.statusLabel
import com.macroresearch.ui.theme.AssetUp
import com.macroresearch.ui.theme.Dovish
import com.macroresearch.ui.theme.Upcoming

enum class StatusTone { Pending, InFlight, Done, Failed, Neutral }

/** 未知状态（例如更新的后端新增的值）归为中性，原样显示文字。 */
internal fun statusTone(status: String): StatusTone = when (status) {
    "scheduled", "watching" -> StatusTone.Pending
    "released", "collecting_market_data", "analyzing" -> StatusTone.InFlight
    "completed" -> StatusTone.Done
    "timeout" -> StatusTone.Failed
    else -> StatusTone.Neutral
}

@Composable
fun StatusTag(status: String, modifier: Modifier = Modifier) {
    val color = when (statusTone(status)) {
        StatusTone.Pending -> Upcoming
        StatusTone.InFlight -> Dovish
        StatusTone.Done -> AssetUp
        StatusTone.Failed -> MaterialTheme.colorScheme.error
        StatusTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        statusLabel(status),
        modifier
            .border(1.dp, color, MaterialTheme.shapes.extraSmall)
            .padding(horizontal = 5.dp, vertical = 1.dp),
        color = color,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
