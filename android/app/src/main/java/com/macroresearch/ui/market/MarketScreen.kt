package com.macroresearch.ui.market

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.macroresearch.R
import com.macroresearch.data.MacroRepository
import com.macroresearch.data.model.LiveMarketQuote
import com.macroresearch.ui.MarketViewModel
import com.macroresearch.ui.common.assetLabel
import com.macroresearch.ui.theme.AssetDown
import com.macroresearch.ui.theme.AssetUp
import com.macroresearch.ui.theme.ResearchLayout
import com.macroresearch.ui.theme.TabularStyle
import com.macroresearch.ui.theme.Upcoming
import com.macroresearch.ui.viewModelFactory
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.delay

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

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(
            horizontal = ResearchLayout.pagePadding,
            vertical = ResearchLayout.gap,
        ),
        verticalArrangement = Arrangement.spacedBy(ResearchLayout.gap),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "GLOBAL MARKETS",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            stringResource(R.string.nav_market),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.ExtraBold,
                        )
                        Text(
                            stringResource(R.string.market_subtitle),
                            modifier = Modifier.padding(bottom = 2.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Surface(
                    onClick = vm::refresh,
                    enabled = !state.loading,
                    shape = RoundedCornerShape(ResearchLayout.innerCorner),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (state.loading && quotes.isNotEmpty()) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                Icons.Outlined.Refresh,
                                contentDescription = stringResource(R.string.refresh_quotes),
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Text(
                            text = stringResource(R.string.refresh_quotes),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
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
                Surface(
                    shape = RoundedCornerShape(ResearchLayout.innerCorner),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f)),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.load_failed, error),
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = vm::refresh) { Text(stringResource(R.string.retry)) }
                    }
                }
            }
        }

        marketGroups.forEach { (title, symbols) ->
            item {
                Card(
                    shape = RoundedCornerShape(ResearchLayout.cardCorner),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // Category Header Ribbon
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.5.dp)
                                    .height(13.dp)
                                    .clip(RoundedCornerShape(1.dp))
                                    .background(MaterialTheme.colorScheme.primary),
                            )
                            Text(
                                stringResource(title),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                        if (ResearchLayout.stackMetadata) {
                            symbols.forEachIndexed { index, symbol ->
                                if (index > 0) {
                                    HorizontalDivider(
                                        thickness = 0.5.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant,
                                    )
                                }
                                MarketQuoteTile(
                                    symbol = symbol,
                                    quote = quotes[symbol],
                                    unavailable = symbol in unavailable,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        } else {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(IntrinsicSize.Min),
                            ) {
                                symbols.forEachIndexed { index, symbol ->
                                    if (index > 0) {
                                        Box(
                                            modifier = Modifier
                                                .width(0.5.dp)
                                                .fillMaxHeight()
                                                .background(MaterialTheme.colorScheme.outlineVariant),
                                        )
                                    }
                                    MarketQuoteTile(
                                        symbol = symbol,
                                        quote = quotes[symbol],
                                        unavailable = symbol in unavailable,
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight(),
                                    )
                                }
                            }
                        }
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
private fun MarketQuoteTile(
    symbol: String,
    quote: LiveMarketQuote?,
    unavailable: Boolean,
    modifier: Modifier = Modifier,
) {
    val locale = LocalConfiguration.current.locales[0]
    val marketClosed = quote?.marketState.equals("closed", ignoreCase = true)
    val isLive = quote != null && !quote.stale && !marketClosed
    val change = quote?.changePercent?.takeIf { !quote.stale && !marketClosed }
    val deltaColor = when {
        change == null -> MaterialTheme.colorScheme.onSurfaceVariant
        change >= 0 -> AssetUp
        else -> AssetDown
    }

    Column(
        modifier = modifier.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Row 1: Asset Name + Delta Pill
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = assetLabel(symbol),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (change != null) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = deltaColor.copy(alpha = 0.12f),
                    border = BorderStroke(0.5.dp, deltaColor.copy(alpha = 0.35f)),
                ) {
                    Text(
                        text = "%+.2f%%".format(locale, change),
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                        color = deltaColor,
                        style = MaterialTheme.typography.labelSmall.merge(TabularStyle),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
            }
        }

        // Row 2: Large Tabular Price
        Text(
            text = quote?.price?.let { formatMarketPrice(it, locale) } ?: "--",
            style = MaterialTheme.typography.titleLarge.merge(TabularStyle),
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )

        // Row 3: Provider Status & Intraday Range Bar
        when {
            quote != null -> {
                val status = when {
                    quote.stale -> stringResource(R.string.market_stale)
                    marketClosed -> stringResource(R.string.market_closed)
                    else -> stringResource(R.string.market_live)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isLive -> AssetUp
                                    quote.stale -> Upcoming
                                    else -> MaterialTheme.colorScheme.outline
                                },
                            ),
                    )
                    Text(
                        stringResource(
                            R.string.market_quote_meta,
                            providerLabel(quote.provider),
                            status,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (quote.low != null && quote.high != null) {
                    val lowText = formatMarketPrice(quote.low, locale)
                    val highText = formatMarketPrice(quote.high, locale)
                    val rangeLabel = stringResource(R.string.market_day_range, lowText, highText)
                    val span = (quote.high - quote.low).takeIf { it > 0.0 }
                    val fraction = span?.let { ((quote.price - quote.low) / it).toFloat().coerceIn(0f, 1f) } ?: 0.5f
                    Column(
                        modifier = Modifier.semantics(mergeDescendants = true) {
                            contentDescription = rangeLabel
                        },
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .clip(RoundedCornerShape(1.5.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(fraction.coerceAtLeast(0.06f))
                                    .height(3.dp)
                                    .background(if (change != null && change < 0) AssetDown else MaterialTheme.colorScheme.primary),
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = lowText,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall.merge(TabularStyle),
                                maxLines = 1,
                            )
                            Text(
                                text = highText,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall.merge(TabularStyle),
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            unavailable -> Text(
                stringResource(R.string.market_unavailable),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall,
            )
            else -> Text(
                stringResource(R.string.waiting_quotes),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

private fun providerLabel(provider: String): String = when (provider.lowercase(Locale.ROOT)) {
    "binance" -> "Binance"
    "biquote" -> "BiQuote"
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
