package com.macroresearch.ui.market

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.macroresearch.R
import com.macroresearch.data.MacroRepository
import com.macroresearch.ui.MarketViewModel
import com.macroresearch.ui.common.LoadingHint
import com.macroresearch.ui.common.assetLabel
import com.macroresearch.ui.common.terminal.Hairline
import com.macroresearch.ui.common.terminal.NoticeStrip
import com.macroresearch.ui.common.terminal.NoticeTone
import com.macroresearch.ui.common.terminal.PageHeader
import com.macroresearch.ui.common.terminal.QuoteRow
import com.macroresearch.ui.common.terminal.SectionHeader
import com.macroresearch.ui.theme.ResearchLayout
import com.macroresearch.ui.viewModelFactory
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
fun MarketScreen(repository: MacroRepository, padding: PaddingValues) {
    val vm: MarketViewModel = viewModel(factory = viewModelFactory { MarketViewModel(repository) })
    val state by vm.state.collectAsStateWithLifecycle()
    val quotes = state.value?.quotes.orEmpty().associateBy { it.symbol }
    val unavailable = state.value?.unavailable.orEmpty().toSet()

    LaunchedEffect(vm) {
        while (true) {
            vm.refreshAndWait()
            delay(5_000)
        }
    }

    Column(Modifier.fillMaxSize().padding(padding)) {
        PageHeader(
            title = stringResource(R.string.nav_market),
            subtitle = stringResource(R.string.market_subtitle),
            actions = {
                if (state.loading && quotes.isNotEmpty()) {
                    CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = vm::refresh) {
                        Icon(Icons.Outlined.Refresh, stringResource(R.string.refresh_quotes), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
        )
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = ResearchLayout.gap)) {
            if (state.loading && quotes.isEmpty()) item(key = "loading") { LoadingHint() }
            state.error?.let { error ->
                item(key = "error") {
                    NoticeStrip(
                        stringResource(R.string.load_failed, error),
                        tone = NoticeTone.Error,
                        action = { TextButton(onClick = vm::refresh) { Text(stringResource(R.string.retry)) } },
                    )
                }
            }
            marketGroups.forEach { (title, symbols) ->
                item(key = title) {
                    Column {
                        SectionHeader(stringResource(title))
                        Hairline()
                        symbols.forEach { symbol ->
                            QuoteRow(assetLabel(symbol), quotes[symbol], symbol in unavailable)
                        }
                    }
                }
            }
            item(key = "note") {
                Text(
                    stringResource(R.string.market_api_note),
                    Modifier.padding(horizontal = ResearchLayout.pagePadding, vertical = ResearchLayout.gap),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
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
