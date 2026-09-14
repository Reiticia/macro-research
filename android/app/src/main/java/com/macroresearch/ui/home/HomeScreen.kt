package com.macroresearch.ui.home

import androidx.compose.ui.res.stringResource
import com.macroresearch.R
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.macroresearch.data.MacroRepository
import com.macroresearch.data.model.EconomicEvent
import com.macroresearch.data.model.LiveMarketQuote
import com.macroresearch.ui.HomeViewModel
import com.macroresearch.ui.common.EventCard
import com.macroresearch.ui.common.ImportanceDots
import com.macroresearch.ui.common.assetLabel
import com.macroresearch.ui.common.calendarWarningMessage
import com.macroresearch.ui.common.importanceLabel
import com.macroresearch.ui.common.localizedCountdown as countdown
import com.macroresearch.ui.common.flag
import com.macroresearch.ui.common.localizedDate as localDate
import com.macroresearch.ui.common.localTime
import com.macroresearch.ui.common.localizedName
import com.macroresearch.ui.common.localizedValue as value
import com.macroresearch.ui.market.formatMarketPrice
import com.macroresearch.ui.theme.Upcoming
import com.macroresearch.ui.theme.ResearchLayout
import com.macroresearch.ui.viewModelFactory
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun HomeScreen(repository: MacroRepository, padding: PaddingValues, onEvent: (Long) -> Unit) {
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
    LaunchedEffect(selectedMarkets) {
        while (true) {
            vm.loadLiveMarket(selectedMarkets)
            delay(30_000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = ResearchLayout.pagePadding, vertical = ResearchLayout.gap),
        verticalArrangement = Arrangement.spacedBy(ResearchLayout.gap),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(
                    buildAnnotatedString {
                        append("Mac")
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append("ro") }
                    },
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(next?.localDate() ?: stringResource(R.string.research_tagline), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Outlined.Notifications, stringResource(R.string.notifications), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (next != null) NextEventCard(next) { onEvent(next.id) }
        MarketOverview(
            liveMarket?.quotes.orEmpty(),
            selectedMarkets,
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.today_events), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.event_count, today.size), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(ResearchLayout.gap),
            contentPadding = PaddingValues(bottom = 4.dp),
        ) {
            if (refresh.loading && events.isEmpty()) item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
            }
            // The repository publishes a typed reason; the raw provider error stays in the log.
            // A failure without one is not calendar-related, so fall back to the generic wording.
            notice?.let { item { Text(it, color = Upcoming) } }
            items(today, key = { it.id }) { event -> EventCard(event, { onEvent(event.id) }) }
        }
    }
}

@Composable
private fun MarketOverview(
    liveQuotes: List<LiveMarketQuote>,
    selectedMarkets: List<String>,
) {
    val live = liveQuotes.associateBy { it.symbol }
    val rows = marketOverviewRows(selectedMarkets)
    // Glanceable strip: compact header and one dense row per market line, so the event list below
    // keeps most of the screen.
    Column(verticalArrangement = Arrangement.spacedBy(ResearchLayout.denseGap)) {
        Text(stringResource(R.string.market_overview), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Column(verticalArrangement = Arrangement.spacedBy(ResearchLayout.denseGap)) {
            rows.forEach { rowMarkets ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ResearchLayout.denseGap)) {
                    rowMarkets.forEach { symbol ->
                        MarketMiniCard(
                            label = assetLabel(symbol),
                            liveQuote = live[symbol],
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

internal fun marketOverviewRows(selectedMarkets: List<String>): List<List<String>> {
    val columns = if (selectedMarkets.size == 4) 2 else selectedMarkets.size.coerceAtLeast(1)
    return selectedMarkets.chunked(columns)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NextEventCard(event: EconomicEvent, onClick: () -> Unit) {
    var now by remember { mutableStateOf(Instant.now()) }
    val locale = LocalConfiguration.current.locales[0]
    LaunchedEffect(event.id) {
        while (true) { now = Instant.now(); delay(1_000) }
    }
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
    ) {
        Column(Modifier.padding(ResearchLayout.cardPadding), verticalArrangement = Arrangement.spacedBy(ResearchLayout.denseGap)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.next_event),
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    countdown(event.eventTime, now),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
            Text(
                "${flag(event.country)}  ${event.localizedName(locale)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Schedule and values share one flow row: the hero card stays short, and a large system
            // font scale stacks the entries instead of clipping them.
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ResearchLayout.smallGap),
                verticalArrangement = Arrangement.spacedBy(ResearchLayout.denseGap),
                maxItemsInEachRow = if (ResearchLayout.stackMetadata) 1 else Int.MAX_VALUE,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(event.localTime(), style = MaterialTheme.typography.labelLarge)
                    Text(" · ${importanceLabel(event.importance)}", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(ResearchLayout.smallGap))
                    ImportanceDots(event.importance)
                }
                InlineValue(stringResource(R.string.previous), event.value(event.previous))
                InlineValue(stringResource(R.string.consensus), event.value(event.consensus))
                InlineValue(stringResource(R.string.forecast), event.value(event.forecast))
            }
        }
    }
}

/** Label and value on a single line keep the hero card short; the label stays secondary. */
@Composable
private fun InlineValue(label: String, value: String) = Row(verticalAlignment = Alignment.CenterVertically) {
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.width(4.dp))
    Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun MarketMiniCard(
    label: String,
    liveQuote: LiveMarketQuote?,
    modifier: Modifier = Modifier,
) {
    val locale = LocalConfiguration.current.locales[0]
    val price = liveQuote?.price
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(
            modifier = Modifier.padding(
                horizontal = ResearchLayout.miniCardPadding,
                vertical = ResearchLayout.denseGap,
            ),
            horizontalArrangement = Arrangement.spacedBy(ResearchLayout.denseGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(
                        when {
                            price == null -> R.string.waiting_quotes
                            liveQuote?.stale == true -> R.string.market_stale
                            else -> R.string.live_snapshot
                        },
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                price?.let { formatMarketPrice(it, locale) } ?: "--",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}
