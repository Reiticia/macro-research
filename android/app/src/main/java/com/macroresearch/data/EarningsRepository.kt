package com.macroresearch.data

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.macroresearch.data.model.EARNINGS_ZONE
import com.macroresearch.data.model.EarningsCalendar
import com.macroresearch.data.model.earningsTtl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import com.macroresearch.data.model.EarningsQuote
import com.macroresearch.data.model.EarningsEvent
import com.macroresearch.data.model.EarningsSort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

interface EarningsStore {
    suspend fun read(source: String, date: String): String?
    suspend fun write(source: String, date: String, payload: String)
    suspend fun prune(before: String)
}

/** Separate cache namespace and request lifecycle; never switches to direct on backend failure. */
class EarningsRepository(
    val settings: StateFlow<BackendSettings>,
    private val store: EarningsStore,
    private val direct: suspend (LocalDate, EarningsCalendar?) -> EarningsCalendar,
    private val backend: suspend (LocalDate, Boolean) -> EarningsCalendar,
    private val gson: Gson = Gson(),
    private val clock: () -> Instant = Instant::now,
    val logos: CompanyLogoLoader? = null,
    private val backendQuote: (suspend (String) -> EarningsQuote)? = null,
    private val favoritesStore: EarningsFavoritesStore? = null,
    val sort: StateFlow<EarningsSort> =
        MutableStateFlow(EarningsSort()),
) {
    private val mutex = Mutex()
    private val attempts = mutableMapOf<String, Instant>()

    private fun sourceKey(settings: BackendSettings): String =
        if (settings.mode == DataSourceMode.DIRECT) "direct" else "backend:" +
            MessageDigest.getInstance("SHA-256").digest(settings.baseUrl.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val favorites = settings.flatMapLatest { context ->
        favoritesStore?.observe(sourceKey(context)) ?: flowOf(emptyList())
    }

    suspend fun setFavorite(date: LocalDate, event: EarningsEvent, enabled: Boolean) {
        require(com.macroresearch.data.remote.companyLogoSymbol(event.symbol) == event.symbol)
        val context = settings.value
        favoritesStore?.set(sourceKey(context), FavoriteEarnings(date, event), enabled)
    }

    suspend fun quote(symbol: String): EarningsQuote {
        val context = settings.value
        check(context.mode == DataSourceMode.BACKEND) { "Current stock price requires backend mode" }
        val fetch = backendQuote ?: throw UnsupportedOperationException("Current stock price is unavailable")
        val result = fetch(symbol)
        if (settings.value != context) throw CancellationException("Earnings source changed")
        require(result.symbol == symbol) { "Invalid stock quote identity" }
        return result
    }

    suspend fun cached(date: LocalDate): EarningsCalendar? {
        val context = settings.value
        val result = read(sourceKey(context), date)
        if (settings.value != context) return null
        return result?.copy(stale = result.stale || Duration.between(Instant.parse(result.updatedAt), clock()) >= earningsTtl(date, clock()))
    }

    private suspend fun read(key: String, date: LocalDate): EarningsCalendar? =
        store.read(key, date.toString())?.let { raw -> runCatching { decodeEarningsCalendar(raw, date, gson) }.getOrNull() }

    suspend fun load(date: LocalDate, force: Boolean = false): EarningsCalendar = mutex.withLock {
        val now = clock()
        val today = now.atZone(EARNINGS_ZONE).toLocalDate()
        require(!date.isBefore(today.minusDays(120)) && !date.isAfter(today.plusDays(60))) { "Earnings date outside supported range" }
        val context = settings.value
        val source = sourceKey(context)
        val cached = read(source, date)
        if (settings.value != context) throw CancellationException("Earnings source changed")
        if (!force && cached != null && Duration.between(Instant.parse(cached.updatedAt), now) < earningsTtl(date, now)) return@withLock cached
        val key = "$source:$date"
        if (attempts[key]?.let { Duration.between(it, now).seconds < 60 } == true) {
            return@withLock cached?.copy(stale = true, warnings = (cached.warnings + "refresh_cooldown").distinct())
                ?: throw java.io.IOException("Earnings refresh cooldown")
        }
        attempts.entries.removeAll { Duration.between(it.value, now).toDays() >= 1 }
        attempts[key] = now
        val result = try {
            if (context.mode == DataSourceMode.DIRECT) direct(date, cached) else backend(date, force)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            cached?.copy(stale = true, warnings = (cached.warnings + "refresh_failed").distinct()) ?: throw error
        }
        if (settings.value != context) throw CancellationException("Earnings source changed")
        require(result.date == date.toString() && result.timezone == "America/New_York") { "Invalid earnings response" }
        // An outage must not advance the last successful timestamp or overwrite a good snapshot.
        if (!result.stale) {
            store.write(source, date.toString(), gson.toJson(result))
            store.prune(today.minusDays(120).toString())
        }
        result
    }
}

internal fun decodeEarningsCalendar(raw: String, date: LocalDate, gson: Gson = Gson()): EarningsCalendar {
    val root = JsonParser.parseString(raw).asJsonObject
    require(root.get("date")?.asString == date.toString())
    require(root.get("timezone")?.asString == "America/New_York")
    Instant.parse(root.get("updatedAt").asString)
    require(root.get("warnings")?.isJsonArray == true && root.get("events")?.isJsonArray == true)
    root.getAsJsonArray("events").forEach {
        val event = it.asJsonObject
        require(!event.get("symbol").asString.isBlank() && !event.get("name").asString.isBlank())
        require(event.get("source").asString in setOf("nasdaq", "tradingview"))
        require(event.get("session").asString in setOf("pre_market", "after_hours", "unknown"))
    }
    return gson.fromJson(root, EarningsCalendar::class.java)
}
