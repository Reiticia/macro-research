package com.macroresearch.data.remote

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.macroresearch.data.CalendarBackoffStore
import com.macroresearch.data.CalendarSource
import com.macroresearch.data.CalendarUnavailableException
import com.macroresearch.data.CalendarWarning
import com.macroresearch.data.model.EconomicEvent
import com.macroresearch.data.model.releaseStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.math.BigDecimal
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Proxy
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Economic calendar aggregated on-device from keyless JSON sources.
 *
 * Primary source is TradingView's economic calendar endpoint, which covers arbitrary
 * date ranges with actual / previous / forecast values. When TradingView fails or
 * returns nothing and the requested range overlaps the current week, the client falls
 * back to the Forex Factory weekly JSON feed. Neither source needs an API key.
 */
data class CalendarFetchResult(val events: List<EconomicEvent>, val warning: CalendarWarning? = null)

/** Raised when the fallback provider answered HTTP 429. [retryAfterSeconds] comes from the response. */
internal class CalendarRateLimitedException(val retryAfterSeconds: Long) :
    Exception("Fallback calendar returned HTTP 429")

class EconomicCalendarClient(
    private val client: OkHttpClient,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val proxy: () -> Proxy? = { null },
    private val primaryUrl: String = TRADING_VIEW_URL,
    private val fallbackUrl: String = FOREX_FACTORY_URL,
    private val primaryConnectTimeout: Duration = PRIMARY_CONNECT_TIMEOUT,
    private val primaryCallTimeout: Duration = PRIMARY_CALL_TIMEOUT,
    /** Survives process restarts so a cold start does not repeat known failures or rate limits. */
    private val backoff: CalendarBackoffStore = CalendarBackoffStore.inMemory(),
) {
    /** Serializes calendar traffic so Home and History cannot race both providers. */
    private val fetchMutex = Mutex()

    /** If both providers are blocked, retry when the first one becomes available. */
    internal fun nextRetryAt(now: Instant = Instant.now()): Instant? {
        val primary = backoff.blockedUntil(CalendarSource.PRIMARY)?.takeIf { it.isAfter(now) } ?: return null
        val fallback = backoff.blockedUntil(CalendarSource.FALLBACK)?.takeIf { it.isAfter(now) } ?: return null
        return minOf(primary, fallback)
    }

    suspend fun events(
        start: LocalDate,
        end: LocalDate,
        countryCodes: Collection<String>? = null,
    ): List<EconomicEvent> = fetch(start, end, countryCodes).events

    suspend fun fetch(
        start: LocalDate,
        end: LocalDate,
        countryCodes: Collection<String>? = null,
    ): CalendarFetchResult = fetchMutex.withLock {
        withContext(Dispatchers.IO) {
            require(!end.isBefore(start)) { "Calendar end date is before start date" }
            val now = Instant.now()
            val activeProxy = proxy()
            val http = activeProxy?.let { client.newBuilder().proxy(it).build() } ?: client
            // The affected device receives unusable synthesized IPv6 addresses. Prefer a real
            // IPv4 route for the primary source, while leaving an explicitly configured proxy in
            // charge of its own DNS. The total timeout still caps TLS/read black-holes.
            val primaryHttp = http.newBuilder()
                .connectTimeout(primaryConnectTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .callTimeout(primaryCallTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .apply { if (activeProxy == null) dns(IPV4_FIRST_DNS) }
                .build()
            var primaryError: Exception? = null
            val primaryBlockedFor = if (activeProxy == null) {
                remainingBlock(CalendarSource.PRIMARY, now)
            } else {
                null
            }
            val primaryEvents = if (primaryBlockedFor != null) {
                primaryError = IOException("Primary calendar cooling down for ${primaryBlockedFor}s")
                emptyList()
            } else {
                try {
                    fetchTradingView(primaryHttp, start, end, countryCodes, now).also {
                        backoff.clear(CalendarSource.PRIMARY)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // HTTP errors can recover immediately, but a socket/TLS timeout on this
                    // network is stable. Persist a short circuit-breaker across app restarts.
                    if (activeProxy == null && error is IOException) {
                        backoff.block(
                            CalendarSource.PRIMARY,
                            now.plusSeconds(PRIMARY_FAILURE_BACKOFF.seconds),
                        )
                    }
                    primaryError = error
                    emptyList()
                }
            }
            if (primaryEvents.isNotEmpty()) return@withContext CalendarFetchResult(primaryEvents)

            val primaryDetail = primaryError?.describe()
            val blockedFor = remainingBlock(CalendarSource.FALLBACK, now)
            if (blockedFor != null) {
                // The provider already asked us to wait. Calling it again cannot succeed, so
                // report the wait instead of spending the request and extending the ban.
                throw CalendarUnavailableException(
                    CalendarWarning(CalendarWarning.Reason.FALLBACK_RATE_LIMITED, primaryDetail, blockedFor),
                )
            }
            val fallback = try {
                fetchForexFactory(http, start, end, now)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Report what actually blocked the refresh. Re-throwing the primary error here
                // hid rate limits behind an unrelated primary timeout.
                throw CalendarUnavailableException(
                    if (error is CalendarRateLimitedException) {
                        CalendarWarning(
                            CalendarWarning.Reason.FALLBACK_RATE_LIMITED,
                            primaryDetail,
                            error.retryAfterSeconds,
                        )
                    } else {
                        CalendarWarning(
                            CalendarWarning.Reason.ALL_SOURCES_UNAVAILABLE,
                            listOfNotNull(
                                primaryDetail?.let { "primary=$it" },
                                "fallback=${error.describe()}",
                            ).joinToString("; "),
                        )
                    },
                )
            }
            // A weekly schedule is not a successful historical result sync. Expose degradation
            // even when it returned rows, so missing actuals are never mistaken for future releases.
            val warning = primaryDetail?.let {
                CalendarWarning(CalendarWarning.Reason.PRIMARY_UNAVAILABLE, it)
            } ?: if (fallback.isNotEmpty()) {
                CalendarWarning(
                    CalendarWarning.Reason.PRIMARY_UNAVAILABLE,
                    "Primary source returned no events; weekly schedule only",
                )
            } else {
                null
            }
            CalendarFetchResult(fallback, warning)
        }
    }

    private suspend fun fetchTradingView(
        http: OkHttpClient,
        start: LocalDate,
        end: LocalDate,
        countryCodes: Collection<String>?,
        now: Instant,
    ): List<EconomicEvent> {
        val from = start.atStartOfDay(zone).toInstant()
        // `to` is an inclusive instant: the last second of the local day, so the window covers
        // exactly the requested local days instead of pulling in the next day's all-day rows.
        val to = end.plusDays(1).atStartOfDay(zone).toInstant().minusSeconds(1)
        val builder = primaryUrl.toHttpUrl().newBuilder()
            .addQueryParameter("from", DateTimeFormatter.ISO_INSTANT.format(from))
            .addQueryParameter("to", DateTimeFormatter.ISO_INSTANT.format(to))
        countryCodes?.takeIf { it.isNotEmpty() }
            ?.let { builder.addQueryParameter("countries", it.joinToString(",")) }
        val request = Request.Builder()
            .url(builder.build())
            .header("Accept", "application/json")
            .header("Origin", "https://www.tradingview.com")
            .cacheControl(CacheControl.FORCE_NETWORK)
            .build()
        return http.newCall(request).awaitResponse { response ->
            check(response.isSuccessful) { "Calendar provider returned HTTP ${response.code}" }
            parseTradingView(response.body?.string().orEmpty(), now)
        }
    }

    private suspend fun fetchForexFactory(http: OkHttpClient, start: LocalDate, end: LocalDate, now: Instant): List<EconomicEvent> {
        val request = Request.Builder()
            .url(fallbackUrl)
            .header("Accept", "application/json")
            .cacheControl(CacheControl.FORCE_NETWORK)
            .build()
        return http.newCall(request).awaitResponse { response ->
            if (response.code == HTTP_TOO_MANY_REQUESTS) {
                val wait = retryAfterSeconds(response, now) ?: DEFAULT_RATE_LIMIT_BACKOFF.seconds
                backoff.block(CalendarSource.FALLBACK, now.plusSeconds(wait))
                throw CalendarRateLimitedException(wait)
            }
            check(response.isSuccessful) { "Fallback calendar returned HTTP ${response.code}" }
            backoff.clear(CalendarSource.FALLBACK)
            parseForexFactory(response.body?.string().orEmpty(), now, start, end)
        }
    }

    /** Seconds left on a provider back-off, or null when it may be called again. */
    private fun remainingBlock(source: CalendarSource, now: Instant): Long? {
        val deadline = backoff.blockedUntil(source) ?: return null
        val remaining = Duration.between(now, deadline).seconds
        if (remaining > 0) return remaining
        backoff.clear(source)
        return null
    }

    /** Reads `Retry-After`, which the provider may send as delta-seconds or as an HTTP date. */
    private fun retryAfterSeconds(response: Response, now: Instant): Long? {
        val raw = response.header("Retry-After")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        raw.toLongOrNull()?.let { return it.coerceIn(0, MAX_RATE_LIMIT_BACKOFF.seconds) }
        return runCatching { ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }
            .getOrNull()
            ?.let { Duration.between(now, it).seconds.coerceIn(0, MAX_RATE_LIMIT_BACKOFF.seconds) }
    }

    private fun Throwable.describe(): String = "${javaClass.simpleName}: $message"

    internal fun parseTradingView(json: String, now: Instant = Instant.now()): List<EconomicEvent> {
        val payload = JsonParser.parseString(json)
        if (!payload.isJsonObject) error("Unexpected TradingView calendar payload")
        val root = payload.asJsonObject
        when (val status = root.text("status")) {
            "no_data" -> return emptyList()
            "ok" -> Unit
            else -> error("TradingView calendar status: ${status ?: "missing"}")
        }
        val result = root.get("result")?.takeIf(JsonElement::isJsonArray)?.asJsonArray ?: return emptyList()
        return result.map { element ->
            if (!element.isJsonObject) return@map null
            val entry = element.asJsonObject
            val providerId = entry.text("id") ?: return@map null
            val title = entry.text("title").normalized() ?: return@map null
            val eventTime = entry.text("date")?.let { value ->
                runCatching { Instant.parse(value) }.getOrNull()
            } ?: return@map null
            val countryCode = entry.text("country").normalized() ?: "Unknown"
            val country = TRADING_VIEW_COUNTRIES[countryCode] ?: countryCode
            val actual = entry.number("actual")
            // TradingView publishes a single market expectation. Store it as the consensus
            // baseline too, otherwise the rule engine has nothing to compare the actual against
            // and every release would be classified as neutral.
            val expectation = entry.number("forecast")
            val unit = entry.text("unit").normalized()
                ?.takeIf { !it.equals("None", ignoreCase = true) }
            EconomicEvent(
                id = stableEventId("trading_view|$providerId"),
                provider = "trading_view",
                providerId = providerId,
                releaseGroupId = null,
                country = country,
                currency = entry.text("currency").normalized(),
                category = entry.text("indicator").normalized() ?: title,
                event = title,
                eventTime = eventTime.toString(),
                importance = when (entry.integer("importance")) {
                    null -> 1
                    1 -> 3
                    0 -> 2
                    else -> 1
                },
                actual = actual,
                previous = entry.number("previous"),
                consensus = expectation,
                forecast = expectation,
                unit = unit,
                status = eventStatus(actual, eventTime, now),
            )
        }.filterNotNull().distinctBy(EconomicEvent::id).sortedBy(EconomicEvent::eventTime)
    }

    internal fun parseForexFactory(
        json: String,
        now: Instant = Instant.now(),
        start: LocalDate = LocalDate.now(zone),
        end: LocalDate = start,
    ): List<EconomicEvent> {
        val payload = JsonParser.parseString(json)
        if (!payload.isJsonArray) error("Unexpected Forex Factory calendar payload")
        val rangeStart = start.atStartOfDay(zone).toInstant()
        val rangeEnd = end.plusDays(1).atStartOfDay(zone).toInstant()
        return payload.asJsonArray.map { element ->
            if (!element.isJsonObject) return@map null
            val entry = element.asJsonObject
            val title = entry.text("title").normalized() ?: return@map null
            val dateText = entry.text("date") ?: return@map null
            val eventTime = runCatching { OffsetDateTime.parse(dateText).toInstant() }.getOrNull()
                ?: return@map null
            if (eventTime < rangeStart || eventTime >= rangeEnd) return@map null
            val currency = entry.text("country").normalized()?.uppercase(Locale.ROOT) ?: "Unknown"
            val actual = parseEventNumber(entry.text("actual"))
            // The weekly feed also exposes one expectation value; use it as the consensus baseline.
            val expectation = parseEventNumber(entry.text("forecast"))
            val unit = listOf(entry.text("actual"), entry.text("forecast"), entry.text("previous"))
                .firstNotNullOfOrNull(::detectUnit)
            EconomicEvent(
                id = stableEventId("forex_factory|$dateText|$currency|${title.lowercase(Locale.ROOT)}"),
                provider = "forex_factory",
                providerId = "$currency|$dateText|$title",
                releaseGroupId = null,
                country = CURRENCIES_BY_FEED[currency] ?: currency,
                currency = currency.takeUnless { it == "Unknown" },
                category = title,
                event = title,
                eventTime = eventTime.toString(),
                importance = when (entry.text("impact")?.trim()?.lowercase(Locale.ROOT)) {
                    "high" -> 3
                    "medium" -> 2
                    else -> 1
                },
                actual = actual,
                previous = parseEventNumber(entry.text("previous")),
                consensus = expectation,
                forecast = expectation,
                unit = unit,
                status = eventStatus(actual, eventTime, now),
            )
        }.filterNotNull().distinctBy(EconomicEvent::id).sortedBy(EconomicEvent::eventTime)
    }

    private fun eventStatus(actual: String?, eventTime: Instant, now: Instant): String =
        releaseStatus(actual, eventTime, now)

    private fun String?.normalized(): String? = this?.trim()?.takeUnless(String::isEmpty)

    private fun JsonObject.text(key: String): String? =
        get(key)?.takeIf(JsonElement::isJsonPrimitive)?.asString

    private fun JsonObject.number(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.asJsonPrimitive?.asBigDecimal
            ?.let(BigDecimal::stripTrailingZeros)?.toPlainString()

    private fun JsonObject.integer(key: String): Int? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt

    companion object {
        private const val TRADING_VIEW_URL = "https://economic-calendar.tradingview.com/events"
        private const val FOREX_FACTORY_URL = "https://nfs.faireconomy.media/ff_calendar_thisweek.json"

        /**
         * The primary source can black-hole (an unreachable DNS64 address) rather than refuse.
         * Bounding it keeps the first screen responsive; the fallback then gets its turn quickly.
         */
        private val PRIMARY_CONNECT_TIMEOUT: Duration = Duration.ofSeconds(6)
        private val PRIMARY_CALL_TIMEOUT: Duration = Duration.ofSeconds(12)
        // A socket/TLS failure on restricted networks is rarely transient within a few minutes.
        // Retry periodically, but do not charge every five-minute data refresh the same timeout.
        private val PRIMARY_FAILURE_BACKOFF: Duration = Duration.ofMinutes(15)

        /** Avoids synthesized/unroutable AAAA records while preserving all IPv4 candidates. */
        private val IPV4_FIRST_DNS = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                Dns.SYSTEM.lookup(hostname).sortedBy { address -> if (address is Inet4Address) 0 else 1 }
        }

        private const val HTTP_TOO_MANY_REQUESTS = 429

        /** Used when the provider rate-limits us without saying for how long. */
        private val DEFAULT_RATE_LIMIT_BACKOFF: Duration = Duration.ofMinutes(5)

        /** Ceiling for a hostile or wrong `Retry-After`, so the calendar still recovers. */
        private val MAX_RATE_LIMIT_BACKOFF: Duration = Duration.ofHours(1)

        private val COUNTRY_CODES by lazy {
            TRADING_VIEW_COUNTRIES.entries.associate { (code, name) -> name to code }
        }

        /** Maps supported display names ("United States") onto TradingView codes ("US"). */
        fun codesFor(countries: Collection<String>): List<String> =
            countries.mapNotNull { COUNTRY_CODES[it.trim()] }.distinct()

        private val TRADING_VIEW_COUNTRIES = mapOf(
            "US" to "United States",
            "EU" to "Euro Area",
            "CN" to "China",
            "JP" to "Japan",
            "GB" to "United Kingdom",
            "DE" to "Germany",
            "FR" to "France",
            "IT" to "Italy",
            "ES" to "Spain",
            "NL" to "Netherlands",
            "PT" to "Portugal",
            "GR" to "Greece",
            "IE" to "Ireland",
            "AT" to "Austria",
            "BE" to "Belgium",
            "FI" to "Finland",
            "LU" to "Luxembourg",
            "MT" to "Malta",
            "CY" to "Cyprus",
            "SK" to "Slovakia",
            "SI" to "Slovenia",
            "EE" to "Estonia",
            "LV" to "Latvia",
            "LT" to "Lithuania",
            "AU" to "Australia",
            "CA" to "Canada",
            "CH" to "Switzerland",
            "NZ" to "New Zealand",
            "KR" to "South Korea",
            "IN" to "India",
            "SG" to "Singapore",
            "HK" to "Hong Kong",
            "TW" to "Taiwan",
            "BR" to "Brazil",
            "MX" to "Mexico",
            "ZA" to "South Africa",
            "TR" to "Turkey",
            "RU" to "Russia",
            "SE" to "Sweden",
            "NO" to "Norway",
            "DK" to "Denmark",
            "PL" to "Poland",
            "CZ" to "Czechia",
            "HU" to "Hungary",
            "RO" to "Romania",
            "IL" to "Israel",
            "SA" to "Saudi Arabia",
            "ID" to "Indonesia",
            "MY" to "Malaysia",
            "TH" to "Thailand",
            "PH" to "Philippines",
            "VN" to "Vietnam",
            "AR" to "Argentina",
            "CL" to "Chile",
            "CO" to "Colombia",
            "PE" to "Peru",
            "EG" to "Egypt",
            "NG" to "Nigeria",
            "UA" to "Ukraine",
        )

        private val CURRENCIES_BY_FEED = mapOf(
            "USD" to "United States",
            "EUR" to "Euro Area",
            "CNY" to "China",
            "JPY" to "Japan",
            "GBP" to "United Kingdom",
            "AUD" to "Australia",
            "CAD" to "Canada",
            "CHF" to "Switzerland",
            "NZD" to "New Zealand",
        )
    }
}

/** Stable 63-bit non-negative digest so Room upserts overwrite the same event on refresh. */
internal fun stableEventId(value: String): Long {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    var id = 0L
    repeat(8) { index -> id = (id shl 8) or (digest[index].toLong() and 0xff) }
    return id and Long.MAX_VALUE
}

/** Normalizes display values such as "0.2%", "768B" or "1,234" into plain decimal strings. */
internal fun parseEventNumber(raw: String?): String? {
    var value = raw?.trim().orEmpty()
    if (value.isEmpty() || value in setOf("-", "--", "N/A")) return null
    value = value.replace("%", "").replace(",", "")
        .replace("$", "").replace("€", "").replace("£", "")
        .replace('−', '-').trim().trimStart('<', '>')
    val suffix = value.lastOrNull()?.uppercaseChar()
    val multiplier = when (suffix) {
        'K' -> BigDecimal("1000")
        'M' -> BigDecimal("1000000")
        'B' -> BigDecimal("1000000000")
        'T' -> BigDecimal("1000000000000")
        else -> BigDecimal.ONE
    }
    if (multiplier != BigDecimal.ONE) value = value.dropLast(1).trim()
    return value.toBigDecimalOrNull()?.multiply(multiplier)?.stripTrailingZeros()?.toPlainString()
}

private fun detectUnit(raw: String?): String? {
    val value = raw?.trim()?.uppercase(Locale.ROOT).orEmpty()
    return when {
        '%' in value -> "%"
        '$' in value -> "$"
        '€' in value -> "EUR"
        '£' in value -> "GBP"
        // K/M/B/T specify magnitude only. Oil barrels and job counts are not currencies.
        value.lastOrNull() in setOf('K', 'M', 'B', 'T') -> "number"
        else -> null
    }
}
