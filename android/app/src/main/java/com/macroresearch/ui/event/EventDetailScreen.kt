package com.macroresearch.ui.event

import androidx.compose.ui.res.stringResource
import com.macroresearch.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.macroresearch.data.MacroRepository
import com.macroresearch.data.CalendarWarning
import com.macroresearch.data.model.EconomicEvent
import com.macroresearch.data.model.currentStatus
import com.macroresearch.data.model.MarketReaction
import com.macroresearch.data.model.MarketResponse
import com.macroresearch.data.model.MarketSnapshot
import com.macroresearch.ui.EventDetailViewModel
import com.macroresearch.ui.ReleaseFetchOutcome
import com.macroresearch.ui.common.assetLabel
import com.macroresearch.ui.common.categoryLabel
import com.macroresearch.ui.common.countryLabel
import com.macroresearch.ui.common.calendarWarningMessage
import com.macroresearch.ui.common.importanceLabel
import com.macroresearch.ui.common.localizedCountdown as countdown
import com.macroresearch.ui.common.flag
import com.macroresearch.ui.common.localizedChange as formatChange
import com.macroresearch.ui.common.localTime
import com.macroresearch.ui.common.localizedName
import com.macroresearch.ui.common.statusLabel
import com.macroresearch.ui.common.localizedValue as value
import com.macroresearch.ui.theme.AssetDown
import com.macroresearch.ui.theme.AssetUp
import com.macroresearch.ui.theme.Upcoming
import com.macroresearch.ui.viewModelFactory
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDetailScreen(
    id: Long,
    repository: MacroRepository,
    onBack: () -> Unit,
    onAnalysis: () -> Unit,
    onHistory: () -> Unit,
) {
    val vm: EventDetailViewModel = viewModel(key = "event-$id", factory = viewModelFactory { EventDetailViewModel(id, repository) })
    val state by vm.state.collectAsStateWithLifecycle()
    val event = state.detail?.event
    val warning by repository.calendarWarning.collectAsStateWithLifecycle()
    val locale = LocalConfiguration.current.locales[0]
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(event?.localizedName(locale) ?: stringResource(R.string.event_detail), fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) } },
                actions = {
                    IconButton(onClick = vm::toggleFollowed) {
                        Icon(if (state.followed) Icons.Filled.Star else Icons.Outlined.StarBorder, stringResource(R.string.follow), tint = if (state.followed) Upcoming else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.loading && event == null -> Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { CircularProgressIndicator() }
            event == null -> Column(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                Text(stringResource(R.string.event_load_failed), style = MaterialTheme.typography.titleLarge)
                Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error)
            }
            else -> {
                EventContent(
                    event = event,
                    market = state.market,
                    modifier = Modifier.padding(padding),
                    onAnalysis = onAnalysis,
                    onHistory = onHistory,
                    warning = warning,
                    releaseFetching = state.releaseFetching,
                    releaseOutcome = state.releaseOutcome,
                    releaseError = state.releaseError,
                    onFetchRelease = vm::fetchRelease,
                )
            }
        }
    }
}

@Composable
private fun EventContent(
    event: EconomicEvent,
    market: MarketResponse?,
    modifier: Modifier,
    onAnalysis: () -> Unit,
    onHistory: () -> Unit,
    warning: CalendarWarning?,
    releaseFetching: Boolean,
    releaseOutcome: ReleaseFetchOutcome?,
    releaseError: String?,
    onFetchRelease: () -> Unit,
) {
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("${flag(event.country)}  ${countryLabel(event.country)}", style = MaterialTheme.typography.titleMedium)
                    Text(categoryLabel(event.category), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("${importanceLabel(event.importance)}  ${"●".repeat(event.importance.coerceIn(0, 3))}", color = Upcoming, style = MaterialTheme.typography.labelMedium)
            }
        }
        item { CountdownCard(event) }
        if (event.currentStatus() == "data_unavailable") {
            item {
                ReleaseRetryCard(
                    fetching = releaseFetching,
                    outcome = releaseOutcome,
                    error = releaseError,
                    warning = warning,
                    onFetch = onFetchRelease,
                )
            }
        }
        item { ReleaseDataCard(event) }
        item { EventIntroduction(event) }
        item { MarketTrackingCard(event, market) }
        item {
            Button(
                onClick = onAnalysis,
                modifier = Modifier.fillMaxWidth(),
                // A past timestamp alone is not a published data release (for example speeches
                // never have an actual value). Only events with a result can be analyzed.
                enabled = event.actual != null,
            ) { Text(stringResource(if (event.status in setOf("completed", "historical")) R.string.view_analysis else R.string.view_analysis_progress)) }
        }
        item {
            Card(onClick = onHistory, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.view_history), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text("›")
                }
            }
        }
        item { Column(Modifier.padding(bottom = 24.dp)) {} }
    }
}

