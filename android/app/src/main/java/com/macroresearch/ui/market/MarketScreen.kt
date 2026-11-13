package com.macroresearch.ui.market

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.macroresearch.R
import com.macroresearch.data.MacroRepository
import com.macroresearch.data.model.LiveMarketQuote
import com.macroresearch.ui.MarketViewModel
import com.macroresearch.ui.common.assetLabel
import com.macroresearch.ui.theme.AssetDown
import com.macroresearch.ui.theme.AssetUp
import com.macroresearch.ui.viewModelFactory
import com.macroresearch.ui.theme.ResearchLayout
import kotlinx.coroutines.delay
import java.text.NumberFormat
import java.util.Locale

private val marketGroups = listOf(
    R.string.risk_assets to listOf("nasdaq100", "sp500"),
    R.string.precious_metals to listOf("gold", "silver"),
    R.string.dollar_fx to listOf("dxy", "eur_usd"),
    R.string.treasuries to listOf("us2y", "us10y"),
    R.string.category_energy to listOf("wti", "natural_gas"),
    R.string.crypto to listOf("bitcoin", "ethereum"),
)

@Composable
fun MarketScreen(repository: MacroRepository, padding: PaddingValues, onNews: () -> Unit = {}) {
    val vm: MarketViewModel = viewModel(factory = viewModelFactory { MarketViewModel(repository) })
    val state by vm.state.collectAsStateWithLifecycle()
    val quotes = state.value?.quotes.orEmpty().associateBy { it.symbol }
    val unavailable = state.value?.unavailable.orEmpty().toSet()

    val source by repository.dataSourceSettings.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(vm, source, lifecycle) {
        vm.resetSource()
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                while (true) { vm.refreshAndWait(); delay(5_000) }
            } finally { vm.stopRefreshing() }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(ResearchLayout.pagePadding),
        verticalArrangement = Arrangement.spacedBy(ResearchLayout.gap),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        stringResource(R.string.nav_market),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        stringResource(R.string.market_subtitle),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = vm::refresh, enabled = !state.loading) {
                    if (state.loading && quotes.isNotEmpty()) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(Icons.Outlined.Refresh, stringResource(R.string.refresh_quotes))
                    }
                }
            }
        }
        item {
            Card(onClick = onNews, colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            )) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.news_entry), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.news_entry_note), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (state.loading && quotes.isEmpty()) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                }
            }
        }
        state.error?.let { error ->
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.quotes_refresh_failed),
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = vm::refresh) { Text(stringResource(R.string.retry)) }
                }
            }
        }
        marketGroups.forEach { (title, symbols) ->
            item {
                Text(
                    stringResource(title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    symbols.forEach { symbol ->
                        MarketQuoteCard(
                            symbol = symbol,
                            quote = quotes[symbol],
                            unavailable = symbol in unavailable,
                        )
                    }
                }
            }
        }
        item {
            Text(
                stringResource(R.string.market_api_note),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun MarketQuoteCard(symbol: String, quote: LiveMarketQuote?, unavailable: Boolean) {
    val locale = LocalConfiguration.current.locales[0]
    val basisPointsLabel = stringResource(R.string.basis_points)
    val marketClosed = quote?.marketState.equals("closed", ignoreCase = true)
    Card {
        Row(
            modifier = Modifier.fillMaxWidth().padding(ResearchLayout.cardPadding),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(assetLabel(symbol), fontWeight = FontWeight.Bold)
                when {
                    quote != null -> {
                        val status = when {
                            quote.stale -> stringResource(R.string.market_stale)
                            marketClosed ->
                                stringResource(R.string.market_closed)
                            else -> stringResource(R.string.market_live)
                        }
                        Text(
                            stringResource(
                                R.string.market_quote_meta,
                                providerLabel(quote.provider),
                                status,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            stringResource(R.string.quote_observed_at, quoteTimeLabel(quote.timestamp, locale) ?: "—"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (quote.low != null && quote.high != null) {
                            Text(
                                stringResource(
                                    R.string.market_day_range,
                                    formatMarketQuoteValue(symbol, quote.low, locale),
                                    formatMarketQuoteValue(symbol, quote.high, locale),
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    unavailable -> Text(
                        stringResource(R.string.market_unavailable),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    else -> Text(
                        stringResource(R.string.waiting_quotes),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    quote?.price?.let { formatMarketQuoteValue(symbol, it, locale) } ?: "--",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.End,
                )
                if (quote != null && !quote.stale && !marketClosed) {
                    val changeText = formatMarketQuoteChange(quote, locale, basisPointsLabel)
                    val change = quote.changePercent
                    if (changeText != null && change != null) {
                        Text(
                            changeText,
                            color = if (change >= 0) AssetUp else AssetDown,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

private fun providerLabel(provider: String): String = when (provider.lowercase(Locale.ROOT)) {
    "binance" -> "Binance"
    "biquote" -> "BiQuote"
    "cnbc" -> "CNBC"
    "treasury" -> "U.S. Treasury"
    "yahoo" -> "Yahoo Finance"
    else -> provider
}

internal fun formatMarketPrice(price: Double, locale: Locale = Locale.ENGLISH): String {
    val decimals = when {
        price >= 100.0 -> 2
        price >= 1.0 -> 4
        else -> 6
    }
    return NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = decimals
        maximumFractionDigits = decimals
    }.format(price)
}
