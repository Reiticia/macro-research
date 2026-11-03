package com.macroresearch.data.model

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.time.Instant

data class EarningsQuote(
    val symbol: String,
    val price: String,
    val currency: String?,
    val timestamp: String,
    val fetchedAt: String,
    val provider: String,
    val timestampBasis: String,
    val stale: Boolean,
)

internal fun decodeEarningsQuote(raw: String, symbol: String, gson: Gson = Gson()): EarningsQuote {
    val root = JsonParser.parseString(raw).asJsonObject
    require(root.get("symbol")?.asString == symbol)
    require(root.get("price")?.asJsonPrimitive?.isString == true)
    require(root.get("stale")?.asJsonPrimitive?.isBoolean == true)
    val quote = gson.fromJson(root, EarningsQuote::class.java)
    require(quote.price.toBigDecimalOrNull()?.signum() == 1)
    require(quote.currency == null || quote.currency.matches(Regex("[A-Z]{3}")))
    require(quote.provider == "yahoo" && quote.timestampBasis in setOf("provider_quote", "minute_bar_close"))
    require(Instant.parse(quote.timestamp) <= Instant.parse(quote.fetchedAt))
    return quote
}
