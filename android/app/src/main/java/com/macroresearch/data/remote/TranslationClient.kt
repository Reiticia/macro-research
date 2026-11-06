package com.macroresearch.data.remote

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.macroresearch.data.TranslationSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class TranslationClient(
    private val client: OkHttpClient,
    private val gson: Gson,
) {
    /** Names whose translation kept failing review; skipped until the provider changes. */
    private val rejected = ConcurrentHashMap.newKeySet<String>()
    private var rejectedProvider: String? = null
    suspend fun models(baseUrl: String, apiKey: String): List<String> =
        withContext(Dispatchers.IO) {
            val endpoint = when {
                baseUrl.endsWith("/models") -> baseUrl
                baseUrl.endsWith("/chat/completions") ->
                    baseUrl.removeSuffix("/chat/completions") + "/models"
                else -> "$baseUrl/models"
            }
            var lastError: Exception? = null
            repeat(MODEL_LIST_ATTEMPTS) { attempt ->
                try {
                    val request = Request.Builder()
                        .url(endpoint)
                        .header("Authorization", "Bearer $apiKey")
                        .header("Accept", "application/json")
                        .get()
                        .build()
                    val call = client.newCall(request)
                    call.timeout().timeout(MODEL_LIST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    call.execute().use { response ->
                        val body = response.body?.string().orEmpty()
                        if (!response.isSuccessful) {
                            throw IOException("Models API returned HTTP ${response.code}")
                        }
                        return@withContext parseModelsResponse(body)
                    }
                } catch (error: Exception) {
                    lastError = error
                }
                if (attempt + 1 < MODEL_LIST_ATTEMPTS) {
                    Thread.sleep(RETRY_DELAY_MS * (attempt + 1))
                }
            }
            throw lastError ?: IOException("Unable to fetch models")
        }

    suspend fun translate(
        eventNames: List<String>,
        settings: TranslationSettings,
        apiKey: String,
        revise: Boolean = false,
    ): Map<String, Pair<String, String>> = withContext(Dispatchers.IO) {
        if (eventNames.isEmpty()) return@withContext emptyMap()
        val endpoint = chatEndpoint(settings.baseUrl)

        val translated = linkedMapOf<String, Pair<String, String>>()
        var pending = eventNames
        var lastError: Exception? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                val responseText = execute(
                    endpoint = endpoint,
                    apiKey = apiKey,
                    payload = payload(pending, settings.model, useResponseFormat = attempt == 0, revise = revise),
                )
                translated += parseChatResponse(responseText, pending)
                pending = pending.filterNot(translated::containsKey)
                if (pending.isEmpty()) return@withContext translated
                lastError = IOException("Translation API omitted ${pending.size} event names")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                lastError = error
            }
            if (attempt + 1 < MAX_ATTEMPTS) delay(RETRY_DELAY_MS * (attempt + 1))
        }
        if (translated.isNotEmpty()) return@withContext translated
        throw lastError ?: IOException("Translation request failed")
    }

    suspend fun translateVerified(
        eventNames: List<String>,
        settings: TranslationSettings,
        apiKey: String,
        rounds: Int = VERIFICATION_ROUNDS,
        retryRejected: Boolean = false,
    ): Map<String, Pair<String, String>> = withContext(Dispatchers.IO) {
        if (eventNames.isEmpty()) return@withContext emptyMap()
        resetRejectedIfProviderChanged(settings)
        val verified = linkedMapOf<String, Pair<String, String>>()
        var pending = eventNames.distinct().filterNot { !retryRejected && it in rejected }
        repeat(maxOf(1, rounds)) { round ->
            if (pending.isEmpty()) return@withContext verified
            val attempt = translate(pending, settings, apiKey, revise = round > 0)
            if (attempt.isEmpty()) return@withContext verified
            val accepted = try {
                verify(attempt, settings, apiKey)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // A reviewer that cannot answer (endpoint error or unusable reply) must not
                // block usable translations forever; only explicit rejections trigger a retry.
                verified += attempt
                return@withContext verified
            }
            accepted.forEach { name -> attempt[name]?.let { verified[name] = it } }
            pending = attempt.keys.filterNot(accepted::contains)
        }
        // Names whose review kept failing are not retried until the provider changes,
        // otherwise every page load would pay for the same rejected attempts again.
        rejected += pending
        verified
    }

    private fun resetRejectedIfProviderChanged(settings: TranslationSettings) {
        val signature = "${settings.baseUrl}|${settings.model}"
        if (rejectedProvider != signature) {
            rejectedProvider = signature
            rejected.clear()
        }
    }

    /** Returns the subset of [translations] the model confirms as accurate. */
    suspend fun verify(
        translations: Map<String, Pair<String, String>>,
        settings: TranslationSettings,
        apiKey: String,
    ): Set<String> = withContext(Dispatchers.IO) {
        if (translations.isEmpty()) return@withContext emptySet()
        val responseText = execute(
            endpoint = chatEndpoint(settings.baseUrl),
            apiKey = apiKey,
            payload = reviewPayload(translations, settings.model),
        )
        parseVerdictResponse(responseText, translations)
    }

    private fun chatEndpoint(baseUrl: String): String =
        if (baseUrl.endsWith("/chat/completions")) baseUrl else "$baseUrl/chat/completions"

    private suspend fun execute(
        endpoint: String, apiKey: String, payload: Map<String, Any>,
        responseLimit: Long? = null, timeoutSeconds: Long? = null,
    ): String {
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .post(gson.toJson(payload).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            timeoutSeconds?.let { call.timeout().timeout(it, TimeUnit.SECONDS) }
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(error))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            val body = if (responseLimit == null) response.body?.string().orEmpty() else {
                                val source = response.body?.source() ?: throw IOException("Empty translation response")
                                source.request(responseLimit + 1)
                                if (source.buffer.size > responseLimit) throw IOException("Translation response too large")
                                source.readUtf8()
                            }
                            if (!response.isSuccessful) {
                                throw IOException("Translation API returned HTTP ${response.code}")
                            }
                            continuation.resumeWith(Result.success(body))
                        } catch (error: Exception) {
                            if (continuation.isActive) continuation.resumeWith(Result.failure(error))
                        }
                    }
                }
            })
        }
    }

    private fun payload(
        eventNames: List<String>,
        model: String,
        useResponseFormat: Boolean,
        revise: Boolean = false,
    ): Map<String, Any> {
        val input = eventNames.mapIndexed { index, name -> mapOf("id" to index, "name" to name) }
        val instruction = buildString {
            append("Translate each macroeconomic event name into Simplified Chinese and Traditional Chinese. ")
            append("Preserve numbers, periods and abbreviations. ")
            if (revise) {
                append("A previous attempt was rejected by quality review: fix the accuracy, use standard ")
                append("macroeconomic terminology, and keep both Chinese variants equivalent. ")
            }
            append("Reply with JSON only: {\"translations\":[{\"id\":0,\"zhCn\":\"...\",\"zhTw\":\"...\"}]}. ")
            append("Include every input id exactly once.")
        }
        val values = linkedMapOf<String, Any>(
            "model" to model,
            "messages" to listOf(
                mapOf("role" to "system", "content" to instruction),
                mapOf("role" to "user", "content" to gson.toJson(mapOf("events" to input))),
            ),
            "stream" to false,
            "max_tokens" to 2048,
        )
        if (useResponseFormat) values["response_format"] = mapOf("type" to "json_object")
        return values
    }

    private fun reviewPayload(
        translations: Map<String, Pair<String, String>>,
        model: String,
    ): Map<String, Any> {
        val items = translations.entries.mapIndexed { index, (name, value) ->
            mapOf("id" to index, "en" to name, "zhCn" to value.first, "zhTw" to value.second)
        }
        return linkedMapOf(
            "model" to model,
            "messages" to listOf(
                mapOf("role" to "system", "content" to REVIEW_INSTRUCTION),
                mapOf("role" to "user", "content" to gson.toJson(mapOf("items" to items))),
            ),
            "stream" to false,
            "max_tokens" to 2048,
        )
    }

    internal fun parseModelsResponse(responseText: String): List<String> {
        val root = JsonParser.parseString(responseText)
        val rows = when {
            root.isJsonArray -> root.asJsonArray
            root.isJsonObject -> root.asJsonObject.getAsJsonArray("data")
                ?: root.asJsonObject.getAsJsonArray("models")
                ?: error("Models API returned no model list")
            else -> error("Models API returned an invalid result")
        }
        val models = rows.mapNotNull { element ->
            when {
                element.isJsonPrimitive && element.asJsonPrimitive.isString -> element.asString
                element.isJsonObject -> element.asJsonObject.firstString("id", "name", "model")
                else -> null
            }?.trim()?.takeIf(String::isNotEmpty)
        }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
        check(models.isNotEmpty()) { "Models API returned an empty list" }
        return models
    }

    internal fun parseChatResponse(
        responseText: String,
        eventNames: List<String>,
    ): Map<String, Pair<String, String>> {
        val root = JsonParser.parseString(responseText)
        val content = if (root.isJsonObject && root.asJsonObject.has("translations")) {
            root
        } else {
            extractModelContent(root)
        }
        return parseTranslations(content, eventNames)
    }

    /** Parses the reviewer verdicts, returning only the names explicitly confirmed as accurate. */
    internal fun parseVerdictResponse(
        responseText: String,
        translations: Map<String, Pair<String, String>>,
    ): Set<String> {
        val names = translations.keys.toList()
        val root = JsonParser.parseString(responseText)
        val direct = root.isJsonObject && root.asJsonObject.let {
            it.has("verdicts") || it.has("verified") || it.has("approved")
        }
        val content = if (direct) root else extractModelContent(root)
        val verified = linkedSetOf<String>()

        fun acceptRow(row: JsonObject, index: Int) {
            val id = row.intOrNull("id") ?: index
            val ok = row.booleanOrNull("ok", "valid", "verified", "pass", "passed", "correct", "accepted")
            if (ok == true && id in names.indices) verified += names[id]
        }

        fun acceptPlain(element: JsonElement) {
            when {
                element.isJsonPrimitive && element.asJsonPrimitive.isNumber -> {
                    val id = element.asInt
                    if (id in names.indices) verified += names[id]
                }
                element.isJsonPrimitive && element.asJsonPrimitive.isString -> {
                    val name = element.asString.trim()
                    if (name in translations) verified += name
                }
            }
        }

        when {
            content.isJsonObject -> {
                val obj = content.asJsonObject
                val rows = obj.getAsJsonArray("verdicts")
                    ?: obj.getAsJsonArray("items")
                    ?: obj.getAsJsonArray("results")
                if (rows != null) {
                    rows.forEachIndexed { index, element ->
                        if (element.isJsonObject) acceptRow(element.asJsonObject, index)
                    }
                } else {
                    val listed = obj.getAsJsonArray("verified") ?: obj.getAsJsonArray("approved")
                        ?: error("Review API returned no verdicts")
                    listed.forEach(::acceptPlain)
                }
            }
            content.isJsonArray -> content.asJsonArray.forEachIndexed { index, element ->
                when {
                    element.isJsonObject -> acceptRow(element.asJsonObject, index)
                    else -> acceptPlain(element)
                }
            }
            else -> error("Review API returned an invalid result")
        }
        return verified
    }

    private fun extractModelContent(
        root: JsonElement,
        missing: String = "Translation API returned no content",
    ): JsonElement {
        val choice = root.asJsonObject.getAsJsonArray("choices")
            ?.firstOrNull()?.asJsonObject
            ?: error("Translation API returned no choices")
        val message = choice.getAsJsonObject("message")
        val value = message?.get("content")
            ?.takeUnless {
                it.isJsonNull || (it.isJsonPrimitive && it.asStringOrNull().isNullOrBlank())
            }
            ?: message?.get("reasoning_content")
            ?: choice.get("text")
            ?: error(missing)
        return if (value.isJsonObject || value.isJsonArray) value else parseModelJson(value.asString)
    }

    private fun parseModelJson(raw: String): JsonElement {
        val candidates = ModelJson.values(raw)
        // Prefer an explicit translation payload, then a list, then whatever parsed.
        candidates.firstOrNull { it.isJsonObject && (it.asJsonObject.has("translations") || it.asJsonObject.has("items")) }
            ?.let { return it }
        candidates.firstOrNull { it.isJsonArray }?.let { return it }
        return candidates.firstOrNull() ?: error("Translation API returned invalid JSON")
    }

    private fun parseTranslations(
        content: JsonElement,
        eventNames: List<String>,
    ): Map<String, Pair<String, String>> {
        val rows: JsonArray = when {
            content.isJsonArray -> content.asJsonArray
            content.isJsonObject -> content.asJsonObject.getAsJsonArray("translations")
                ?: content.asJsonObject.getAsJsonArray("items")
                ?: error("Translation API returned no translations")
            else -> error("Translation API returned an invalid result")
        }
        val translated = linkedMapOf<String, Pair<String, String>>()
        rows.forEachIndexed { index, element ->
            if (!element.isJsonObject) return@forEachIndexed
            val row = element.asJsonObject
            val id = row.intOrNull("id") ?: index
            if (id !in eventNames.indices) return@forEachIndexed
            val zhCn = row.firstString("zhCn", "zh_cn", "zhHans", "simplified", "simplifiedChinese")
            val zhTw = row.firstString("zhTw", "zh_tw", "zhHant", "traditional", "traditionalChinese")
            if (!zhCn.isNullOrBlank() && !zhTw.isNullOrBlank()) {
                translated[eventNames[id]] = zhCn.trim() to zhTw.trim()
            }
        }
        check(translated.isNotEmpty()) { "Translation API returned no usable translations" }
        return translated
    }

    companion object {
        private const val MAX_ATTEMPTS = 3
        private const val VERIFICATION_ROUNDS = 3
        private const val MODEL_LIST_ATTEMPTS = 2
        private const val MODEL_LIST_TIMEOUT_SECONDS = 25L
        private const val RETRY_DELAY_MS = 1_500L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private const val REVIEW_INSTRUCTION =
            "Review each macroeconomic event name and its Simplified Chinese (zhCn) and " +
                "Traditional Chinese (zhTw) translations. Set ok to true only when both " +
                "translations are accurate, complete, idiomatic for financial news, and " +
                "equivalent to each other. Reply with JSON only: " +
                "{\"verdicts\":[{\"id\":0,\"ok\":true}]}. Include every item id exactly once."

        private fun JsonElement.asStringOrNull(): String? =
            takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

        private fun JsonObject.intOrNull(name: String): Int? =
            runCatching { get(name)?.takeUnless { it.isJsonNull }?.asInt }.getOrNull()

        private fun JsonObject.booleanOrNull(vararg names: String): Boolean? =
            names.firstNotNullOfOrNull { name ->
                runCatching { get(name)?.takeUnless { it.isJsonNull }?.asBoolean }.getOrNull()
            }

        private fun JsonObject.firstString(vararg names: String): String? = names.firstNotNullOfOrNull { name ->
            runCatching { get(name)?.takeUnless { it.isJsonNull }?.asString }.getOrNull()
        }
    }
}
