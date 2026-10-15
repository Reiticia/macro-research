package com.macroresearch.ui.common.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.macroresearch.ui.theme.ResearchLayout
import com.macroresearch.ui.theme.ResearchType

data class Metric(
    val label: String,
    val value: String,
    val emphasis: Boolean = false,
    val labelColor: Color = Color.Unspecified,
)

/** 等宽多格数值行；每个值是独立文本节点，长值换行而不是省略，避免隐藏数据。 */
@Composable
fun MetricGrid(metrics: List<Metric>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Hairline()
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            metrics.forEachIndexed { index, metric ->
                if (index > 0) VerticalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                MetricCell(metric, Modifier.weight(1f).fillMaxHeight())
            }
        }
        Hairline()
    }
}

@Composable
fun MetricCell(metric: Metric, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(horizontal = ResearchLayout.smallGap, vertical = ResearchLayout.denseGap),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            metric.label,
            style = MaterialTheme.typography.labelSmall,
            color = metric.labelColor.takeOrElse { MaterialTheme.colorScheme.onSurfaceVariant },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            metric.value,
            style = ResearchType.large,
            color = if (metric.emphasis) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}
