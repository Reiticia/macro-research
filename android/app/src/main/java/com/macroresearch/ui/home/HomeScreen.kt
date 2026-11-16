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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.TextButton
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.macroresearch.ui.common.EventTimes
import com.macroresearch.ui.common.localizedName
import com.macroresearch.ui.common.localizedValue as value
import com.macroresearch.ui.market.formatMarketQuoteChange
import com.macroresearch.ui.market.formatMarketQuoteValue
import com.macroresearch.ui.theme.AssetDown
import com.macroresearch.ui.theme.AssetUp
import com.macroresearch.ui.theme.Upcoming
import com.macroresearch.ui.theme.ResearchLayout
import com.macroresearch.ui.viewModelFactory
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun HomeScreen(
    repository: MacroRepository,
    padding: PaddingValues,
    onEvent: (Long) -> Unit,
    onFollowedEvents: () -> Unit,
    onCalendar: () -> Unit = {},
    onMarket: () -> Unit,
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
    val failed by vm.marketFailed.collectAsStateWithLifecycle()
    val source by repository.dataSourceSettings.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var now by remember { mutableStateOf(Instant.now()) }
    val next = events.firstOrNull { it.timeExact && it.importance == 3 && runCatching { Instant.parse(it.eventTime) > now }.getOrDefault(false) }
        ?: events.firstOrNull { it.timeExact && runCatching { Instant.parse(it.eventTime) > now }.getOrDefault(false) }
    val todayDate = now.atZone(ZoneId.systemDefault()).toLocalDate()
    val today = events.filter {
        runCatching { Instant.parse(it.eventTime).atZone(ZoneId.systemDefault()).toLocalDate() == todayDate }.getOrDefault(false)
    }
    LaunchedEffect(source, selectedMarkets, lifecycle) {
        vm.resetSource()
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                vm.refresh()
                while (true) {
                    now = Instant.now()
                    vm.loadLiveMarketAndWait(selectedMarkets)
                    delay(5_000)
                }
            } finally { vm.stopRefreshing() }
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(ResearchLayout.pagePadding),
        verticalArrangement = Arrangement.spacedBy(ResearchLayout.gap),
    ) {
        item(key = "header") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Macro", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(com.macroresearch.ui.common.dateLabel(todayDate), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { vm.refresh(force = true) }, enabled = !refresh.loading) {
                    if (refresh.loading && events.isNotEmpty()) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(Icons.Outlined.Refresh, stringResource(R.string.home_refresh))
                    }
                }
                IconButton(onClick = onFollowedEvents) {
                    Icon(Icons.Outlined.Notifications, stringResource(R.string.followed_events))
                }
            }
        }
        item(key = "source") {
            Text(
                stringResource(if (source.mode == com.macroresearch.data.DataSourceMode.BACKEND) R.string.data_source_backend else R.string.data_source_direct),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            )
        }
        if (refresh.loading && events.isEmpty()) item(key = "loading") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
        }
        notice?.let { item(key = "notice") {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(ResearchLayout.cardPadding)) {
                    Text(it, color = MaterialTheme.colorScheme.onErrorContainer)
                    TextButton(onClick = { vm.refresh(force = true) }, enabled = !refresh.loading) { Text(stringResource(R.string.retry)) }
                }
            }
        } }
        if (next != null) item(key = "next") { NextEventCard(next) { onEvent(next.id) } }
        item(key = "markets") {
            MarketOverview(liveMarket?.quotes.orEmpty(), selectedMarkets, onMarket)
            if (failed) Text(stringResource(R.string.quotes_refresh_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        item(key = "today") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.today_events), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.event_count, today.size), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (today.isEmpty() && !refresh.loading && notice == null) item(key = "empty") {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(ResearchLayout.cardPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.home_empty_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.home_empty_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onCalendar) { Text(stringResource(R.string.nav_calendar)) }
                }
            }
        }
        items(today, key = { it.id }) { event -> EventCard(event, { onEvent(event.id) }) }
    }
}

