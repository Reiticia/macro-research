package com.macroresearch.ui.earnings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.macroresearch.R
import com.macroresearch.data.EarningsRepository
import com.macroresearch.data.model.EarningsEvent
import com.macroresearch.ui.common.appLocale
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate

/** Current quote only: opening this detail never loads or creates an earnings-price history. */
@Composable
internal fun EarningsStockDialog(date: LocalDate, event: EarningsEvent, repository: EarningsRepository, onDismiss: () -> Unit) {
    val loader = remember(repository, event.symbol) { EarningsQuoteLoader(repository, event.symbol) }
    val quote by loader.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(loader, lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { loader.monitor() } }
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { now = Instant.now(); delay(5_000) }
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        EarningsStockContent(date, event, quote, now, loader::refresh, onDismiss,
            favorite = { EarningsFavoriteButton(date, event, repository) })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EarningsStockContent(
    date: LocalDate,
    event: EarningsEvent,
    quoteState: EarningsQuoteState,
    now: Instant,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    favorite: (@Composable () -> Unit)? = null,
) {
    val locale = appLocale()
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(event.symbol, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) } },
            actions = {
                favorite?.invoke()
                IconButton(onClick = onRefresh, enabled = !quoteState.loading, modifier = Modifier.testTag("earnings-stock-refresh")) {
                    Icon(Icons.Outlined.Refresh, stringResource(R.string.earnings_refresh))
                }
            },
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)
            .testTag("earnings-stock-detail"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(event.name, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.earnings_stock_date, date.toString()), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            CurrentStockPriceCard(quoteState, now, locale)
            EarningsPageLink(event)
        }
    }
}
