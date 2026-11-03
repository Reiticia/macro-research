package com.macroresearch.data.model

import java.util.Locale

/** Provider overview, not a verified company announcement for this event's fiscal quarter. */
data class EarningsPage(val provider: String, val url: String)

private val pageSymbol = Regex("[A-Z][A-Z0-9]{0,9}(?:[.-][A-Z0-9]{1,4})?")
private val pageExchanges = setOf("NASDAQ", "NYSE", "AMEX", "NYSEARCA", "NYSEAMERICAN", "OTC")

/** Only fixed HTTPS hosts and exact source identifiers; never guess an exchange or company domain. */
fun EarningsEvent.earningsPage(): EarningsPage? {
    if (!pageSymbol.matches(symbol)) return null
    return when (source) {
        "nasdaq" -> EarningsPage("Nasdaq", "https://www.nasdaq.com/market-activity/stocks/${symbol.lowercase(Locale.ROOT)}/earnings")
        "tradingview" -> {
            val parts = tradingView?.ticker?.split(':') ?: return null
            if (parts.size != 2 || parts[0] !in pageExchanges || parts[1] != symbol) return null
            EarningsPage("TradingView", "https://www.tradingview.com/symbols/${parts[0]}-$symbol/financials-earnings/")
        }
        else -> null
    }
}
