package com.macroresearch.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.macroresearch.R
import com.macroresearch.data.MacroRepository
import com.macroresearch.data.model.EconomicEvent
import com.macroresearch.data.model.LiveMarketQuote
import com.macroresearch.ui.HomeViewModel
import com.macroresearch.ui.common.ImportanceDots
import com.macroresearch.ui.common.LoadingHint
import com.macroresearch.ui.common.assetLabel
import com.macroresearch.ui.common.calendarWarningMessage
import com.macroresearch.ui.common.flag
import com.macroresearch.ui.common.importanceLabel
import com.macroresearch.ui.common.localTime
import com.macroresearch.ui.common.localizedCountdown as countdown
import com.macroresearch.ui.common.localizedDate as localDate
import com.macroresearch.ui.common.localizedName
import com.macroresearch.ui.common.localizedValue as value
import com.macroresearch.ui.common.terminal.AccentBand
import com.macroresearch.ui.common.terminal.EventRow
import com.macroresearch.ui.common.terminal.EventTableHeader
import com.macroresearch.ui.common.terminal.Hairline
import com.macroresearch.ui.common.terminal.Metric
import com.macroresearch.ui.common.terminal.MetricGrid
import com.macroresearch.ui.common.terminal.NoticeStrip
import com.macroresearch.ui.common.terminal.PageHeader
import com.macroresearch.ui.common.terminal.QuoteCell
import com.macroresearch.ui.common.terminal.SectionHeader
import com.macroresearch.ui.theme.ResearchLayout
import com.macroresearch.ui.theme.ResearchType
import com.macroresearch.ui.viewModelFactory
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val ClockFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    repository: MacroRepository,
    padding: PaddingValues,
    onEvent: (Long) -> Unit,
    onFollowedEvents: () -> Unit,
) {
    val vm: HomeViewModel = viewModel(factory = viewModelFactory { HomeViewModel(repository) })
    val events by vm.events.collectAsStateWithLifecycle()
    val refresh by vm.refresh.collectAsStateWithLifecycle()
    val warning by repository.calendarWarning.collectAsStateWithLifecycle()
    // The repository publishes a typed reason; the raw provider error stays in the log. A failure
    // without one is not calendar-related, so fall back to the generic wording.
    val notice = warning?.let { calendarWarningMessage(it) }
        ?: if (refresh.error != null) stringResource(R.string.calendar_warning_offline) else null
    val liveMarket by vm.liveMarket.collectAsStateWithLifecycle()
    val selectedMarkets by repository.selectedMarkets.collectAsStateWithLifecycle()
    val now = Instant.now()
    val next = events.firstOrNull { it.importance == 3 && runCatching { Instant.parse(it.eventTime) > now }.getOrDefault(false) }
        ?: events.firstOrNull { runCatching { Instant.parse(it.eventTime) > now }.getOrDefault(false) }
    val today = events.filter {
        runCatching { Instant.parse(it.eventTime).atZone(ZoneId.systemDefault()).toLocalDate() == LocalDate.now() }
            .getOrDefault(false)
    }
    var clock by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) { clock = LocalTime.now(); delay(1_000) }
    }
    LaunchedEffect(selectedMarkets) {
        while (true) {
            vm.loadLiveMarketAndWait(selectedMarkets)
            delay(5_000)
        }
    }

    Column(Modifier.fillMaxSize().padding(padding)) {
        PageHeader(
            title = { BrandTitle() },
            subtitle = next?.localDate() ?: stringResource(R.string.research_tagline),
            actions = {
                Text(clock.format(ClockFormatter), style = ResearchType.small, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = onFollowedEvents) {
                    Icon(
                        Icons.Outlined.Notifications,
                        stringResource(R.string.followed_events),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = ResearchLayout.gap)) {
            if (next != null) item(key = "next") { NextEventCard(next) { onEvent(next.id) } }
            item(key = "market") { MarketOverview(liveMarket?.quotes.orEmpty(), selectedMarkets) }
            item(key = "today") {
                SectionHeader(
                    stringResource(R.string.today_events),
                    trailing = stringResource(R.string.event_count, today.size),
                )
            }
            notice?.let { item(key = "notice") { NoticeStrip(it) } }
            if (refresh.loading && events.isEmpty()) item(key = "loading") { LoadingHint() }
            stickyHeader(key = "table") { EventTableHeader() }
            items(today, key = { it.id }) { event -> EventRow(event, { onEvent(event.id) }) }
        }
    }
}

@Composable
private fun BrandTitle() = Text(
    buildAnnotatedString {
        append("MAC")
        withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append("RO") }
    },
    fontWeight = FontWeight.ExtraBold,
    letterSpacing = 0.6.sp,
)

@Composable
private fun MarketOverview(liveQuotes: List<LiveMarketQuote>, selectedMarkets: List<String>) {
    val live = liveQuotes.associateBy { it.symbol }
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(stringResource(R.string.market_overview))
        Hairline()
        marketOverviewRows(selectedMarkets).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                row.forEachIndexed { index, symbol ->
                    if (index > 0) VerticalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    QuoteCell(assetLabel(symbol), live[symbol], Modifier.weight(1f))
                }
                if (row.size == 1) {
                    VerticalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.weight(1f))
                }
            }
            Hairline()
        }
    }
}

/** 首页行情固定 2 列；奇数个品种时最后一行只占左半。 */
internal fun marketOverviewRows(selectedMarkets: List<String>): List<List<String>> = selectedMarkets.chunked(2)

@Composable
internal fun NextEventCard(event: EconomicEvent, onClick: () -> Unit) {
    var now by remember { mutableStateOf(Instant.now()) }
    val locale = LocalConfiguration.current.locales[0]
    LaunchedEffect(event.id) {
        while (true) { now = Instant.now(); delay(1_000) }
    }
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        AccentBand {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${stringResource(R.string.next_event)} · ${importanceLabel(event.importance)}",
                        Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    ImportanceDots(event.importance, decorative = true)
                }
                Text(event.localTime(), style = ResearchType.small, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ResearchLayout.smallGap),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    "${flag(event.country)} ${event.localizedName(locale)}",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(countdown(event.eventTime, now), style = ResearchType.hero, color = MaterialTheme.colorScheme.primary, maxLines = 1)
            }
        }
        // 保留四个来源值，与详情页的影响判断分开。
        MetricGrid(
            listOf(
                Metric(stringResource(R.string.previous), event.value(event.previous)),
                Metric(stringResource(R.string.consensus), event.value(event.consensus)),
                Metric(stringResource(R.string.forecast), event.value(event.forecast)),
                Metric(stringResource(R.string.actual), event.value(event.actual), emphasis = event.actual != null),
            ),
        )
    }
}
