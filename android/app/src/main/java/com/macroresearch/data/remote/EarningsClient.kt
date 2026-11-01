package com.macroresearch.data.remote

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.macroresearch.data.model.EARNINGS_ZONE
import com.macroresearch.data.model.EarningsCalendar
import com.macroresearch.data.model.EarningsEvent
import com.macroresearch.data.model.TradingViewForecast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.Proxy
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private fun JsonElement?.text(): String? = this?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
    ?.asString?.trim()?.takeUnless { it.isEmpty() || it in setOf("N/A", "--", "-") }

internal fun earningsNumber(value: JsonElement?): String? {
    if (value == null || value.isJsonNull || !value.isJsonPrimitive) return null
    var text = value.asString.replace(Regex("[,$%]"), "").trim()
    if (text.startsWith('(') && text.endsWith(')')) text = "-${text.substring(1, text.length - 1)}"
    return text.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString()
}

internal fun parseNasdaqEarnings(raw: String, date: LocalDate): List<EarningsEvent> {
    val root = JsonParser.parseString(raw).asJsonObject
    require(root.getAsJsonObject("status")?.get("rCode")?.asInt == 200) { "Invalid Nasdaq status" }
    val data = root.getAsJsonObject("data") ?: error("Missing Nasdaq data")
    val asOf = LocalDate.parse(data.get("asOf").asString, DateTimeFormatter.ofPattern("EEE, MMM d, uuuu", Locale.US))
    require(asOf == date) { "Nasdaq returned a different date" }
    val rows = data.get("rows") ?: error("Missing Nasdaq rows")
    if (rows.isJsonNull) return emptyList()
    val events = rows.asJsonArray.map { element ->
        val row = element.asJsonObject
        EarningsEvent(
            symbol = row.get("symbol").text() ?: error("Missing Nasdaq symbol"),
            name = row.get("name").text() ?: error("Missing Nasdaq company"),
            source = "nasdaq",
            session = when (row.get("time").text()) { "time-pre-market" -> "pre_market"; "time-after-hours" -> "after_hours"; else -> "unknown" },
            fiscalQuarterEnding = row.get("fiscalQuarterEnding").text(),
            epsForecast = earningsNumber(row.get("epsForecast")),
            analystCount = row.get("noOfEsts").text()?.toIntOrNull()?.takeIf { it >= 0 },
            marketCap = earningsNumber(row.get("marketCap")),
            actualEps = earningsNumber(row.get("eps")),
            surprisePercent = earningsNumber(row.get("surprise")),
            lastYearEps = earningsNumber(row.get("lastYearEPS")),
        )
    }
    require(events.map { it.symbol }.distinct().size == events.size) { "Ambiguous Nasdaq symbols" }
    return events.sortedBy { it.symbol }
}

internal val earningsColumns = listOf("name", "description", "earnings_release_next_date", "earnings_per_share_forecast_next_fq", "revenue_forecast_next_fq", "currency")

internal fun parseTradingViewEarnings(raw: String, date: LocalDate): List<EarningsEvent> {
    val root = JsonParser.parseString(raw).asJsonObject
    val rows = root.getAsJsonArray("data") ?: error("Missing TradingView data")
    require(root.get("totalCount")?.asInt == rows.size()) { "Incomplete TradingView response" }
    require(rows.map { it.asJsonObject.get("s").text() }.distinct().size == rows.size()) { "Duplicate TradingView ticker" }
    return rows.map { element ->
        val row = element.asJsonObject
        val ticker = row.get("s").text()?.takeIf { ':' in it } ?: error("Missing TradingView ticker")
        val d = row.getAsJsonArray("d") ?: error("Missing TradingView columns")
        require(d.size() == earningsColumns.size) { "Invalid TradingView columns" }
        val symbol = d[0].text() ?: error("Missing TradingView symbol")
        val nextDate = d[2].takeUnless { it.isJsonNull }?.asBigDecimal?.longValueExact()
            ?.let { Instant.ofEpochSecond(it).atZone(EARNINGS_ZONE).toLocalDate() }
        EarningsEvent(symbol, d[1].text() ?: symbol, "tradingview", tradingView = TradingViewForecast(
            ticker = ticker, nextDate = nextDate?.toString(),
            nextEpsForecast = earningsNumber(d[3]), nextRevenueForecast = earningsNumber(d[4]),
            quoteCurrency = d[5].text(), dateConflict = nextDate != null && nextDate != date,
        ))
    }
}

internal fun mergeEarnings(primary: List<EarningsEvent>, secondary: List<EarningsEvent>): List<EarningsEvent> {
    val bySymbol = secondary.groupBy { it.symbol }
    return primary.map { event ->
        if (event.actualEps != null) event else event.copy(tradingView = bySymbol[event.symbol]?.singleOrNull()?.tradingView)
    }
}

