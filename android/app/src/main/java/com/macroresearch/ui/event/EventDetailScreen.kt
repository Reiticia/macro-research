package com.macroresearch.ui.event

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.macroresearch.R
import com.macroresearch.data.CalendarWarning
import com.macroresearch.data.MacroRepository
import com.macroresearch.data.model.EconomicEvent
import com.macroresearch.data.model.EventDescription
import com.macroresearch.data.model.MarketReaction
import com.macroresearch.data.model.MarketResponse
import com.macroresearch.data.model.MarketSnapshot
import com.macroresearch.data.model.currentStatus
import com.macroresearch.data.model.hasEventTimeArrived
import com.macroresearch.ui.EventDetailViewModel
import com.macroresearch.ui.ReleaseFetchOutcome
import com.macroresearch.ui.common.ImportanceDots
import com.macroresearch.ui.common.ReleaseImpactLabels
import com.macroresearch.ui.common.assetLabel
import com.macroresearch.ui.common.calendarWarningMessage
import com.macroresearch.ui.common.categoryLabel
import com.macroresearch.ui.common.countryLabel
import com.macroresearch.ui.common.flag
import com.macroresearch.ui.common.importanceLabel
import com.macroresearch.ui.common.localTime
import com.macroresearch.ui.common.localizedChange as formatChange
import com.macroresearch.ui.common.localizedCountdown as countdown
import com.macroresearch.ui.common.localizedName
import com.macroresearch.ui.common.localizedValue as value
import com.macroresearch.ui.common.statusLabel
import com.macroresearch.ui.common.terminal.AccentBand
import com.macroresearch.ui.common.terminal.DetailTopBar
import com.macroresearch.ui.common.terminal.Hairline
import com.macroresearch.ui.common.terminal.Metric
import com.macroresearch.ui.common.terminal.MetricGrid
import com.macroresearch.ui.common.terminal.SectionHeader
import com.macroresearch.ui.common.terminal.TerminalButton
import com.macroresearch.ui.common.terminal.TerminalOutlinedButton
import com.macroresearch.ui.common.terminal.monoColumnWidth
import com.macroresearch.ui.theme.AssetDown
import com.macroresearch.ui.theme.AssetUp
import com.macroresearch.ui.theme.ResearchLayout
import com.macroresearch.ui.theme.ResearchType
import com.macroresearch.ui.theme.Upcoming
import com.macroresearch.ui.viewModelFactory
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant

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
            DetailTopBar(
                title = event?.localizedName(locale) ?: stringResource(R.string.event_detail),
                onBack = onBack,
                actions = {
                    IconButton(onClick = vm::toggleFollowed) {
                        Icon(
                            if (state.followed) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            stringResource(R.string.follow),
                            tint = if (state.followed) Upcoming else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.loading && event == null -> Column(
                Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator() }
            event == null -> Column(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                Text(stringResource(R.string.event_load_failed), style = MaterialTheme.typography.titleLarge)
                Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error)
            }
            else -> EventContent(
                event = event,
                market = state.market,
                description = state.detail?.description,
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

@Composable
private fun EventContent(
    event: EconomicEvent,
    market: MarketResponse?,
    description: EventDescription?,
    modifier: Modifier,
    onAnalysis: () -> Unit,
    onHistory: () -> Unit,
    warning: CalendarWarning?,
    releaseFetching: Boolean,
    releaseOutcome: ReleaseFetchOutcome?,
    releaseError: String?,
    onFetchRelease: () -> Unit,
) {
    var now by remember(event.id) { mutableStateOf(Instant.now()) }
    LaunchedEffect(event.id) { while (true) { now = Instant.now(); delay(1_000) } }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = ResearchLayout.gap * 2)) {
        item { EventHeader(event) }
        item { CountdownCard(event) }
        val missingActual = event.currentStatus() == "data_unavailable"
        val missingUnit = event.unit.isNullOrBlank() || event.unit?.trim() in
            setOf("$", "USD", "€", "EUR", "£", "GBP", "number", "currency")
        if (missingActual || (missingUnit && hasEventTimeArrived(event.eventTime, now))) {
            item {
                ReleaseRetryCard(
                    unitRecovery = !missingActual,
                    fetching = releaseFetching,
                    outcome = releaseOutcome,
                    error = releaseError,
                    warning = warning,
                    onFetch = onFetchRelease,
                )
            }
        }
        item { ReleaseDataCard(event) }
        item {
            Column {
                SectionHeader(stringResource(R.string.release_impact_title))
                ReleaseImpactLabels(
                    event,
                    Modifier.fillMaxWidth().padding(horizontal = ResearchLayout.pagePadding),
                    detailed = true,
                    showTitle = false,
                )
            }
        }
        item { MarketTrackingCard(event, market) }
        item { EventIntroduction(event, stateDescription = description) }
        item {
            TerminalButton(
                onClick = onAnalysis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = ResearchLayout.pagePadding, vertical = ResearchLayout.gap),
                // A missing published value must not block analysis after the scheduled event time.
                enabled = hasEventTimeArrived(event.eventTime, now),
            ) { Text(stringResource(if (event.status in setOf("completed", "historical")) R.string.view_analysis else R.string.view_analysis_progress)) }
        }
        item { HistoryLink(onHistory) }
    }
}

@Composable
private fun EventHeader(event: EconomicEvent) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = ResearchLayout.pagePadding, vertical = ResearchLayout.smallGap),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "${flag(event.country)} ${countryLabel(event.country)} · ${categoryLabel(event.category)} · ${importanceLabel(event.importance)}",
                Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ImportanceDots(event.importance, decorative = true)
        }
        Text(
            event.localizedName(LocalConfiguration.current.locales[0]),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Manual recovery for missing release values or source unit/scale metadata. */
@Composable
private fun ReleaseRetryCard(
    unitRecovery: Boolean,
    fetching: Boolean,
    outcome: ReleaseFetchOutcome?,
    error: String?,
    warning: CalendarWarning?,
    onFetch: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = ResearchLayout.pagePadding, vertical = ResearchLayout.denseGap)
            .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.extraSmall)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            stringResource(if (unitRecovery) R.string.event_unit_refresh_note else R.string.release_data_missing),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        outcome?.let {
            Text(
                stringResource(
                    if (unitRecovery) R.string.event_unit_refresh_result
                    else if (it == ReleaseFetchOutcome.RETRIEVED) R.string.release_fetch_updated
                    else R.string.release_fetch_missing,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (it == ReleaseFetchOutcome.RETRIEVED) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error,
            )
        }
        // The source warning explains why a retry still could not fill the value.
        warning?.let {
            Text(calendarWarningMessage(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        error?.let {
            Text(stringResource(R.string.release_fetch_failed, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        TerminalOutlinedButton(onClick = onFetch, enabled = !fetching, modifier = Modifier.fillMaxWidth()) {
            if (fetching) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Outlined.Refresh, contentDescription = null)
            }
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (fetching) R.string.release_fetching else if (unitRecovery) R.string.event_unit_refresh_action else R.string.release_fetch_action))
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
    AccentBand {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(if (awaitingFutureRelease) R.string.release_countdown else R.string.data_status),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (awaitingFutureRelease) countdown(event.eventTime, now) else statusLabel(event.currentStatus(now)),
                    style = if (awaitingFutureRelease) ResearchType.hero else MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = if (event.status == "watching") Upcoming else MaterialTheme.colorScheme.primary,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(event.localTime(), style = ResearchType.large)
                Text(stringResource(R.string.local_time), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun ReleaseDataCard(event: EconomicEvent) {
    // Preserve the original four columns and their order, even when the two expectations match.
    MetricGrid(
        listOf(
            Metric(stringResource(R.string.actual), event.value(event.actual), emphasis = event.actual != null),
            Metric(stringResource(R.string.consensus), event.value(event.consensus)),
            Metric(stringResource(R.string.forecast), event.value(event.forecast)),
            Metric(stringResource(R.string.previous), event.value(event.previous)),
        ),
    )
}

@Composable
private fun EventIntroduction(event: EconomicEvent, stateDescription: EventDescription?) {
    val introText = stateDescription?.let { description ->
        val locale = LocalConfiguration.current.locales[0]
        when {
            locale.language == java.util.Locale.CHINESE.language && locale.country in setOf("TW", "HK", "MO") -> description.zhTw
            locale.language == java.util.Locale.CHINESE.language -> description.zhCn
            else -> description.en
        }
    } ?: if (event.category.equals("calendar", ignoreCase = true)) {
        stringResource(R.string.event_intro_nonnumeric, countryLabel(event.country), categoryLabel(event.category))
    } else {
        stringResource(R.string.event_intro_body, countryLabel(event.country), categoryLabel(event.category))
    }
    Column {
        SectionHeader(stringResource(R.string.event_intro))
        Text(
            introText,
            Modifier.padding(horizontal = ResearchLayout.pagePadding),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HistoryLink(onClick: () -> Unit) {
    Column {
        Hairline()
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClick = onClick)
                .padding(horizontal = ResearchLayout.pagePadding),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.view_history), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Hairline()
    }
}

internal val MARKET_TRACKING_SYMBOLS =
    listOf("gold", "dxy", "us2y", "us10y", "nasdaq100", "bitcoin", "wti", "natural_gas")

/** Missing Actual does not end the standard 60-minute market observation window. */
internal fun marketTrackingAwaitingData(
    status: String,
    eventTime: String? = null,
    now: Instant = Instant.now(),
): Boolean {
    if (status in setOf("scheduled", "watching", "released", "collecting_market_data", "analyzing")) return true
    if (status !in setOf("timeout", "data_unavailable")) return false
    val elapsed = runCatching { Duration.between(Instant.parse(eventTime), now).seconds }.getOrNull()
    return elapsed != null && elapsed in 0L..3_600L
}

/**
 * Rows shown in the market card. Live states keep the full placeholder list; terminal states
 * only list symbols that produced a snapshot or a reaction, so an event the backend never
 * tracked no longer renders eight "--" rows that read as a malfunction.
 */
internal fun visibleMarketSymbols(
    status: String,
    snapshots: List<MarketSnapshot>,
    reactions: List<MarketReaction>,
    eventTime: String? = null,
    now: Instant = Instant.now(),
): List<String> {
    if (marketTrackingAwaitingData(status, eventTime, now)) return MARKET_TRACKING_SYMBOLS
    val available = snapshots.mapTo(hashSetOf()) { it.symbol } +
        reactions.mapTo(hashSetOf()) { it.symbol }
    return MARKET_TRACKING_SYMBOLS.filter { it in available }
}

@Composable
internal fun MarketTrackingCard(event: EconomicEvent, market: MarketResponse?) {
    val status = event.currentStatus()
    val latest = market?.snapshots.orEmpty().groupBy { it.symbol }.mapValues { it.value.maxByOrNull { row -> row.timestamp } }
    val reactions = market?.reactions.orEmpty().associateBy { it.symbol }
    val rows = visibleMarketSymbols(status, market?.snapshots.orEmpty(), market?.reactions.orEmpty(), event.eventTime)
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(
            stringResource(R.string.market_tracking),
            trailing = statusLabel(status),
            trailingColor = MaterialTheme.colorScheme.primary,
        )
        if (rows.isNotEmpty()) {
            TrackingRow(
                stringResource(R.string.asset),
                stringResource(R.string.table_price),
                stringResource(R.string.minutes_short, 5L),
                header = true,
            )
            Hairline()
        }
        rows.forEach { symbol ->
            val reaction = reactions[symbol]
            val change = reaction?.change5m ?: reaction?.change1m
            // Backfilled events keep only a reaction: its baseline is the pre-release close.
            TrackingRow(
                assetLabel(symbol),
                latest[symbol]?.price?.let { "%,.2f".format(it) }
                    ?: reaction?.baselinePrice?.let { "%,.2f".format(it) }
                    ?: "--",
                formatChange(change, reaction?.reactionUnit ?: "percent"),
                changeColor = when {
                    change == null -> MaterialTheme.colorScheme.onSurfaceVariant
                    change >= 0 -> AssetUp
                    else -> AssetDown
                },
            )
        }
        Column(Modifier.padding(horizontal = ResearchLayout.pagePadding, vertical = ResearchLayout.smallGap)) {
            if (rows.isEmpty()) {
                Text(stringResource(R.string.market_data_unavailable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (status == "historical") {
                Text(stringResource(R.string.historical_method), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                AnalysisProgress(event.eventTime, status)
            }
        }
    }
}

@Composable
private fun TrackingRow(
    asset: String,
    price: String,
    change: String,
    header: Boolean = false,
    changeColor: Color = Color.Unspecified,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val valueStyle = if (header) MaterialTheme.typography.labelSmall else ResearchType.body
    // 表头与数据行都按正文字号算列宽，保证两者对齐
    val priceWidth = monoColumnWidth(11f, 96.dp, ResearchType.body)
    val changeWidth = monoColumnWidth(9f, 80.dp, ResearchType.body)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = ResearchLayout.pagePadding, vertical = if (header) ResearchLayout.denseGap else 5.dp),
        horizontalArrangement = Arrangement.spacedBy(ResearchLayout.smallGap),
    ) {
        Text(
            asset,
            Modifier.weight(1f),
            style = if (header) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodyMedium,
            color = if (header) muted else Color.Unspecified,
        )
        Text(price, Modifier.width(priceWidth), style = valueStyle, textAlign = TextAlign.End, color = if (header) muted else Color.Unspecified)
        Text(change, Modifier.width(changeWidth), style = valueStyle, textAlign = TextAlign.End, color = if (header) muted else changeColor)
    }
}

private val Horizons = listOf(1L, 5L, 15L, 30L, 60L)

/** 每个观察窗口是否已结束；completed 状态视为全部完成。 */
internal fun reachedHorizons(elapsedMinutes: Long, status: String): List<Boolean> =
    Horizons.map { status == "completed" || elapsedMinutes >= it }

@Composable
private fun AnalysisProgress(eventTime: String, status: String) {
    val elapsed = runCatching { Duration.between(Instant.parse(eventTime), Instant.now()).toMinutes() }.getOrDefault(-1)
    val reached = reachedHorizons(elapsed, status)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.analysis_progress), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Horizons.forEachIndexed { index, horizon ->
                val done = reached[index]
                val state = stringResource(if (done) R.string.progress_done else R.string.progress_pending)
                Column(
                    Modifier.weight(1f).semantics(mergeDescendants = true) { stateDescription = state },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(
                                if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                MaterialTheme.shapes.extraSmall,
                            ),
                    )
                    Text(
                        stringResource(R.string.minutes_short, horizon),
                        style = ResearchType.small,
                        color = if (done) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
