package com.macroresearch.ui.earnings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.macroresearch.R
import com.macroresearch.data.EarningsRepository
import com.macroresearch.data.model.*
import com.macroresearch.ui.common.appLocale
import com.macroresearch.ui.theme.AssetDown
import com.macroresearch.ui.theme.AssetUp
import kotlinx.coroutines.delay
import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Full-screen detail overlay preserves calendar date, scroll position and search on return. */
@Composable
internal fun EarningsMarketDialog(date: LocalDate, event: EarningsEvent, repository: EarningsRepository, onDismiss: () -> Unit) {
    val loader = remember(repository, date, event.symbol) { EarningsMarketLoader(repository, date, event.symbol) }
    val state by loader.state.collectAsStateWithLifecycle()
    val quoteLoader = remember(repository, event.symbol) { EarningsQuoteLoader(repository, event.symbol) }
    val quoteState by quoteLoader.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(loader, lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { loader.monitor() } }
    LaunchedEffect(quoteLoader, lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { quoteLoader.monitor() } }
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { now = Instant.now(); delay(1_000) }
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        EarningsMarketContent(date, event, state, now, { loader.refresh(); quoteLoader.refresh() }, onDismiss,
            quoteState = quoteState, favorite = { EarningsFavoriteButton(date, event, repository) })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EarningsMarketContent(
    date: LocalDate,
    event: EarningsEvent,
    state: EarningsMarketState,
    now: Instant,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    quoteState: EarningsQuoteState = EarningsQuoteState(),
    favorite: (@Composable () -> Unit)? = null,
) {
    val locale = appLocale()
    var openingSelected by rememberSaveable(date, event.symbol) { mutableStateOf(false) }
    val opening = state.market?.forOpeningSession()
    val market = if (openingSelected && opening != null) opening else state.market
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(event.symbol, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) } },
            actions = { favorite?.invoke(); IconButton(onClick = onRefresh, enabled = !state.loading, modifier = Modifier.testTag("earnings-market-refresh")) {
                Icon(Icons.Outlined.Refresh, stringResource(R.string.earnings_refresh))
            } },
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)
            .testTag("earnings-market-detail"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(event.name, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.earnings_market_date, date.toString()), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            CurrentStockPriceCard(quoteState, now, locale)
            EarningsPageLink(event)
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { error ->
                Card {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(when (error) {
                            "backend_required" -> R.string.earnings_market_backend_required
                            "unauthorized" -> R.string.earnings_unauthorized
                            "unsupported" -> R.string.earnings_market_unsupported
                            "rate_limited" -> R.string.earnings_market_rate_limited
                            else -> R.string.earnings_market_failed
                        }), color = MaterialTheme.colorScheme.error)
                        if (market != null) Text(stringResource(R.string.earnings_market_stale), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = onRefresh, enabled = !state.loading) { Text(stringResource(R.string.earnings_refresh)) }
                    }
                }
            }
            if (opening != null) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !openingSelected, onClick = { openingSelected = false },
                        label = { Text(stringResource(R.string.earnings_market_release_phase)) }, modifier = Modifier.testTag("earnings-phase-release"))
                    FilterChip(selected = openingSelected, onClick = { openingSelected = true },
                        label = { Text(stringResource(R.string.earnings_market_opening_phase)) }, modifier = Modifier.testTag("earnings-phase-opening"))
                }
                Text(stringResource(R.string.earnings_market_opening_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            market?.let {
                StockPriceCard(it, now, locale)
                if (it.tracking != null) {
                    StockObservedTable(it, now, locale)
                    StockTimeline(it, now, locale)
                    StockCoverageCard(it, locale)
                }
            }
            Text(stringResource(R.string.earnings_market_method), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StockPriceCard(market: EarningsMarket, now: Instant, locale: Locale) {
    val tracking = market.tracking
    val points = remember(market) { market.pricePoints() }
    val latest = points.lastOrNull()
    val ended = market.coverage.windowEnded || tracking?.status == "completed" || tracking?.endsAt?.let { Instant.parse(it) <= now } == true
    val change = market.changeFromFirst(latest)
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.earnings_market_title), fontWeight = FontWeight.Bold)
                Text(stringResource(when {
                    tracking == null -> R.string.earnings_market_not_started
                    ended -> R.string.earnings_market_ended
                    tracking.status == "scheduled" -> R.string.earnings_market_scheduled
                    else -> R.string.earnings_market_collecting
                }), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(priceLabel(latest?.price, locale), style = MaterialTheme.typography.headlineLarge, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold, modifier = Modifier.testTag("earnings-market-latest-price"))
                Text(tracking?.currency ?: "—", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                ChangeLabel(change, locale)
            }
            Text(stringResource(R.string.earnings_market_last_sample), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (latest != null) Text(stringResource(R.string.earnings_market_quote_time, stockTime(latest.at.toString(), locale)),
                style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            if (tracking == null) {
                Text(stringResource(when (market.observationStatus) {
                    "watching" -> R.string.earnings_market_watching
                    "already_reported" -> R.string.earnings_market_already_reported
                    "missed_after_gap", "quarter_changed" -> R.string.earnings_market_missed
                    else -> R.string.earnings_market_unobserved
                }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(stringResource(R.string.earnings_market_detected, stockTime(tracking.detectedAt, locale)), style = MaterialTheme.typography.bodySmall)
                if (market.timeBasis == "regular_session_open") Text(
                    stringResource(R.string.earnings_market_opening_start, stockTime(tracking.windowStart, locale)), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.earnings_market_window, stockTime(tracking.endsAt, locale)), style = MaterialTheme.typography.bodySmall)
                if (latest == null) Text(stringResource(R.string.earnings_market_no_samples), style = MaterialTheme.typography.bodySmall)
                market.firstPricePoint()?.let { first ->
                    Text(stringResource(R.string.earnings_market_baseline, priceLabel(first.price, locale), tracking.currency ?: "—", stockTime(first.at.toString(), locale)),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (points.isNotEmpty()) StockPriceChart(points, tracking, locale)
            }
        }
    }
}

@Composable
private fun StockObservedTable(market: EarningsMarket, now: Instant, locale: Locale) {
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.earnings_market_observed), fontWeight = FontWeight.Bold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.width(64.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(stringResource(R.string.asset), style = MaterialTheme.typography.labelSmall)
                    Text(market.symbol, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                }
                listOf(1, 5, 15, 30, 60).forEach { minute ->
                    val point = market.priceAt(minute, now)
                    Column(Modifier.width(88.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.minutes_short, minute), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(priceLabel(point?.price, locale), fontFamily = FontFamily.Monospace)
                        ChangeLabel(market.changeFromFirst(point), locale)
                    }
                }
            }
            Text(stringResource(R.string.earnings_market_change_basis), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StockTimeline(market: EarningsMarket, now: Instant, locale: Locale) {
    var minute by rememberSaveable(market.date, market.symbol, market.timeBasis) { mutableFloatStateOf(5f) }
    val point = market.priceAt(minute.toInt(), now)
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.earnings_market_timeline), fontWeight = FontWeight.Bold)
                Text(stringResource(if (market.timeBasis == "regular_session_open") R.string.earnings_market_after_opening
                    else R.string.earnings_market_after_detection, minute.toInt()), color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge)
            }
            Slider(value = minute, onValueChange = { minute = it }, valueRange = 0f..60f, steps = 59,
                modifier = Modifier.testTag("earnings-market-timeline"))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(market.symbol, fontFamily = FontFamily.Monospace)
                Text(priceLabel(point?.price, locale), fontFamily = FontFamily.Monospace,
                    modifier = Modifier.testTag("earnings-market-timeline-price"))
                ChangeLabel(market.changeFromFirst(point), locale)
            }
            Text(if (point != null) stringResource(R.string.earnings_market_quote_time, stockTime(point.at.toString(), locale))
                else stringResource(R.string.earnings_market_no_point), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StockCoverageCard(market: EarningsMarket, locale: Locale) {
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.earnings_market_coverage, market.coverage.minuteCount, market.coverage.expectedMinuteCount, market.coverage.missingMinuteCount),
                style = MaterialTheme.typography.bodySmall)
            if (market.tracking?.lastError != null) Text(stringResource(R.string.earnings_market_provider_error),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            val bases = market.snapshots.map { it.timestampBasis }.toSet()
            if (bases.any { it != "provider_quote" }) Text(stringResource(R.string.earnings_market_bar_close), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.earnings_market_source, ZoneId.systemDefault().id), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            market.tracking?.lastSuccessAt?.let {
                Text(stringResource(R.string.earnings_market_last_success, stockTime(it, locale)), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StockPriceChart(points: List<EarningsPricePoint>, tracking: EarningsStockTracking, locale: Locale) {
    val low = points.minOf { it.price }
    val high = points.maxOf { it.price }
    val spread = (high - low).takeIf { it > 0 } ?: (low * .001)
    val bottom = low - spread * (if (high == low) .65 else .15)
    val range = spread * 1.3
    val start = Instant.parse(tracking.windowStart)
    val color = if (points.last().price >= points.first().price) AssetUp else AssetDown
    val grid = MaterialTheme.colorScheme.outlineVariant
    val description = stringResource(R.string.earnings_market_chart)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.earnings_market_price_range, priceLabel(low, locale), priceLabel(high, locale)),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Canvas(Modifier.fillMaxWidth().height(132.dp).semantics { contentDescription = description }) {
            fun position(point: EarningsPricePoint) = Offset(
                (Duration.between(start, point.at).toMillis() / 3_600_000f).coerceIn(0f, 1f) * size.width,
                size.height - ((point.price - bottom) / range).toFloat() * size.height,
            )
            listOf(.25f, .5f, .75f).forEach { y -> drawLine(grid, Offset(0f, size.height * y), Offset(size.width, size.height * y), 1.dp.toPx()) }
            points.zipWithNext().forEach { (a, b) ->
                // A missing minute is a visible break, not a fabricated continuous path.
                if (b.at.epochSecond / 60 - a.at.epochSecond / 60 <= 1 && Duration.between(a.at, b.at).seconds <= 90) {
                    drawLine(color, position(a), position(b), 2.dp.toPx())
                }
            }
            points.forEach { drawCircle(color, 2.5.dp.toPx(), position(it)) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(0, 30, 60).forEach { Text(stringResource(R.string.minutes_short, it), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun ChangeLabel(change: Double?, locale: Locale) {
    Text(change?.let { String.format(locale, "%+.2f%%", it) } ?: "—", fontFamily = FontFamily.Monospace,
        color = when { change == null -> MaterialTheme.colorScheme.onSurfaceVariant; change >= 0 -> AssetUp; else -> AssetDown })
}

private fun priceLabel(value: Double?, locale: Locale): String = value?.let {
    NumberFormat.getNumberInstance(locale).apply { minimumFractionDigits = 2; maximumFractionDigits = 4 }.format(it)
} ?: "—"

private fun stockTime(value: String, locale: Locale): String = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", locale)
    .withZone(ZoneId.systemDefault()).format(Instant.parse(value))
