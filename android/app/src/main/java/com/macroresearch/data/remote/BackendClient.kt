package com.macroresearch.data.remote

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.macroresearch.data.AnalysisMethod
import com.macroresearch.data.model.AiAnalysis
import com.macroresearch.data.model.AnalysisReport
import com.macroresearch.data.model.EconomicEvent
import com.macroresearch.data.model.EventDetailResponse
import com.macroresearch.data.model.MarketQuotesResponse
import com.macroresearch.data.model.MarketResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant
import java.time.ZoneId

/** The backend rejected the address, the protocol version, or the token. */
class BackendUnauthorizedException(message: String) : IOException(message)

/** The backend could not be reached, or answered with a server error. */
class BackendUnavailableException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Any other structured backend error, carrying the server's machine-readable code. */
class BackendException(
    val statusCode: Int,
    val code: String?,
    message: String,
) : IOException(message)

/** Capability handshake used by the "test connection" action. */
data class BackendMeta(
    val name: String?,
    val version: String?,
    val apiVersion: Int,
    val aiEnabled: Boolean,
    val capabilities: List<String>,
)

data class BackendSourceHealth(
    val key: String,
    val status: String,
    val consecutiveFailures: Int,
    val lastError: String?,
)

data class BackendStatus(val sources: List<BackendSourceHealth>)

/** Outcome of a translation-correction submission. */
data class CorrectionRecord(
    val id: Long,
    val eventName: String,
    val status: String,
    val zhCn: String,
    val zhTw: String,
    val decidedAt: String?,
)

/**
 * REST client for the self-hosted backend.
 *
 * Address and token are read through lambdas on every call so changing them in Settings takes
 * effect without rebuilding the client, and so a stale binary cannot silently keep using an old
 * host.
 */
