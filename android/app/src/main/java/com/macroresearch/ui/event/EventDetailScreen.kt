package com.macroresearch.ui.event

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Star
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.macroresearch.ui.theme.AssetDown
import com.macroresearch.ui.theme.AssetUp
import com.macroresearch.ui.theme.ResearchLayout
import com.macroresearch.ui.theme.TabularStyle
import com.macroresearch.ui.theme.Upcoming
import com.macroresearch.ui.viewModelFactory
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay

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
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            event?.localizedName(locale) ?: stringResource(R.string.event_detail),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
                        }
                    },
                    actions = {
                        IconButton(onClick = vm::toggleFollowed) {
                            Icon(
                                if (state.followed) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                stringResource(R.string.follow),
                                tint = if (state.followed) Upcoming else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    ),
                )
                HorizontalDivider(
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
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
            else -> {
                EventContent(
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
    LazyColumn(
        modifier
            .fillMaxSize()
            .padding(horizontal = ResearchLayout.pagePadding),
        verticalArrangement = Arrangement.spacedBy(ResearchLayout.gap),
    ) {
        item {
            Spacer(Modifier.height(2.dp))
            CountdownCard(event)
        }
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
        item { EventIntroduction(event, stateDescription = description) }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(ResearchLayout.cardCorner),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                ReleaseImpactLabels(event, Modifier.fillMaxWidth().padding(ResearchLayout.cardPadding), detailed = true)
            }
        }
        item { MarketTrackingCard(event, market) }
        item {
            Button(
                onClick = onAnalysis,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(ResearchLayout.innerCorner),
                enabled = hasEventTimeArrived(event.eventTime, now),
            ) {
                Text(
                    stringResource(
                        if (event.status in setOf("completed", "historical")) {
                            R.string.view_analysis
                        } else {
                            R.string.view_analysis_progress
                        },
                    ),
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        item {
            Card(
                onClick = onHistory,
                shape = RoundedCornerShape(ResearchLayout.cardCorner),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(ResearchLayout.cardPadding),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.view_history),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                    Text("→", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }
        }
        item { Column(Modifier.padding(bottom = 24.dp)) {} }
    }
}

@Composable
private fun ReleaseRetryCard(
    unitRecovery: Boolean,
    fetching: Boolean,
    outcome: ReleaseFetchOutcome?,
    error: String?,
    warning: CalendarWarning?,
    onFetch: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(ResearchLayout.cardCorner),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(ResearchLayout.cardPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
            OutlinedButton(
                onClick = onFetch,
                enabled = !fetching,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(ResearchLayout.innerCorner),
            ) {
                if (fetching) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.Refresh, contentDescription = null)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(
                        if (fetching) R.string.release_fetching
                        else if (unitRecovery) R.string.event_unit_refresh_action
                        else R.string.release_fetch_action,
                    ),
                )
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
    Card(
        shape = RoundedCornerShape(ResearchLayout.cardCorner),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Top Jurisdiction & Category Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${flag(event.country)} ${countryLabel(event.country)} · ${categoryLabel(event.category)}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        importanceLabel(event.importance),
                        color = Upcoming,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    ImportanceDots(event.importance)
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(ResearchLayout.cardPadding),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stringResource(if (awaitingFutureRelease) R.string.release_countdown else R.string.data_status),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                    )
                    Text(
                        if (awaitingFutureRelease) countdown(event.eventTime, now) else statusLabel(event.currentStatus(now)),
                        style = MaterialTheme.typography.headlineSmall.merge(TabularStyle),
                        color = if (event.status == "watching") Upcoming else MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(ResearchLayout.innerCorner),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.End,
                    ) {
                        Text(
                            event.localTime(),
                            style = MaterialTheme.typography.titleMedium.merge(TabularStyle),
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            stringResource(R.string.local_time),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ReleaseDataCard(event: EconomicEvent) {
    // Preserve the original four columns and their order, even when the two expectations match.
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(ResearchLayout.cardCorner),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
        ) {
            DataValueCell(
                label = stringResource(R.string.actual),
                value = event.value(event.actual),
                highlight = event.actual != null,
                modifier = Modifier.weight(1f),
            )
            VerticalHairline()
            DataValueCell(
                label = stringResource(R.string.consensus),
                value = event.value(event.consensus),
                modifier = Modifier.weight(1f),
            )
            VerticalHairline()
            DataValueCell(
                label = stringResource(R.string.forecast),
                value = event.value(event.forecast),
                modifier = Modifier.weight(1f),
            )
            VerticalHairline()
            DataValueCell(
                label = stringResource(R.string.previous),
                value = event.value(event.previous),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun VerticalHairline() {
    Box(
        modifier = Modifier
            .width(0.5.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

@Composable
private fun DataValueCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
) = Column(
    modifier = modifier
        .background(
            if (highlight) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            else MaterialTheme.colorScheme.surfaceContainerLowest,
        )
        .padding(horizontal = 10.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Text(
        value,
        style = MaterialTheme.typography.titleMedium.merge(TabularStyle),
        color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.ExtraBold,
        maxLines = 1,
    )
}

@Composable
private fun EventIntroduction(event: EconomicEvent, stateDescription: EventDescription?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(ResearchLayout.cardCorner),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(ResearchLayout.cardPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .width(3.5.dp)
                        .height(14.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
                Text(
                    stringResource(R.string.event_intro),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            val introText = stateDescription?.let { description ->
                val locale = LocalConfiguration.current.locales[0]
                when {
                    locale.language == java.util.Locale.CHINESE.language && locale.country in setOf("TW", "HK", "MO") -> description.zhTw
                    locale.language == java.util.Locale.CHINESE.language -> description.zhCn
                    else -> description.en
                }
            } ?: if (event.category.equals("calendar", ignoreCase = true)) {
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
    Card(
        shape = RoundedCornerShape(ResearchLayout.cardCorner),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(ResearchLayout.cardPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .width(3.5.dp)
                            .height(14.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(MaterialTheme.colorScheme.primary),
                    )
                    Text(
                        stringResource(R.string.market_tracking),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        statusLabel(status),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            rows.forEachIndexed { index, symbol ->
                if (index > 0) {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                    )
                }
                val reaction = reactions[symbol]
                val change = reaction?.change5m ?: reaction?.change1m
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(assetLabel(symbol), fontWeight = FontWeight.SemiBold)
                    Text(
                        latest[symbol]?.price?.let { "%,.2f".format(it) }
                            ?: reaction?.baselinePrice?.let { "%,.2f".format(it) }
                            ?: "--",
                        style = MaterialTheme.typography.bodyMedium.merge(TabularStyle),
                    )
                    Text(
                        formatChange(change, reaction?.reactionUnit ?: "percent"),
                        style = MaterialTheme.typography.bodyMedium.merge(TabularStyle),
                        fontWeight = if (change != null) FontWeight.Bold else FontWeight.Normal,
                        color = when {
                            change == null -> MaterialTheme.colorScheme.onSurfaceVariant
                            change >= 0 -> AssetUp
                            else -> AssetDown
                        },
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
                Text(
                    stringResource(R.string.historical_method),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                AnalysisProgress(event.eventTime, status)
            }
        }
    }
}

@Composable
private fun AnalysisProgress(eventTime: String, status: String) {
    val elapsed = runCatching { Duration.between(Instant.parse(eventTime), Instant.now()).toMinutes() }.getOrDefault(-1)
    Surface(
        shape = RoundedCornerShape(ResearchLayout.innerCorner),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.analysis_progress),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf(1L, 5L, 15L, 30L, 60L).forEach { horizon ->
                    val done = elapsed >= horizon || status == "completed"
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            stringResource(R.string.minutes_short, horizon),
                            style = MaterialTheme.typography.labelSmall.merge(TabularStyle),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            if (done) "✓" else "--",
                            color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}