/** Global per-provider gates include Retry-After; callers share one instance per application. */
class EarningsClient(
    private val client: OkHttpClient,
    private val proxy: () -> Proxy? = { null },
    private val nasdaqUrl: String = "https://api.nasdaq.com/api/calendar/earnings",
    private val tradingViewUrl: String = "https://scanner.tradingview.com/america/scan",
    private val clock: () -> Instant = Instant::now,
) {
    private val mutex = Mutex()
    private val nextRequest = java.util.concurrent.ConcurrentHashMap<String, Instant>()

    suspend fun fetch(date: LocalDate, cached: EarningsCalendar? = null): EarningsCalendar = mutex.withLock {
        val now = clock()
        val http = proxy()?.let { client.newBuilder().proxy(it).build() } ?: client
        val primary = attempt {
            val url = nasdaqUrl.toHttpUrl().newBuilder().addQueryParameter("date", date.toString()).build()
            parseNasdaqEarnings(request(http, "nasdaq", Request.Builder().url(url)
                .header("Origin", "https://www.nasdaq.com").header("Referer", "https://www.nasdaq.com/").get().build()), date)
        }
        val symbols = primary.getOrNull()?.filter { it.actualEps == null }?.map { it.symbol }
        // Next-period scanner fields cannot be aligned to historical/reported occurrences.
        val fetchSecondary = date >= now.atZone(EARNINGS_ZONE).toLocalDate() && (symbols == null || symbols.isNotEmpty())
        val secondary = attempt {
            if (!fetchSecondary) emptyList() else {
                val filters = JsonArray().apply {
                    add(JsonObject().apply { addProperty("left", "is_primary"); addProperty("operation", "equal"); addProperty("right", true) })
                    add(JsonObject().apply {
                        addProperty("left", if (symbols == null) "earnings_release_next_date" else "name")
                        addProperty("operation", "in_range")
                        add("right", JsonArray().apply {
                            if (symbols == null) {
                                add(date.atStartOfDay(EARNINGS_ZONE).toEpochSecond())
                                add(date.plusDays(1).atStartOfDay(EARNINGS_ZONE).toEpochSecond() - 1)
                            } else symbols.forEach { add(it) }
                        })
                    })
                }
                val body = JsonObject().apply {
                    add("filter", filters)
                    add("columns", JsonArray().apply { earningsColumns.forEach { add(it) } })
                    add("range", JsonArray().apply { add(0); add(2000) })
                }
                parseTradingViewEarnings(request(http, "tradingview", Request.Builder().url(tradingViewUrl)
                    .header("Origin", "https://www.tradingview.com")
                    .post(body.toString().toRequestBody("application/json".toMediaType())).build()), date).also { events ->
                    if (symbols == null) require(events.all { it.tradingView?.nextDate == date.toString() }) { "TradingView fallback date mismatch" }
                }
            }
        }
        val warnings = buildList {
            if (primary.isFailure) add("nasdaq_unavailable")
            if (secondary.isFailure) add("tradingview_unavailable")
        }
        if (primary.isFailure && cached != null && (cached.nasdaqUpdatedAt != null || secondary.isFailure || !fetchSecondary)) {
            return@withLock cached.copy(stale = true, warnings = warnings)
        }
        val events = primary.getOrNull()?.let { mergeEarnings(it, secondary.getOrDefault(emptyList())) }
            ?: secondary.getOrNull()?.takeIf { fetchSecondary }?.filter { it.tradingView?.nextDate == date.toString() }
            ?: throw IOException("Earnings providers unavailable")
        EarningsCalendar(
            date = date.toString(), updatedAt = now.toString(), nasdaqUpdatedAt = now.toString().takeIf { primary.isSuccess },
            tradingViewUpdatedAt = now.toString().takeIf { fetchSecondary && secondary.isSuccess },
            warnings = warnings + if (events.any { it.tradingView?.dateConflict == true }) listOf("date_conflict") else emptyList(),
            events = events,
        )
    }

    private suspend fun request(http: OkHttpClient, source: String, request: Request): String {
        val now = clock()
        nextRequest[source]?.let { at ->
            val wait = java.time.Duration.between(now, at).toMillis()
            if (wait > 2000) throw IOException("Provider cooldown")
            if (wait > 0) delay(wait)
        }
        nextRequest[source] = clock().plusSeconds(1)
        return http.newCall(request.newBuilder().header("Accept", "application/json").cacheControl(okhttp3.CacheControl.FORCE_NETWORK).build()).awaitResponse { response ->
            if (response.code == 429 || response.code == 403) {
                val retry = response.header("Retry-After")
                val seconds = (retry?.toLongOrNull() ?: retry?.let { value -> runCatching {
                    java.time.Duration.between(clock(), java.time.ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).seconds
                }.getOrNull() } ?: 300).coerceIn(60, 86400)
                nextRequest[source] = clock().plusSeconds(seconds)
            }
            if (!response.isSuccessful) throw IOException("$source HTTP ${response.code}")
            response.body?.string() ?: throw IOException("Empty provider response")
        }
    }

    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try { Result.success(block()) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) { Result.failure(error) }
}
