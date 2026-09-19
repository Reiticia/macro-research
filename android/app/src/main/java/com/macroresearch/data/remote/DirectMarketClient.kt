package com.macroresearch.data.remote

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.macroresearch.data.model.EconomicEvent
import com.macroresearch.data.model.LiveMarketQuote
import com.macroresearch.data.model.MarketReaction
import com.macroresearch.data.model.MarketResponse
import com.macroresearch.data.model.MarketSnapshot
import com.macroresearch.data.model.MarketQuotesResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Duration
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

class DirectMarketClient(
    private val client: OkHttpClient,
    /** Optional proxy for providers that many networks block (Yahoo, CNBC). */
    private val proxy: () -> java.net.Proxy? = { null },
    private val cnbcQuoteBase: String = CNBC_QUOTE_BASE,
    private val cnbcChartBase: String = CNBC_CHART_BASE,
) {
    private val quoteCache = ConcurrentHashMap<String, CachedQuote>()
    private val quoteLocks = ConcurrentHashMap<String, Mutex>()

    /**
     * Direct mode keeps the same five-second in-process quote cache as the server. Unlike the
     * server, a single device can refresh all expired symbols concurrently without staggering.
     */
    suspend fun quotes(symbols: List<String>): MarketQuotesResponse = coroutineScope {
        val results = symbols.distinct().map { symbol ->
            async { symbol to runCatching { cachedQuote(symbol) } }
        }.awaitAll()
        MarketQuotesResponse(
            quotes = results.mapNotNull { it.second.getOrNull() },
            unavailable = results.filter { it.second.isFailure }.map { it.first },
        )
    }

    private suspend fun cachedQuote(symbol: String): LiveMarketQuote =
        quoteLocks.computeIfAbsent(symbol) { Mutex() }.withLock {
            val now = System.nanoTime()
            quoteCache[symbol]?.takeIf { now - it.fetchedAtNanos <= QUOTE_REFRESH_NANOS }
                ?.let { return@withLock it.quote }
            runCatching { fetchQuote(symbol) }
                .onSuccess { quoteCache[symbol] = CachedQuote(it, System.nanoTime()) }
                .getOrElse { error ->
                    val fallbackNow = System.nanoTime()
                    quoteCache[symbol]
                        ?.takeIf { fallbackNow - it.fetchedAtNanos <= QUOTE_STALE_NANOS }
                        ?.quote
                        ?.copy(stale = true)
                        ?: throw error
                }
        }

    private suspend fun fetchQuote(symbol: String): LiveMarketQuote =
        if (symbol == "bitcoin" || symbol == "ethereum") {
            runCatching { binanceQuote(symbol) }.getOrElse { biquoteQuote(symbol) }
        } else {
            publicMarketQuote(symbol)
        }

    suspend fun eventMarket(
        event: EconomicEvent,
        symbols: List<String> = EVENT_SYMBOLS,
    ): MarketResponse = coroutineScope {
        val eventTime = runCatching { Instant.parse(event.eventTime) }.getOrNull()
            ?: return@coroutineScope MarketResponse(emptyList(), emptyList())
        if (eventTime.isAfter(Instant.now().plusSeconds(60))) {
            val live = quotes(symbols).quotes
            return@coroutineScope MarketResponse(
                snapshots = live.map { quote ->
                    MarketSnapshot(
                        id = snapshotId(event.id, quote.symbol, quote.timestamp),
                        eventId = event.id,
                        symbol = quote.symbol,
                        timestamp = quote.timestamp,
                        price = quote.price,
                        open = null,
                        high = quote.high,
                        low = quote.low,
                        close = quote.price,
                        volume = null,
                    )
                },
                reactions = emptyList(),
            )
        }
        val end = minOf(Instant.now(), eventTime.plusSeconds(65 * 60))
        val start = eventTime.minusSeconds(6 * 60)
        val results = symbols.distinct().map { symbol ->
            async { runCatching { candles(symbol, start, end) }.getOrDefault(emptyList()) }
        }.awaitAll().flatten()
        val snapshots = results.map { candle ->
            MarketSnapshot(
                id = snapshotId(event.id, candle.symbol, candle.timestamp.toString()),
                eventId = event.id,
                symbol = candle.symbol,
                timestamp = candle.timestamp.toString(),
                price = candle.close,
                open = candle.open,
                high = candle.high,
                low = candle.low,
                close = candle.close,
                volume = candle.volume,
            )
        }
        val reactions = results.groupBy(Candle::symbol).mapNotNull { (symbol, values) ->
            reaction(event.id, symbol, eventTime, values)
        }
        MarketResponse(snapshots, reactions)
    }

    private suspend fun publicMarketQuote(symbol: String): LiveMarketQuote {
        // Treasury yields have no BiQuote ticker, and Yahoo is unreachable or rate limited on
        // many networks, so CNBC supplies them first.
        if (symbol in CNBC_SYMBOLS) {
            runCatching { return cnbcQuote(symbol) }
        }
        if (symbol in BIQUOTE_TICKERS) {
            runCatching { return biquoteQuote(symbol) }
        }
        return yahooQuote(symbol)
    }

    private suspend fun cnbcQuote(symbol: String): LiveMarketQuote = withContext(Dispatchers.IO) {
        val ticker = CNBC_SYMBOLS[symbol] ?: error("Unsupported CNBC symbol: $symbol")
        parseCnbcQuote(
            symbol,
            getJsonRaw(
                "$cnbcQuoteBase?symbols=$ticker&requestMethod=itv&noform=1&partnerId=2&fund=1&exthrs=1&output=json&events=1",
                viaProxy = true,
                referer = CNBC_REFERER,
            ),
        )
    }

    /** CNBC sends offsets without a colon ("2026-09-11T11:17:11.000-0400"), which strict ISO rejects. */
    internal fun parseCnbcInstant(value: String): String? = runCatching {
        OffsetDateTime.parse(value).toInstant()
    }.recoverCatching {
        OffsetDateTime.parse(value, CNBC_TIME_FORMAT).toInstant()
    }.getOrNull()?.toString()

    internal fun parseCnbcQuote(symbol: String, body: String): LiveMarketQuote {
        val root = JsonParser.parseString(body).asJsonObject
        val quote = root.getAsJsonObject("FormattedQuoteResult")
            ?.getAsJsonArray("FormattedQuote")?.firstOrNull()?.asJsonObject
            ?: error("CNBC returned no quote for $symbol")
        fun number(name: String) = quote.stringOrNull(name)?.removeSuffix("%")?.toDoubleOrNull()
        val price = number("last") ?: error("CNBC returned no price for $symbol")
        // CNBC's own change_pct is inconsistent with its change field, so derive the move from
        // the last close of the previous session, exactly like the other providers.
        val previous = number("previous_day_closing") ?: number("open")
        return LiveMarketQuote(
            symbol = symbol,
            timestamp = quote.stringOrNull("last_time")?.let(::parseCnbcInstant) ?: Instant.now().toString(),
            price = price,
            provider = "cnbc",
            changePercent = previous?.takeIf { it != 0.0 }?.let { (price - it) / it * 100.0 },
            high = number("high"),
            low = number("low"),
            marketState = quote.stringOrNull("curmktstatus")?.lowercase(Locale.ROOT),
            stale = false,
        )
    }

    private suspend fun yahooQuote(symbol: String): LiveMarketQuote {
        val chart = yahooChart(symbol, mapOf("interval" to "1m", "range" to "1d"))
        val meta = chart.getAsJsonObject("meta")
        val quote = chart.getAsJsonObject("indicators")
            ?.getAsJsonArray("quote")?.firstOrNull()?.asJsonObject
        val closes = quote?.getAsJsonArray("close")
        val price = meta.doubleOrNull("regularMarketPrice")
            ?: closes?.mapNotNull { it.takeUnless { value -> value.isJsonNull }?.asDouble }?.lastOrNull()
            ?: error("Yahoo returned no price for $symbol")
        val previous = meta.doubleOrNull("chartPreviousClose")
        val timestamp = meta.longOrNull("regularMarketTime")
            ?.let { Instant.ofEpochSecond(it).toString() } ?: Instant.now().toString()
        return LiveMarketQuote(
            symbol = symbol,
            timestamp = timestamp,
            price = price,
            provider = "yahoo",
            changePercent = previous?.takeIf { it > 0.0 }?.let { (price - it) / it * 100.0 },
            high = meta.doubleOrNull("regularMarketDayHigh"),
            low = meta.doubleOrNull("regularMarketDayLow"),
            marketState = meta.stringOrNull("marketState")?.lowercase(Locale.ROOT),
            stale = false,
        )
    }

    private suspend fun binanceQuote(symbol: String): LiveMarketQuote = withContext(Dispatchers.IO) {
        val ticker = BINANCE_TICKERS[symbol] ?: error("Unsupported Binance symbol: $symbol")
        val url = "$BINANCE_BASE/ticker/24hr".toHttpUrl().newBuilder()
            .addQueryParameter("symbol", ticker).build()
        val json = getJson(url.toString())
        LiveMarketQuote(
            symbol = symbol,
            timestamp = json.longOrNull("closeTime")?.let { Instant.ofEpochMilli(it).toString() }
                ?: Instant.now().toString(),
            price = json.requiredDouble("lastPrice"),
            provider = "binance",
            changePercent = json.doubleOrNull("priceChangePercent"),
            high = json.doubleOrNull("highPrice"),
            low = json.doubleOrNull("lowPrice"),
            marketState = "open",
            stale = false,
        )
    }

    private suspend fun biquoteQuote(symbol: String): LiveMarketQuote = withContext(Dispatchers.IO) {
        val ticker = BIQUOTE_TICKERS[symbol] ?: error("Unsupported BiQuote symbol: $symbol")
        val url = "$BIQUOTE_BASE/api/$ticker".toHttpUrl().newBuilder()
            .addQueryParameter("allowStale", "true").build()
        val json = getJson(url.toString())
        LiveMarketQuote(
            symbol = symbol,
            timestamp = json.stringOrNull("timestamp") ?: Instant.now().toString(),
            price = json.requiredDouble("mid"),
            provider = "biquote",
            changePercent = json.doubleOrNull("dayDiffPercent"),
            high = json.doubleOrNull("high"),
            low = json.doubleOrNull("low"),
            marketState = json.stringOrNull("marketState"),
            stale = json.get("stale")?.takeUnless { it.isJsonNull }?.asBoolean ?: false,
        )
    }

    private suspend fun candles(symbol: String, start: Instant, end: Instant): List<Candle> = when {
        symbol in CNBC_SYMBOLS ->
            runCatching { cnbcCandles(symbol, end) }.getOrElse { yahooCandles(symbol, start, end) }
        symbol == "bitcoin" || symbol == "ethereum" ->
            runCatching { binanceCandles(symbol, start, end) }
                .getOrElse { biquoteCandles(symbol, start, end) }
        symbol in BIQUOTE_TICKERS -> runCatching { biquoteCandles(symbol, start, end) }
            .getOrElse { yahooCandles(symbol, start, end) }
        else -> yahooCandles(symbol, start, end)
    }

    /**
     * CNBC's one-day chart returns one-minute bars for the last few sessions, which covers the
     * pre-release baseline and the reaction windows without needing a key.
     */
    private suspend fun cnbcCandles(symbol: String, end: Instant): List<Candle> = withContext(Dispatchers.IO) {
        val ticker = CNBC_SYMBOLS[symbol] ?: error("Unsupported CNBC symbol: $symbol")
        parseCnbcCandles(
            symbol,
            getJsonRaw(cnbcChartUrl(ticker), viaProxy = true, referer = CNBC_REFERER),
            end,
        )
    }

    /** CNBC expects the symbol as a query parameter; a path segment answers HTTP 400. */
    internal fun cnbcChartUrl(ticker: String): String =
        "$cnbcChartBase/1D.json?symbol=$ticker&interval=1&requestMethod=itv&events=1"

    internal fun parseCnbcCandles(symbol: String, body: String, end: Instant): List<Candle> {
        val root = JsonParser.parseString(body).asJsonObject
        val bars = root.getAsJsonObject("barData")?.getAsJsonArray("priceBars") ?: return emptyList()
        return bars.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val bar = element.asJsonObject
            val timestamp = bar.longOrNull("tradeTimeinMills")?.let { Instant.ofEpochMilli(it) }
                ?: return@mapNotNull null
            if (timestamp.isAfter(end.plusSeconds(60))) return@mapNotNull null
            runCatching {
                Candle(
                    symbol = symbol,
                    timestamp = timestamp,
                    open = bar.stringOrNull("open")?.toDoubleOrNull(),
                    high = bar.stringOrNull("high")?.toDoubleOrNull(),
                    low = bar.stringOrNull("low")?.toDoubleOrNull(),
                    close = bar.stringOrNull("close")?.toDoubleOrNull() ?: return@mapNotNull null,
                    volume = null,
                )
            }.getOrNull()
        }
    }

    private suspend fun yahooCandles(symbol: String, start: Instant, end: Instant): List<Candle> {
        val chart = yahooChart(
            symbol,
            mapOf(
                "interval" to "1m",
                "period1" to start.epochSecond.toString(),
                "period2" to end.plusSeconds(60).epochSecond.toString(),
            ),
        )
        val timestamps = chart.getAsJsonArray("timestamp") ?: return emptyList()
        val quote = chart.getAsJsonObject("indicators")
            ?.getAsJsonArray("quote")?.firstOrNull()?.asJsonObject ?: return emptyList()
        return timestamps.mapIndexedNotNull { index, value ->
            val timestamp = runCatching { Instant.ofEpochSecond(value.asLong) }.getOrNull()
                ?: return@mapIndexedNotNull null
            Candle(
                symbol = symbol,
                timestamp = timestamp,
                open = quote.arrayDouble("open", index),
                high = quote.arrayDouble("high", index),
                low = quote.arrayDouble("low", index),
                close = quote.arrayDouble("close", index) ?: return@mapIndexedNotNull null,
                volume = quote.arrayDouble("volume", index),
            )
        }
    }

    private suspend fun binanceCandles(symbol: String, start: Instant, end: Instant): List<Candle> =
        withContext(Dispatchers.IO) {
            val ticker = BINANCE_TICKERS[symbol] ?: error("Unsupported Binance symbol: $symbol")
            val url = "$BINANCE_BASE/klines".toHttpUrl().newBuilder()
                .addQueryParameter("symbol", ticker)
                .addQueryParameter("interval", "1m")
                .addQueryParameter("startTime", start.toEpochMilli().toString())
                .addQueryParameter("endTime", end.toEpochMilli().toString())
                .addQueryParameter("limit", "1000")
                .build()
            getJsonArray(url.toString()).mapNotNull { element ->
                val row = element.asJsonArray
                runCatching {
                    Candle(
                        symbol = symbol,
                        timestamp = Instant.ofEpochMilli(row[0].asLong),
                        open = row[1].asString.toDouble(),
                        high = row[2].asString.toDouble(),
                        low = row[3].asString.toDouble(),
                        close = row[4].asString.toDouble(),
                        volume = row[5].asString.toDoubleOrNull(),
                    )
                }.getOrNull()
            }
        }

    private suspend fun biquoteCandles(symbol: String, start: Instant, end: Instant): List<Candle> =
        withContext(Dispatchers.IO) {
            val ticker = BIQUOTE_TICKERS[symbol] ?: error("Unsupported BiQuote symbol: $symbol")
            val url = "$BIQUOTE_BASE/api/$ticker/ohlc".toHttpUrl().newBuilder()
                .addQueryParameter("interval", "1m")
                .addQueryParameter("limit", "1000")
                .addQueryParameter("from", start.toString())
                .addQueryParameter("to", end.toString())
                .build()
            val bars = getJson(url.toString()).getAsJsonArray("bars") ?: return@withContext emptyList()
            bars.mapNotNull { element ->
                val bar = element.asJsonObject
                if (bar.get("isOpen")?.asBoolean == true) return@mapNotNull null
                runCatching {
                    Candle(
                        symbol = symbol,
                        timestamp = Instant.parse(bar.get("openTime").asString),
                        open = bar.requiredDouble("open"),
                        high = bar.requiredDouble("high"),
                        low = bar.requiredDouble("low"),
                        close = bar.requiredDouble("close"),
                        volume = bar.doubleOrNull("volume") ?: bar.doubleOrNull("tickVolume"),
                    )
                }.getOrNull()
            }
        }

    private suspend fun yahooChart(symbol: String, parameters: Map<String, String>): JsonObject {
        val ticker = YAHOO_TICKERS[symbol] ?: error("Unsupported Yahoo symbol: $symbol")
        val builder = YAHOO_BASE.toHttpUrl().newBuilder().addPathSegment(ticker)
        parameters.forEach(builder::addQueryParameter)
        val root = withContext(Dispatchers.IO) {
            JsonParser.parseString(getJsonRaw(builder.build().toString(), viaProxy = true)).asJsonObject
        }
        val chart = root.getAsJsonObject("chart")
        return chart.getAsJsonArray("result")?.firstOrNull()?.asJsonObject
            ?: error("Yahoo returned no data for $symbol")
    }

    private fun getJson(url: String): JsonObject =
        JsonParser.parseString(getJsonRaw(url)).asJsonObject

    /** [viaProxy] routes the call through the user's proxy when one is configured. */
    private fun getJsonRaw(
        url: String,
        viaProxy: Boolean = false,
        referer: String? = null,
    ): String {
        val request = Request.Builder().url(url).header("Accept", "application/json").apply {
            referer?.let { header("Referer", it) }
        }.build()
        val http = if (viaProxy) proxy()?.let { client.newBuilder().proxy(it).build() } ?: client else client
        http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Market provider returned HTTP ${response.code}" }
            return response.body?.string().orEmpty()
        }
    }

    private fun getJsonArray(url: String) = run {
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Market provider returned HTTP ${response.code}" }
            JsonParser.parseString(response.body?.string().orEmpty()).asJsonArray
        }
    }

    internal fun reaction(
        eventId: Long,
        symbol: String,
        eventTime: Instant,
        values: List<Candle>,
    ): MarketReaction? {
        val baseline = values.filter { it.timestamp.isBefore(eventTime) }.maxByOrNull { it.timestamp }
            ?: return null
        if (Duration.between(baseline.timestamp, eventTime).seconds > 180 || baseline.close == 0.0) return null
        fun change(minutes: Long): Double? {
            val target = eventTime.plusSeconds(minutes * 60)
            if (target.isAfter(Instant.now())) return null
            val sample = values.filter { !it.timestamp.isAfter(target) }.maxByOrNull { it.timestamp }
                ?: return null
            if (abs(Duration.between(sample.timestamp, target).seconds) > 180) return null
            return if (symbol in YIELD_SYMBOLS) {
                (sample.close - baseline.close) * 100.0
            } else {
                (sample.close - baseline.close) / baseline.close * 100.0
            }
        }
        return MarketReaction(
            eventId = eventId,
            symbol = symbol,
            baselinePrice = baseline.close,
            reactionUnit = if (symbol in YIELD_SYMBOLS) "basis_points" else "percent",
            change1m = change(1),
            change5m = change(5),
            change15m = change(15),
            change30m = change(30),
            change60m = change(60),
        )
    }

    private data class CachedQuote(
        val quote: LiveMarketQuote,
        val fetchedAtNanos: Long,
    )

    internal data class Candle(
        val symbol: String,
        val timestamp: Instant,
        val open: Double?,
        val high: Double?,
        val low: Double?,
        val close: Double,
        val volume: Double?,
    )

    companion object {
        private const val YAHOO_BASE = "https://query1.finance.yahoo.com/v8/finance/chart"
        private const val BINANCE_BASE = "https://data-api.binance.vision/api/v3"
        private const val BIQUOTE_BASE = "https://biquote.io"
        private const val QUOTE_REFRESH_NANOS = 5_000_000_000L
        private const val QUOTE_STALE_NANOS = 300_000_000_000L
        private const val CNBC_REFERER = "https://www.cnbc.com/"
        private const val CNBC_QUOTE_BASE = "https://quote.cnbc.com/quote-html-webservice/restQuote/symbolType/symbol"
        private const val CNBC_CHART_BASE = "https://ts-api.cnbc.com/harmony/app/charts"
        /** Treasury yields: CNBC symbols, quoted in percent just like Yahoo's ^TNX/^UST2YR. */
        private val CNBC_SYMBOLS = mapOf("us2y" to "US2Y", "us10y" to "US10Y")
        private val CNBC_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXX", Locale.US)
        private val EVENT_SYMBOLS = listOf("gold", "dxy", "us2y", "us10y", "nasdaq100", "bitcoin")
        private val YIELD_SYMBOLS = setOf("us2y", "us10y")
        private val BINANCE_TICKERS = mapOf("bitcoin" to "BTCUSDT", "ethereum" to "ETHUSDT")
        private val BIQUOTE_TICKERS = mapOf(
            "nasdaq100" to "USTEC", "sp500" to "US500",
            "gold" to "XAUUSD", "silver" to "XAGUSD", "dxy" to "DXY", "eur_usd" to "EURUSD",
            "bitcoin" to "BTCUSD", "ethereum" to "ETHUSD",
        )
        private val YAHOO_TICKERS = mapOf(
            "gold" to "GC=F", "silver" to "SI=F", "sp500" to "^GSPC", "nasdaq100" to "^NDX",
            "dxy" to "DX-Y.NYB", "eur_usd" to "EURUSD=X", "us2y" to "^UST2YR", "us10y" to "^TNX",
        )

        private fun snapshotId(eventId: Long, symbol: String, timestamp: String): Long =
            stableEventId("$eventId|$symbol|$timestamp")

        private fun JsonObject.stringOrNull(name: String): String? =
            get(name)?.takeUnless { it.isJsonNull }?.asString

        private fun JsonObject.doubleOrNull(name: String): Double? =
            stringOrNull(name)?.toDoubleOrNull()

        private fun JsonObject.longOrNull(name: String): Long? =
            stringOrNull(name)?.toLongOrNull()

        private fun JsonObject.requiredDouble(name: String): Double =
            doubleOrNull(name) ?: error("Market response omitted $name")

        private fun JsonObject.arrayDouble(name: String, index: Int): Double? =
            getAsJsonArray(name)?.getOrNull(index)?.takeUnless { it.isJsonNull }?.asDouble
    }
}

private operator fun <T> Iterable<T>.get(index: Int): T = elementAt(index)
private fun <T> Iterable<T>.getOrNull(index: Int): T? = runCatching { elementAt(index) }.getOrNull()
