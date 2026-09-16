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
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.macroresearch.data.CorrectionOutcome
import com.macroresearch.data.model.EconomicEvent
import com.macroresearch.data.model.currentStatus
import com.macroresearch.data.model.MarketResponse
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
                var showCorrection by rememberSaveable { mutableStateOf(false) }
                EventContent(
                    event = event,
                    market = state.market,
                    modifier = Modifier.padding(padding),
                    onAnalysis = onAnalysis,
                    onHistory = onHistory,
                    onFixTranslation = { showCorrection = true },
                    warning = warning,
                    releaseFetching = state.releaseFetching,
                    releaseOutcome = state.releaseOutcome,
                    releaseError = state.releaseError,
                    onFetchRelease = vm::fetchRelease,
                )
                if (showCorrection) {
                    TranslationCorrectionDialog(
                        event = event,
                        repository = repository,
                        onChanged = vm::refresh,
                        onDismiss = { showCorrection = false },
                    )
                }
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
    onFixTranslation: () -> Unit,
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
        item {
            OutlinedButton(
                onClick = onFixTranslation,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Edit, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.fix_translation))
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
private fun TranslationCorrectionDialog(
    event: EconomicEvent,
    repository: MacroRepository,
    onChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var zhCn by rememberSaveable(event.id) { mutableStateOf(event.eventZhCn.orEmpty()) }
    var zhTw by rememberSaveable(event.id) { mutableStateOf(event.eventZhTw.orEmpty()) }
    var working by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var operation by remember { mutableStateOf<Job?>(null) }
    val configured = repository.translationSettings.collectAsStateWithLifecycle().value.configured
    val retranslateFailed = stringResource(R.string.retranslate_failed)
    val queuedMessage = stringResource(R.string.translation_correction_queued)

    fun cancelAndDismiss() {
        operation?.cancel()
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = ::cancelAndDismiss,
        title = { Text(stringResource(R.string.translation_correction), fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column {
                    Text(
                        stringResource(R.string.source_name),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(event.event, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                }
                OutlinedTextField(
                    value = zhCn,
                    onValueChange = { zhCn = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.simplified_chinese)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = zhTw,
                    onValueChange = { zhTw = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.traditional_chinese)) },
                    singleLine = true,
                )
                if (configured) {
                    OutlinedButton(
                        onClick = {
                            working = true
                            error = null
                            operation = scope.launch {
                                try {
                                    repository.retranslateEventName(event)
                                    onChanged()
                                    onDismiss()
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    error = failure.message ?: retranslateFailed
                                } finally {
                                    working = false
                                    operation = null
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !working,
                    ) {
                        if (working) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Outlined.Refresh, contentDescription = null)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(
                                if (working) R.string.retranslating else R.string.ai_retranslate,
                            ),
                        )
                    }
                } else {
                    Text(
                        stringResource(R.string.translation_requires_key),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    stringResource(R.string.translation_correction_note),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = ::cancelAndDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    working = true
                    error = null
                    operation = scope.launch {
                        try {
                            when (repository.submitTranslationCorrection(event, zhCn, zhTw)) {
                                // The server holds the shared translation cache, so a reader's
                                // suggestion is queued until the admin approves it.
                                is CorrectionOutcome.Queued -> {
                                    error = queuedMessage
                                    return@launch
                                }
                                CorrectionOutcome.AppliedLocally -> Unit
                            }
                            onChanged()
                            onDismiss()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            error = failure.message
                        } finally {
                            working = false
                            operation = null
                        }
                    }
                },
                enabled = zhCn.isNotBlank() && zhTw.isNotBlank() && !working,
            ) { Text(stringResource(R.string.save)) }
        },
    )
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
            Text(
                stringResource(R.string.event_intro_body, countryLabel(event.country), categoryLabel(event.category)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MarketTrackingCard(event: EconomicEvent, market: MarketResponse?) {
    val symbols = listOf("gold", "dxy", "us2y", "us10y", "nasdaq100", "bitcoin")
    val latest = market?.snapshots.orEmpty().groupBy { it.symbol }.mapValues { it.value.maxByOrNull { row -> row.timestamp } }
    val reactions = market?.reactions.orEmpty().associateBy { it.symbol }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.market_tracking), fontWeight = FontWeight.Bold)
                Text(statusLabel(event.status), color = MaterialTheme.colorScheme.primary)
            }
            HorizontalDivider()
            symbols.forEach { symbol ->
                val reaction = reactions[symbol]
                val change = reaction?.change5m ?: reaction?.change1m
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(assetLabel(symbol))
                    Text(latest[symbol]?.price?.let { "%,.2f".format(it) } ?: "--")
                    Text(
                        formatChange(change, reaction?.reactionUnit ?: "percent"),
                        color = when { change == null -> MaterialTheme.colorScheme.onSurfaceVariant; change >= 0 -> AssetUp; else -> AssetDown },
                    )
                }
            }
            if (event.status == "historical") {
                Text(stringResource(R.string.historical_method), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                AnalysisProgress(event.eventTime, event.status)
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