@Composable
internal fun MarketOverview(
    liveQuotes: List<LiveMarketQuote>,
    selectedMarkets: List<String>,
    onMarket: () -> Unit,
) {
    val live = liveQuotes.associateBy { it.symbol }
    // All selected markets stay visible; only the parent home page scrolls.
    val fontScale = LocalDensity.current.fontScale
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.market_overview), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = marketOverviewColumns(maxWidth.value, fontScale, selectedMarkets.size)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                selectedMarkets.chunked(columns).forEach { symbols ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        symbols.forEach { symbol ->
                            MarketMiniCard(symbol, assetLabel(symbol), live[symbol], onMarket, Modifier.weight(1f))
                        }
                        repeat(columns - symbols.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/** Default three across, four in a 2x2 grid; large fonts reduce columns, never font size. */
internal fun marketOverviewColumns(widthDp: Float, fontScale: Float, count: Int): Int {
    val scale = fontScale.takeIf { it.isFinite() && it > 0 } ?: 1f
    val width = widthDp.takeIf { it.isFinite() && it > 0 } ?: 360f
    val preferred = if (count == 4) 2 else count.coerceIn(1, 3)
    val readable = ((width + 6f) / (112f * scale.coerceAtLeast(1f) + 6f)).toInt().coerceAtLeast(1)
    return minOf(preferred, readable)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NextEventCard(event: EconomicEvent, onClick: () -> Unit) {
    var now by remember { mutableStateOf(Instant.now()) }
    val locale = LocalConfiguration.current.locales[0]
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(event.id, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { now = Instant.now(); delay(1_000) }
        }
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
                    if (event.timeExact) countdown(event.eventTime, now) else stringResource(R.string.event_time_pending),
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
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            EventTimes(event, showDate = true)
            // Keep the source values in the compact summary, separate from detail-only impact.
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ResearchLayout.smallGap),
                verticalArrangement = Arrangement.spacedBy(ResearchLayout.denseGap),
                maxItemsInEachRow = if (ResearchLayout.stackMetadata) 1 else Int.MAX_VALUE,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(importanceLabel(event.importance), color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(ResearchLayout.smallGap))
                    ImportanceDots(event.importance)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(ResearchLayout.denseGap)) {
                    InlineValue(stringResource(R.string.previous), event.value(event.previous))
                    InlineValue(stringResource(R.string.consensus), event.value(event.consensus))
                    InlineValue(stringResource(R.string.forecast), event.value(event.forecast))
                    if (!event.actual.isNullOrBlank()) {
                        InlineValue(stringResource(R.string.actual), event.value(event.actual))
                    }
                }
            }
        }
    }
}

@Composable
private fun InlineValue(label: String, value: String) = Row(verticalAlignment = Alignment.CenterVertically) {
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.width(4.dp))
    Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun MarketMiniCard(
    symbol: String,
    label: String,
    liveQuote: LiveMarketQuote?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = LocalConfiguration.current.locales[0]
    val basisPointsLabel = stringResource(R.string.basis_points)
    val marketClosed = liveQuote?.marketState.equals("closed", ignoreCase = true)
    val price = liveQuote?.price
    Card(onClick = onClick, modifier = modifier.heightIn(min = 48.dp).testTag("home-market-$symbol"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(ResearchLayout.miniCardPadding),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Text(price?.let { formatMarketQuoteValue(symbol, it, locale) } ?: "—",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            val changeText = liveQuote?.takeIf { !it.stale && !marketClosed }
                ?.let { formatMarketQuoteChange(it, locale, basisPointsLabel) }
            val status = when {
                price == null -> stringResource(R.string.waiting_quotes)
                liveQuote.stale -> stringResource(R.string.market_stale)
                marketClosed -> stringResource(R.string.market_closed)
                else -> "—"
            }
            Text(
                changeText ?: status,
                color = if (changeText == null) MaterialTheme.colorScheme.secondary
                    else if ((liveQuote?.changePercent ?: 0.0) >= 0) AssetUp else AssetDown,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (changeText != null) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
