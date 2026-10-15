package com.macroresearch.ui.common.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.macroresearch.R
import com.macroresearch.data.model.EconomicEvent
import com.macroresearch.data.model.currentStatus
import com.macroresearch.ui.common.ImportanceDots
import com.macroresearch.ui.common.flag
import com.macroresearch.ui.common.localNumericDate
import com.macroresearch.ui.common.localTime
import com.macroresearch.ui.common.localizedName
import com.macroresearch.ui.common.localizedValue
import com.macroresearch.ui.common.marketExpectation
import com.macroresearch.ui.common.surprise
import com.macroresearch.ui.theme.ResearchLayout
import com.macroresearch.ui.theme.ResearchType

/** 表头与行共用同一组列宽，随字号缩放。 */
private data class EventColumns(val time: Dp, val value: Dp, val actual: Dp)

@Composable
private fun eventColumns() = EventColumns(
    time = monoColumnWidth(5.5f, 40.dp),
    value = monoColumnWidth(7f, 52.dp),
    actual = monoColumnWidth(9f, 58.dp),
)

enum class SurpriseMarker { Above, Below, None }

/** 只表示公布值相对预期（缺失时为预测）的方向，不代表资产涨跌，界面上使用中性色。 */
internal fun surpriseMarker(event: EconomicEvent): SurpriseMarker = when (event.surprise()?.signum()) {
    1 -> SurpriseMarker.Above
    -1 -> SurpriseMarker.Below
    else -> SurpriseMarker.None
}

/** 宽屏表头；窄屏/大字体时数值折到事件名下方并自带标签，因此不显示表头。 */
@Composable
fun EventTableHeader(modifier: Modifier = Modifier) {
    if (ResearchLayout.stackMetadata) return
    val columns = eventColumns()
    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = ResearchLayout.pagePadding, vertical = ResearchLayout.denseGap),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            HeaderCell(stringResource(R.string.table_time), Modifier.width(columns.time))
            HeaderCell(stringResource(R.string.table_event), Modifier.weight(1f))
            HeaderCell(stringResource(R.string.table_previous), Modifier.width(columns.value), TextAlign.End)
            HeaderCell(stringResource(R.string.table_expectation), Modifier.width(columns.value), TextAlign.End)
            HeaderCell(stringResource(R.string.table_actual), Modifier.width(columns.actual), TextAlign.End)
        }
        Hairline()
    }
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier, align: TextAlign = TextAlign.Start) = Text(
    text,
    modifier,
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    textAlign = align,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
)

private data class EventRowValues(
    val previous: String,
    val expectation: String,
    val actual: String,
    val marker: SurpriseMarker,
)

@Composable
fun EventRow(
    event: EconomicEvent,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showDate: Boolean = false,
) {
    val values = EventRowValues(
        previous = event.localizedValue(event.previous),
        expectation = event.localizedValue(event.marketExpectation()),
        actual = event.localizedValue(event.actual),
        marker = surpriseMarker(event),
    )
    // clickable 会合并子节点语义，读屏时整行作为一个可点击项。
    Column(modifier.fillMaxWidth().clickable(onClick = onClick)) {
        if (ResearchLayout.stackMetadata) StackedEventRow(event, values, showDate) else WideEventRow(event, values, showDate)
        Hairline()
    }
}

@Composable
private fun WideEventRow(event: EconomicEvent, values: EventRowValues, showDate: Boolean) {
    val columns = eventColumns()
    val previousDescription = "${stringResource(R.string.table_previous)} ${values.previous}"
    val expectationDescription = "${stringResource(R.string.table_expectation)} ${values.expectation}"
    val actualDescription = "${stringResource(R.string.table_actual)} ${values.actual}"
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = ResearchLayout.pagePadding, vertical = ResearchLayout.rowVertical),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            if (showDate) "${event.localNumericDate()}\n${event.localTime()}" else event.localTime(),
            Modifier.width(columns.time),
            style = ResearchType.small,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            EventName(event, MaterialTheme.typography.bodyMedium)
            EventMeta(event)
        }
        Text(
            values.previous,
            Modifier.width(columns.value).semantics { contentDescription = previousDescription },
            style = ResearchType.small,
            textAlign = TextAlign.End,
        )
        Text(
            values.expectation,
            Modifier.width(columns.value).semantics { contentDescription = expectationDescription },
            style = ResearchType.small,
            textAlign = TextAlign.End,
        )
        Row(Modifier.width(columns.actual), horizontalArrangement = Arrangement.End) {
            Text(
                values.actual,
                Modifier.weight(1f, fill = false).semantics { contentDescription = actualDescription },
                style = ResearchType.small,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.End,
            )
            SurpriseGlyph(values.marker)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StackedEventRow(event: EconomicEvent, values: EventRowValues, showDate: Boolean) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = ResearchLayout.pagePadding, vertical = ResearchLayout.rowVertical),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (showDate) "${event.localNumericDate()} ${event.localTime()}" else event.localTime(),
                style = ResearchType.small,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            EventMeta(event)
        }
        EventName(event, MaterialTheme.typography.bodyLarge)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            LabeledValue(stringResource(R.string.table_previous), values.previous)
            LabeledValue(stringResource(R.string.table_expectation), values.expectation)
            Row(verticalAlignment = Alignment.CenterVertically) {
                LabeledValue(stringResource(R.string.table_actual), values.actual, bold = true)
                SurpriseGlyph(values.marker)
            }
        }
    }
}

@Composable
private fun EventName(event: EconomicEvent, style: TextStyle) {
    Row {
        Text(flag(event.country), style = style)
        Spacer(Modifier.width(4.dp))
        Text(
            event.localizedName(LocalConfiguration.current.locales[0]),
            style = style,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EventMeta(event: EconomicEvent) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ImportanceDots(event.importance)
        StatusTag(event.currentStatus(), Modifier.weight(1f, fill = false))
    }
}

@Composable
private fun LabeledValue(label: String, value: String, bold: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = ResearchType.body, fontWeight = if (bold) FontWeight.Bold else null)
    }
}

@Composable
private fun SurpriseGlyph(marker: SurpriseMarker) {
    if (marker == SurpriseMarker.None) return
    val description = stringResource(
        if (marker == SurpriseMarker.Above) R.string.above_consensus else R.string.below_consensus,
    )
    Text(
        if (marker == SurpriseMarker.Above) " ▲" else " ▼",
        Modifier.semantics { contentDescription = description },
        style = ResearchType.small,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
