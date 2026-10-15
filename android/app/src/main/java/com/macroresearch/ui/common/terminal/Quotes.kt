package com.macroresearch.ui.common.terminal

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.macroresearch.R
import com.macroresearch.data.model.LiveMarketQuote
import com.macroresearch.ui.common.appLocale
import com.macroresearch.ui.market.formatMarketPrice
import com.macroresearch.ui.theme.AssetDown
import com.macroresearch.ui.theme.AssetUp
import com.macroresearch.ui.theme.ResearchLayout
import com.macroresearch.ui.theme.ResearchType
import java.util.Locale

/** 延迟或休市的报价不显示涨跌幅，只显示状态，避免把旧数据当作实时变化。 */
internal fun quoteChangeVisible(quote: LiveMarketQuote): Boolean =
    quote.changePercent != null && !quote.stale && !quote.marketState.equals("closed", ignoreCase = true)

@StringRes
internal fun quoteStatusLabel(quote: LiveMarketQuote): Int = when {
    quote.stale -> R.string.market_stale
    quote.marketState.equals("closed", ignoreCase = true) -> R.string.market_closed
    else -> R.string.market_live
}

internal fun providerLabel(provider: String): String = when (provider.lowercase(Locale.ROOT)) {
    "binance" -> "Binance"
    "cnbc" -> "CNBC"
    "biquote" -> "BiQuote"
    "yahoo" -> "Yahoo Finance"
    else -> provider
}

/** 首页 2 列行情格：名称在左，价格与涨跌幅（或状态）在右。 */
@Composable
fun QuoteCell(label: String, quote: LiveMarketQuote?, modifier: Modifier = Modifier) {
    val locale = appLocale()
    Row(
        modifier
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = ResearchLayout.pagePadding, vertical = ResearchLayout.denseGap),
        horizontalArrangement = Arrangement.spacedBy(ResearchLayout.denseGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Column(horizontalAlignment = Alignment.End) {
            Text(
                quote?.price?.let { formatMarketPrice(it, locale) } ?: "--",
                style = ResearchType.body,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            QuoteStatusLine(quote)
        }
    }
}

@Composable
private fun QuoteStatusLine(quote: LiveMarketQuote?) {
    val locale = appLocale()
    val change = quote?.takeIf(::quoteChangeVisible)?.changePercent
    when {
        change != null -> Text(
            "%+.2f%%".format(locale, change),
            style = ResearchType.small,
            color = if (change >= 0) AssetUp else AssetDown,
            maxLines = 1,
        )
        else -> Text(
            stringResource(if (quote == null) R.string.waiting_quotes else quoteStatusLabel(quote)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 行情页的一行：名称与"来源 · 状态"、日内区间在左，价格与涨跌幅在右。 */
@Composable
fun QuoteRow(label: String, quote: LiveMarketQuote?, unavailable: Boolean, modifier: Modifier = Modifier) {
    val locale = appLocale()
    Column(modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = ResearchLayout.pagePadding, vertical = ResearchLayout.rowVertical),
            horizontalArrangement = Arrangement.spacedBy(ResearchLayout.smallGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                when {
                    quote != null -> {
                        val status = stringResource(quoteStatusLabel(quote))
                        MetaText(stringResource(R.string.market_quote_meta, providerLabel(quote.provider), status))
                        if (quote.low != null && quote.high != null) {
                            MetaText(
                                stringResource(
                                    R.string.market_day_range,
                                    formatMarketPrice(quote.low, locale),
                                    formatMarketPrice(quote.high, locale),
                                ),
                            )
                        }
                    }
                    unavailable -> Text(
                        stringResource(R.string.market_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> MetaText(stringResource(R.string.waiting_quotes))
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    quote?.price?.let { formatMarketPrice(it, locale) } ?: "--",
                    style = ResearchType.body,
                    fontWeight = FontWeight.Bold,
                )
                quote?.takeIf(::quoteChangeVisible)?.changePercent?.let { change ->
                    Text(
                        "%+.2f%%".format(locale, change),
                        style = ResearchType.small,
                        color = if (change >= 0) AssetUp else AssetDown,
                    )
                }
            }
        }
        Hairline()
    }
}

@Composable
private fun MetaText(text: String) = Text(
    text,
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
)