/** Manual recovery for an elapsed event whose published value is still missing. */
@Composable
private fun ReleaseRetryCard(
    fetching: Boolean,
    outcome: ReleaseFetchOutcome?,
    error: String?,
    warning: CalendarWarning?,
    onFetch: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(R.string.release_data_missing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            outcome?.let {
                Text(
                    stringResource(
                        if (it == ReleaseFetchOutcome.RETRIEVED) R.string.release_fetch_updated
                        else R.string.release_fetch_missing,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (it == ReleaseFetchOutcome.RETRIEVED) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                )
            }
            // The source warning explains why a retry still could not fill the value.
            warning?.let {
                Text(
                    calendarWarningMessage(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            error?.let {
                Text(
                    stringResource(R.string.release_fetch_failed, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            OutlinedButton(onClick = onFetch, enabled = !fetching, modifier = Modifier.fillMaxWidth()) {
                if (fetching) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.Refresh, contentDescription = null)
                }
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (fetching) R.string.release_fetching else R.string.release_fetch_action))
            }
        }
    }
}

@Composable
private fun CountdownCard(event: EconomicEvent) {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(event.id) { while (true) { now = Instant.now(); delay(1_000) } }
    val awaitingFutureRelease = event.actual == null && runCatching {
        Instant.parse(event.eventTime).isAfter(now)
    }.getOrDefault(false)
    Card(colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    )) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(stringResource(if (awaitingFutureRelease) R.string.release_countdown else R.string.data_status), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (awaitingFutureRelease) countdown(event.eventTime, now) else statusLabel(event.currentStatus(now)),
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (event.status == "watching") Upcoming else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(event.localTime(), fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.local_time), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ReleaseDataCard(event: EconomicEvent) {
    Card {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            DataValue(stringResource(R.string.actual), event.value(event.actual), event.actual != null)
            DataValue(stringResource(R.string.consensus), event.value(event.consensus))
            DataValue(stringResource(R.string.forecast), event.value(event.forecast))
            DataValue(stringResource(R.string.previous), event.value(event.previous))
        }
    }
}

@Composable
private fun DataValue(label: String, value: String, highlight: Boolean = false) = Column {
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(value, style = MaterialTheme.typography.titleMedium, color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
}

@Composable
private fun EventIntroduction(event: EconomicEvent) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.event_intro), fontWeight = FontWeight.Bold)
            val introText = if (event.category.equals("calendar", ignoreCase = true)) {
                stringResource(
                    R.string.event_intro_nonnumeric,
                    countryLabel(event.country),
                    categoryLabel(event.category),
                )
            } else {
                stringResource(
                    R.string.event_intro_body,
                    countryLabel(event.country),
                    categoryLabel(event.category),
                )
            }
            Text(
                introText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal val MARKET_TRACKING_SYMBOLS =
    listOf("gold", "dxy", "us2y", "us10y", "nasdaq100", "bitcoin", "wti", "natural_gas")

/** States that may still deliver quotes; their rows stay visible even while they are empty. */
internal fun marketTrackingAwaitingData(status: String): Boolean =
    status in setOf("scheduled", "released", "collecting_market_data", "analyzing")

/**
 * Rows shown in the market card. Live states keep the full placeholder list; terminal states
 * only list symbols that produced a snapshot or a reaction, so an event the backend never
 * tracked no longer renders eight "--" rows that read as a malfunction.
 */
internal fun visibleMarketSymbols(
    status: String,
    snapshots: List<MarketSnapshot>,
    reactions: List<MarketReaction>,
): List<String> {
    if (marketTrackingAwaitingData(status)) return MARKET_TRACKING_SYMBOLS
    val available = snapshots.mapTo(hashSetOf()) { it.symbol } +
        reactions.mapTo(hashSetOf()) { it.symbol }
    return MARKET_TRACKING_SYMBOLS.filter { it in available }
}

@Composable
private fun MarketTrackingCard(event: EconomicEvent, market: MarketResponse?) {
    val status = event.currentStatus()
    val latest = market?.snapshots.orEmpty().groupBy { it.symbol }.mapValues { it.value.maxByOrNull { row -> row.timestamp } }
    val reactions = market?.reactions.orEmpty().associateBy { it.symbol }
    val rows = visibleMarketSymbols(status, market?.snapshots.orEmpty(), market?.reactions.orEmpty())
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.market_tracking), fontWeight = FontWeight.Bold)
                Text(statusLabel(status), color = MaterialTheme.colorScheme.primary)
            }
            HorizontalDivider()
            rows.forEach { symbol ->
                val reaction = reactions[symbol]
                val change = reaction?.change5m ?: reaction?.change1m
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(assetLabel(symbol))
                    // Backfilled events keep only a reaction: its baseline is the pre-release close.
                    Text(
                        latest[symbol]?.price?.let { "%,.2f".format(it) }
                            ?: reaction?.baselinePrice?.let { "%,.2f".format(it) }
                            ?: "--"
                    )
                    Text(
                        formatChange(change, reaction?.reactionUnit ?: "percent"),
                        color = when { change == null -> MaterialTheme.colorScheme.onSurfaceVariant; change >= 0 -> AssetUp; else -> AssetDown },
                    )
                }
            }
            if (rows.isEmpty()) {
                Text(
                    stringResource(R.string.market_data_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (status == "historical") {
                Text(stringResource(R.string.historical_method), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                AnalysisProgress(event.eventTime, status)
            }
        }
    }
}

@Composable
private fun AnalysisProgress(eventTime: String, status: String) {
    val elapsed = runCatching { Duration.between(Instant.parse(eventTime), Instant.now()).toMinutes() }.getOrDefault(-1)
    Text(stringResource(R.string.analysis_progress), fontWeight = FontWeight.Bold)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf(1L, 5L, 15L, 30L, 60L).forEach { horizon ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.minutes_short, horizon), style = MaterialTheme.typography.labelSmall)
                Text(
                    if (elapsed >= horizon || status == "completed") "✓" else "--",
                    color = if (elapsed >= horizon || status == "completed") MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
