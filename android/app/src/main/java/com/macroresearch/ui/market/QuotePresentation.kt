package com.macroresearch.ui.market

import com.macroresearch.data.model.LiveMarketQuote
import com.macroresearch.data.model.MarketQuotesResponse
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private val YIELD_SYMBOLS = setOf("us2y", "us10y")

/** True when `symbol` represents a Treasury yield quoted in percent per annum. */
internal fun isTreasuryYieldSymbol(symbol: String): Boolean =
    symbol.trim().lowercase(Locale.ROOT) in YIELD_SYMBOLS

/** Formats a market level, appending `%` with three decimals for Treasury yields. */
internal fun formatMarketQuoteValue(
    symbol: String,
    price: Double,
    locale: Locale = Locale.ENGLISH,
): String {
    if (isTreasuryYieldSymbol(symbol)) {
        val number = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 3
            maximumFractionDigits = 3
        }.format(price)
        return "$number%"
    }
    return formatMarketPrice(price, locale)
}

/**
 * Formats a quote's session move: Treasury yields convert `(price, changePercent)` back to the
 * underlying basis-point move `(price - previous) * 100`, while non-yield assets use `%`.
 */
internal fun formatMarketQuoteChange(
    quote: LiveMarketQuote,
    locale: Locale = Locale.ENGLISH,
    basisPointsLabel: String = "bp",
): String? {
    val changePercent = quote.changePercent ?: return null
    if (!changePercent.isFinite() || !quote.price.isFinite()) return null
    if (isTreasuryYieldSymbol(quote.symbol)) {
        val denominator = 1.0 + changePercent / 100.0
        if (denominator <= 0.0 || !denominator.isFinite()) return null
        val previousYield = quote.price / denominator
        val basisPoints = (quote.price - previousYield) * 100.0
        if (!basisPoints.isFinite()) return null
        return "%+.1f%s".format(locale, basisPoints, basisPointsLabel)
    }
    return "%+.2f%%".format(locale, changePercent)
}

/** A failed refresh preserves values, never their old claim to freshness. */
internal fun MarketQuotesResponse.asStale(): MarketQuotesResponse =
    copy(quotes = quotes.map { it.copy(stale = true) })

/** Partial success keeps the last known price for missing symbols, explicitly marked old. */
internal fun mergeQuoteRefresh(previous: MarketQuotesResponse?, incoming: MarketQuotesResponse): MarketQuotesResponse {
    val received = incoming.quotes.map { it.symbol }.toSet()
    val retained = previous?.quotes.orEmpty().filter { it.symbol !in received }.map { it.copy(stale = true) }
    return incoming.copy(quotes = incoming.quotes + retained)
}

internal fun quoteTimeLabel(timestamp: String, locale: Locale, zone: ZoneId = ZoneId.systemDefault()): String? =
    runCatching {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale)
            .format(Instant.parse(timestamp).atZone(zone))
    }.getOrNull()