class BackendClient(
    private val client: OkHttpClient,
    private val gson: Gson,
    private val baseUrl: () -> String,
    private val token: () -> String?,
) {
    suspend fun meta(): BackendMeta = withContext(Dispatchers.IO) {
        val root = getJson("/api/v1/meta", authenticated = false).asJsonObject
        BackendMeta(
            name = root.stringOrNull("name"),
            version = root.stringOrNull("version"),
            apiVersion = root.get("apiVersion")?.asInt ?: 0,
            aiEnabled = root.get("aiEnabled")?.asBoolean ?: false,
            capabilities = root.getAsJsonArray("capabilities")
                ?.mapNotNull { it.takeUnless { value -> value.isJsonNull }?.asString }
                .orEmpty(),
        )
    }

    suspend fun status(): BackendStatus = withContext(Dispatchers.IO) {
        val root = getJson("/api/v1/status").asJsonObject
        BackendStatus(
            sources = root.getAsJsonArray("sources")?.mapNotNull { element ->
                element.takeIf { it.isJsonObject }?.asJsonObject?.let { row ->
                    BackendSourceHealth(
                        key = row.stringOrNull("key").orEmpty(),
                        status = row.stringOrNull("status").orEmpty(),
                        consecutiveFailures = row.get("consecutiveFailures")?.asInt ?: 0,
                        lastError = row.stringOrNull("lastError"),
                    )
                }
            }.orEmpty(),
        )
    }

    suspend fun upcoming(days: Int = 7): List<EconomicEvent> = withContext(Dispatchers.IO) {
        getJson("/api/v1/events/upcoming") { it.addQueryParameter("days", days.toString()) }
            .let(::events)
    }

    suspend fun calendar(
        from: Instant,
        to: Instant,
        minimumImportance: Int? = null,
    ): List<EconomicEvent> = withContext(Dispatchers.IO) {
        getJson("/api/v1/calendar") { url ->
            url.addQueryParameter("from", from.toString())
            url.addQueryParameter("to", to.toString())
            minimumImportance?.let { url.addQueryParameter("minimum_importance", it.toString()) }
        }.let(::events)
    }

    suspend fun history(
        countries: Collection<String>,
        category: String?,
        limit: Int,
        offset: Int,
    ): List<EconomicEvent> = withContext(Dispatchers.IO) {
        getJson("/api/v1/events/history") { url ->
            if (countries.isNotEmpty()) url.addQueryParameter("country", countries.joinToString(","))
            category?.takeIf { it.isNotBlank() }?.let { url.addQueryParameter("category", it) }
            url.addQueryParameter("limit", limit.toString())
            url.addQueryParameter("offset", offset.toString())
        }.let(::events)
    }

    suspend fun event(id: Long): EventDetailResponse = withContext(Dispatchers.IO) {
        gson.fromJson(getJson("/api/v1/events/$id"), EventDetailResponse::class.java)
    }

    suspend fun refresh(id: Long): EventDetailResponse = withContext(Dispatchers.IO) {
        gson.fromJson(
            postJson("/api/v1/events/$id/refresh", JsonObject()),
            EventDetailResponse::class.java,
        )
    }

    suspend fun analysis(id: Long): AnalysisReport = withContext(Dispatchers.IO) {
        gson.fromJson(getJson("/api/v1/events/$id/analysis"), AnalysisReport::class.java)
    }

    suspend fun market(id: Long): MarketResponse = withContext(Dispatchers.IO) {
        gson.fromJson(getJson("/api/v1/events/$id/market"), MarketResponse::class.java)
    }

    suspend fun marketQuotes(symbols: List<String>): MarketQuotesResponse = withContext(Dispatchers.IO) {
        getJson("/api/v1/market/quotes") { url ->
            if (symbols.isNotEmpty()) url.addQueryParameter("symbols", symbols.joinToString(","))
        }.let { gson.fromJson(it, MarketQuotesResponse::class.java) }
    }

    /** Cached server-side briefing, or null when nothing has been generated yet. */
    suspend fun aiAnalysis(
        id: Long,
        languageTag: String,
        method: AnalysisMethod,
        timezone: String = ZoneId.systemDefault().id,
    ): AiAnalysis? = withContext(Dispatchers.IO) {
        val root = try {
            getJson("/api/v1/events/$id/ai-analysis") { url ->
                url.addQueryParameter("language", languageTag)
                url.addQueryParameter("method", method.wireValue.toString())
                url.addQueryParameter("timezone", timezone)
            }
        } catch (missing: BackendException) {
            if (missing.statusCode == 404) return@withContext null
            throw missing
        }
        gson.fromJson(root, AiAnalysis::class.java)
    }

    suspend fun generateAiAnalysis(
        id: Long,
        languageTag: String,
        method: AnalysisMethod,
        regenerate: Boolean,
        timezone: String = ZoneId.systemDefault().id,
    ): AiAnalysis = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            addProperty("language", languageTag)
            addProperty("method", method.wireValue)
            addProperty("timezone", timezone)
            addProperty("regenerate", regenerate)
        }
        gson.fromJson(postJson("/api/v1/events/$id/ai-analysis", body), AiAnalysis::class.java)
    }

    suspend fun submitCorrection(
        eventName: String,
        zhCn: String,
        zhTw: String,
    ): CorrectionRecord = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            addProperty("eventName", eventName)
            addProperty("zhCn", zhCn)
            addProperty("zhTw", zhTw)
        }
        gson.fromJson(postJson("/api/v1/translations/corrections", body), CorrectionRecord::class.java)
    }

    suspend fun latestCorrection(eventName: String): CorrectionRecord? = withContext(Dispatchers.IO) {
        val root = getJson("/api/v1/translations/corrections") { url ->
            url.addQueryParameter("eventName", eventName)
        }
        if (root.isJsonNull) null else gson.fromJson(root, CorrectionRecord::class.java)
    }
    /** `https://host` / `http://host` into the WebSocket scheme of the same endpoint. */
    fun webSocketUrl(): String? {
        val base = baseUrl().trim().trimEnd('/')
        if (base.isEmpty()) return null
        return when {
            base.startsWith("https://") -> "wss://${base.removePrefix("https://")}/api/v1/ws"
            base.startsWith("http://") -> "ws://${base.removePrefix("http://")}/api/v1/ws"
            else -> null
        }
    }

    private fun events(root: com.google.gson.JsonElement): List<EconomicEvent> =
        gson.fromJson(root, object : TypeToken<List<EconomicEvent>>() {}.type)

    private fun getJson(
        path: String,
        authenticated: Boolean = true,
        configure: (HttpUrl.Builder) -> Unit = {},
    ): com.google.gson.JsonElement {
        val request = requestBuilder(path, authenticated, configure).get().build()
        return execute(request)
    }

    private fun postJson(path: String, body: JsonObject): com.google.gson.JsonElement {
        val request = requestBuilder(path, authenticated = true) {}.post(
            body.toString().toRequestBody(JSON_MEDIA_TYPE),
        ).build()
        return execute(request)
    }

    private fun requestBuilder(
        path: String,
        authenticated: Boolean,
        configure: (HttpUrl.Builder) -> Unit,
    ): Request.Builder {
        val base = baseUrl().trim().trimEnd('/')
        check(base.isNotEmpty()) { "Configure the backend address in Settings first" }
        val url = "$base$path".toHttpUrl().newBuilder().apply(configure).build()
        val builder = Request.Builder()
            .url(url)
            .header("accept", "application/json")
        if (authenticated) {
            val accessToken = token()
            check(!accessToken.isNullOrBlank()) { "Configure the backend access token in Settings first" }
            builder.header("authorization", "Bearer $accessToken")
        }
        return builder
    }

    private fun execute(request: Request): com.google.gson.JsonElement {
        val response = try {
            client.newCall(request).execute()
        } catch (error: IOException) {
            throw BackendUnavailableException(error.message ?: "Backend is unreachable", error)
        }
        response.use {
            val text = it.body?.string().orEmpty()
            if (it.isSuccessful) {
                if (text.isBlank()) return JsonObject()
                return runCatching { JsonParser.parseString(text) }
                    .getOrElse { error -> throw BackendUnavailableException("Backend returned malformed JSON", error) }
            }
            val error = runCatching {
                JsonParser.parseString(text).asJsonObject.getAsJsonObject("error")
            }.getOrNull()
            val code = error?.stringOrNull("code")
            val message = error?.stringOrNull("message") ?: "Backend returned HTTP ${it.code}"
            when (it.code) {
                401, 403 -> throw BackendUnauthorizedException(message)
                400, 404, 409, 429 -> throw BackendException(it.code, code, message)
                else -> throw BackendUnavailableException(message)
            }
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

private fun JsonObject.stringOrNull(name: String): String? =
    get(name)?.takeUnless { it.isJsonNull }?.asString
