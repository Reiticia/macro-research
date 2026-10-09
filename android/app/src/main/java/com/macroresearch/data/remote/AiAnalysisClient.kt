package com.macroresearch.data.remote

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.macroresearch.data.AnalysisMethod
import com.macroresearch.data.TranslationSettings
import com.macroresearch.data.model.EconomicEvent
import com.macroresearch.data.model.ExpectedReaction
import com.macroresearch.data.model.MarketReaction
import com.macroresearch.data.model.TransmissionStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Everything the model needs to brief a single released event. */
data class AiAnalysisInput(
    val event: EconomicEvent,
    val macroSignal: String,
    val rawSurprise: String?,
    val expectedReactions: List<ExpectedReaction>,
    val observedReactions: List<MarketReaction>,
    val languageTag: String,
    val newsSearchRequested: Boolean = false,
    val newsSearchStatus: String? = null,
    val newsArticles: List<NewsArticle> = emptyList(),
)

/** Parsed model output before it is persisted. */
data class AiAnalysisDraft(
    val chain: List<TransmissionStep>,
    val dataAnalysis: String,
    val marketOutlook: String,
    val risks: String?,
)

data class AiConversationMessage(val role: String, val content: String, val createdAt: Long = System.currentTimeMillis())

/**
 * Generates a post-release briefing on the user's own OpenAI-compatible endpoint:
 * a transmission chain from the data surprise to asset prices, a read of the released
 * numbers and a market outlook. The caller persists the result.
 */
class AiAnalysisClient(private val client: OkHttpClient, private val gson: Gson) {

    /**
     * Runs the briefing in the shape the user configured. Observed moves are only ever revealed
     * in a pass that is explicitly allowed to see them, so the ex-ante reasoning stays honest.
     */
    suspend fun analyze(
        input: AiAnalysisInput,
        settings: TranslationSettings,
        apiKey: String,
        method: AnalysisMethod = AnalysisMethod.DEFAULT,
    ): AiAnalysisDraft = withContext(Dispatchers.IO) {
        val hasMoves = input.observedReactions.any { it.hasAnyChange() }
        if (method == AnalysisMethod.NUMBERS_ONLY) {
            return@withContext request(
                input, settings, apiKey, AnalysisStage.EXPECTATION,
                includeMoves = false, includeNews = true,
            )
        }
        if (method == AnalysisMethod.SINGLE_PASS || !hasMoves) {
            return@withContext request(
                input, settings, apiKey, AnalysisStage.SINGLE_PASS,
                includeMoves = hasMoves, includeNews = true,
            )
        }
        // Keep the first ex-ante pass free of both price observations and post-release news.
        val expectation = request(
            input, settings, apiKey, AnalysisStage.EXPECTATION,
            includeMoves = false, includeNews = false,
        )
        val comparison = request(
            input, settings, apiKey, AnalysisStage.COMPARISON,
            includeMoves = true, expectation = expectation, includeNews = true,
        )
        comparison.copy(
            // The numbers read and the chain were produced before the moves were revealed, so the
            // second pass may only validate them; it never rewrites the ex-ante reasoning.
            dataAnalysis = expectation.dataAnalysis.ifBlank { comparison.dataAnalysis },
            chain = comparison.chain.ifEmpty { expectation.chain },
            risks = comparison.risks ?: expectation.risks,
        )
    }

    private suspend fun request(
        input: AiAnalysisInput,
        settings: TranslationSettings,
        apiKey: String,
        stage: AnalysisStage,
        includeMoves: Boolean,
        expectation: AiAnalysisDraft? = null,
        includeNews: Boolean,
    ): AiAnalysisDraft {
        val endpoint = chatEndpoint(settings.baseUrl)
        fun requestPayload(jsonMode: Boolean, compactRetry: Boolean = false): Map<String, Any?> =
            payload(input, settings.model, jsonMode, stage, includeMoves, expectation, includeNews)
                .let { body ->
                    if (!compactRetry) body else body.withCompactRetryInstruction()
                }
        fun send(jsonMode: Boolean, compactRetry: Boolean = false): String =
            execute(endpoint, apiKey, requestPayload(jsonMode, compactRetry))
        fun sendWithCompatibilityFallback(compactRetry: Boolean): String = try {
            send(true, compactRetry)
        } catch (rejected: ApiException) {
            // Not every OpenAI-compatible gateway accepts response_format.
            if (rejected.code in UNSUPPORTED_JSON_MODE_CODES) send(false, compactRetry) else throw rejected
        }

        val responseText = sendWithCompatibilityFallback(compactRetry = false)
        try {
            return parseResponse(responseText)
        } catch (parseError: IllegalStateException) {
            // Some gateways/models stop early despite a generous output budget. Retry once with a
            // strict compact-output instruction; never retry transport/provider errors.
            val retryText = sendWithCompatibilityFallback(compactRetry = true)
            try {
                return parseResponse(retryText)
            } catch (retryError: IllegalStateException) {
                responseObserver?.invoke(retryText)
                retryError.addSuppressed(parseError)
                throw retryError
            }
        }
    }

    suspend fun converse(
        input: AiAnalysisInput,
        settings: TranslationSettings,
        apiKey: String,
        history: List<AiConversationMessage>,
        question: String,
        currentBriefing: String? = null,
    ): AiConversationMessage = withContext(Dispatchers.IO) {
        val endpoint = chatEndpoint(settings.baseUrl)
        val system = buildString {
            append("You are a careful macro-market analyst. Answer follow-up questions about this event using only the supplied event, analysis, observed market reactions and conversation. Distinguish measured facts from hypotheses; do not invent missing values, market moves, news or causality. If actual is absent, do not claim a data surprise. State when evidence is unavailable. This is descriptive analysis, not investment advice. ")
            append("Write in ").append(outputLanguage(input.languageTag)).append('.')
        }
        val context = mapOf(
            "event" to input.event,
            "macroSignal" to input.macroSignal,
            "rawSurprise" to input.rawSurprise,
            "expectedReactions" to input.expectedReactions,
            "observedReactions" to input.observedReactions,
            "currentBriefing" to currentBriefing,
        )
        val messages = buildList {
            add(mapOf("role" to "system", "content" to system))
            add(mapOf("role" to "user", "content" to "Event context: ${gson.toJson(context)}"))
            history.takeLast(20).forEach { add(mapOf("role" to it.role, "content" to it.content)) }
            add(mapOf("role" to "user", "content" to question))
        }
        val body = execute(endpoint, apiKey, mapOf("model" to settings.model, "messages" to messages, "stream" to false, "max_tokens" to MAX_TOKENS))
        val root = com.google.gson.JsonParser.parseString(body).asJsonObject
        val answer = root.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
            ?.getAsJsonObject("message")?.get("content")?.asString?.trim()
            ?.takeIf(String::isNotEmpty) ?: error("AI returned an empty response")
        AiConversationMessage("assistant", answer)
    }

    fun encodeConversation(messages: List<AiConversationMessage>): String = gson.toJson(messages)

    fun decodeConversation(json: String): List<AiConversationMessage> = runCatching {
        gson.fromJson(json, Array<AiConversationMessage>::class.java)?.toList().orEmpty()
    }.getOrDefault(emptyList())

    fun encodeChain(chain: List<TransmissionStep>): String = gson.toJson(chain)

    fun decodeChain(json: String): List<TransmissionStep> = runCatching {
        gson.fromJson(json, Array<TransmissionStep>::class.java)?.toList().orEmpty()
    }.getOrDefault(emptyList())

    private fun chatEndpoint(baseUrl: String): String =
        if (baseUrl.endsWith("/chat/completions")) baseUrl else "$baseUrl/chat/completions"

    private fun execute(endpoint: String, apiKey: String, payload: Map<String, Any?>): String {
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .post(gson.toJson(payload).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ApiException(response.code, "AI analysis API returned HTTP ${response.code}")
            }
            return body
        }
    }

    private fun payload(
        input: AiAnalysisInput,
        model: String,
        jsonMode: Boolean,
        stage: AnalysisStage,
        includeMoves: Boolean,
        expectation: AiAnalysisDraft?,
        includeNews: Boolean,
    ): Map<String, Any?> {
        // The briefing is read next to cards that render device-local time, so hand the model
        // the local clock as well as the precise instant.
        val zone = ZoneId.systemDefault()
        val releasedLocal = runCatching {
            Instant.parse(input.event.eventTime).atZone(zone).format(LOCAL_TIME_FORMAT)
        }.getOrNull()
        val event = mapOf(
            "name" to input.event.event,
            "country" to input.event.country,
            "currency" to input.event.currency,
            "importance" to input.event.importance,
            "releasedAt" to input.event.eventTime,
            "releasedAtLocal" to releasedLocal,
            "timeZone" to zone.id,
            "status" to input.event.status,
            "unit" to input.event.unit,
            "actual" to input.event.actual,
            "consensus" to input.event.consensus,
            "forecast" to input.event.forecast,
            "previous" to input.event.previous,
        )
        val expected = input.expectedReactions.map {
            mapOf("symbol" to it.symbol, "ruleDirection" to it.direction)
        }
        val observed = input.observedReactions.map {
            mapOf(
                "symbol" to it.symbol,
                "baseline" to it.baselinePrice,
                "unit" to it.reactionUnit,
                "change1m" to it.change1m,
                "change5m" to it.change5m,
                "change15m" to it.change15m,
                "change30m" to it.change30m,
                "change60m" to it.change60m,
            )
        }
        val user = buildMap<String, Any?> {
            put("event", event)
            put("ruleSignal", input.macroSignal)
            put("rawSurprise", input.rawSurprise)
            put("expectedReactions", expected)
            if (includeMoves) put("observedReactions", observed)
            if (includeNews && input.newsSearchRequested) {
                put("newsSearchStatus", input.newsSearchStatus ?: "search_unavailable")
                put("relatedNews", input.newsArticles.map { article ->
                    mapOf(
                        "title" to article.title,
                        "source" to article.source,
                        "publishedAt" to article.publishedAt,
                        "url" to article.url,
                        "summary" to article.summary,
                    )
                })
            }
            expectation?.let { put("exAnteExpectation", exAnte(it)) }
        }
        return linkedMapOf<String, Any?>(
            "model" to model,
            "messages" to listOf(
                mapOf("role" to "system", "content" to systemPrompt(input.languageTag, stage)),
                mapOf("role" to "user", "content" to gson.toJson(user)),
            ),
            "stream" to false,
            // Reasoning-capable models spend tokens on their thinking before the answer, so a
            // small budget truncates the JSON mid-object.
            "max_tokens" to MAX_TOKENS,
        ).apply {
            if (jsonMode) put("response_format", mapOf("type" to "json_object"))
        }
    }

    private fun Map<String, Any?>.withCompactRetryInstruction(): Map<String, Any?> {
        val updatedMessages = (this["messages"] as List<Map<String, String>>).toMutableList()
        val system = updatedMessages.firstOrNull()?.get("content").orEmpty()
        updatedMessages[0] = updatedMessages.first().toMutableMap().apply {
            put(
                "content",
                system + " RETRY: The prior answer was not valid JSON. Return a very concise complete JSON object now; keep chain to at most 4 links, each text field to 1-2 short sentences, and do not add prose or markdown.",
            )
        }
        return toMutableMap().apply { put("messages", updatedMessages) }
    }

    private fun exAnte(draft: AiAnalysisDraft): Map<String, Any?> = mapOf(
        "chain" to draft.chain.map {
            mapOf("from" to it.from, "to" to it.to, "direction" to it.direction, "rationale" to it.rationale)
        },
        "dataAnalysis" to draft.dataAnalysis,
        "marketOutlook" to draft.marketOutlook,
        "risks" to draft.risks,
    )

    private fun systemPrompt(languageTag: String, stage: AnalysisStage): String = buildString {
        append("You are a macro market analyst writing a post-event briefing for one economic event. Use only payload data; never invent figures. If actual is absent, do not claim a data surprise. Analyze the event context and any observed market reaction; distinguish evidence from hypotheses, and state when effects are unobserved. Without usable market observations, provide conditional context rather than claiming no impact. If news is supplied, treat it only as dated context, cite only supplied sources, and do not imply it proves causality. ")
        when (stage) {
            AnalysisStage.EXPECTATION -> append(
                "No market reaction data is provided in this pass: build a conditional chain from event context, available values and rule signal only; do not guess what prices did. If actual is missing, discuss the event's potential channels without claiming a measured surprise. Say what would confirm or invalidate each link. ",
            )
            AnalysisStage.SINGLE_PASS -> append(
                "Explain potential transmission from the event context or measured surprise to assets. Use only supplied observed moves; otherwise say market reaction is unobserved, not absent. Describe causal links as hypotheses unless the evidence supports them. Treat related news only as context. ",
            )
            AnalysisStage.COMPARISON -> append(
                "You already produced an ex-ante expectation without seeing any prices; it is supplied as " +
                    "exAnteExpectation. The payload now also carries the observed post-release moves. Reuse " +
                    "the ex-ante chain in its original order, add a verdict to every link (confirmed, " +
                    "contradicted or unobserved) based only on those moves, and you may add a link only when " +
                    "the data requires it. Never rewrite the ex-ante reasoning to look prescient. ",
            )
        }
        append("Reply with JSON only, no reasoning, no plan, no markdown fence, no text before or after: {")
        append("\"chain\":[{\"from\":\"...\",\"to\":\"...\",\"direction\":\"up|down|flat\",\"rationale\":\"...\"")
        if (stage == AnalysisStage.COMPARISON) append(",\"verdict\":\"confirmed|contradicted|unobserved\"")
        append("}],")
        append("\"dataAnalysis\":\"...\",\"marketOutlook\":\"...\",\"risks\":\"...\"}. ")
        append("chain is ordered from the relevant event context or measured surprise to the final asset reaction using short node names ")
        append("(for example \"CPI surprise\", \"real yields\", \"US dollar\", \"gold\"). ")
        append("dataAnalysis: 2-4 sentences comparing actual with consensus, forecast and previous when those values exist; explicitly say when a value is unavailable and do not claim a surprise without actual and a comparison baseline. Include revisions or caveats only when supported by the payload. ")
        when (stage) {
            AnalysisStage.COMPARISON -> append(
                "marketOutlook: 3-5 sentences in one paragraph covering, in order, the ex-ante expectation, " +
                    "what the observed moves actually showed (naming the numbers), and the revised view with " +
                    "its invalidation condition. ",
            )
            else -> append(
                "marketOutlook: 2-4 sentences on how rates, the dollar and risk assets are likely to trade " +
                    "next and what would invalidate the view. ",
            )
        }
        append("risks: 1-2 sentences on the main risk to this chain. State uncertainty explicitly. ")
        append("Quote release times in the reader's local time zone given by timeZone. ")
        append("Write in ").append(outputLanguage(languageTag)).append(". ")
        append("This is descriptive analysis, not investment advice.")
    }

    private fun outputLanguage(languageTag: String): String {
        val tag = languageTag.lowercase(Locale.ROOT)
        return when {
            tag.startsWith("zh-hant") || tag.startsWith("zh-tw") || tag.startsWith("zh-hk") ->
                "Traditional Chinese"
            tag.startsWith("zh") -> "Simplified Chinese"
            else -> "English"
        }
    }

    internal fun parseResponse(responseText: String): AiAnalysisDraft {
        val payload = briefingCandidates(responseText)
            .mapNotNull(::usableBriefing)
            .lastOrNull()
            ?: run {
                responseObserver?.invoke(responseText)
                error("AI did not return a usable analysis JSON; the reply may have been truncated or missing the JSON object")
            }
        val chain = parseChain(payload.firstArray("chain", "transmissionChain", "transmission_chain", "steps", "links"))
        val dataAnalysis = payload
            .firstString("dataAnalysis", "data_analysis", "dataRead", "data_read", "analysis")
            .orEmpty().trim()
        val marketOutlook = payload
            .firstString("marketOutlook", "market_outlook", "marketView", "market_view", "outlook")
            .orEmpty().trim()
        val risks = payload.firstString("risks", "risk", "caveats")?.trim()?.takeIf(String::isNotEmpty)
        check(dataAnalysis.isNotEmpty() || marketOutlook.isNotEmpty()) {
            "AI returned no usable analysis"
        }
        return AiAnalysisDraft(chain, dataAnalysis, marketOutlook, risks)
    }

    /**
     * Every object in the response that could be the briefing: the payload itself, or the
     * contents of an OpenAI envelope. Reasoning prose often embeds the example schema from the
     * prompt, so candidates are validated instead of taking the first braces found.
     */
    private fun briefingCandidates(responseText: String): List<JsonObject> =
        ModelJson.values(responseText).flatMap { root ->
            val fromContents = ModelJson.contents(root).flatMap(ModelJson::objects)
            val direct = when {
                root.isJsonObject -> listOf(root.asJsonObject)
                root.isJsonArray -> root.asJsonArray.filter { it.isJsonObject }.map { it.asJsonObject }
                else -> emptyList()
            }
            direct + fromContents
        }.distinctBy { it.toString() }

    /** Rejects echo of the prompt schema, which carries placeholder values instead of a result. */
    private fun usableBriefing(payload: JsonObject): JsonObject? {
        val text = listOf("dataAnalysis", "data_analysis", "dataRead", "data_read", "analysis",
            "marketOutlook", "market_outlook", "outlook")
            .firstNotNullOfOrNull { payload.firstString(it) }
            ?.trim()
            .orEmpty()
        if (text.isBlank()) return null
        // Rejects the schema echo, whose fields hold placeholders instead of a result.
        if (PLACEHOLDER_TOKENS.any { it in text }) return null
        return payload
    }

    private fun parseChain(rows: List<JsonElement>?): List<TransmissionStep> = rows.orEmpty().mapNotNull { element ->
        if (!element.isJsonObject) return@mapNotNull null
        val row = element.asJsonObject
        val from = row.firstString("from", "fromNode", "from_node", "source", "cause")?.trim().orEmpty()
        val to = row.firstString("to", "toNode", "to_node", "target", "effect")?.trim().orEmpty()
        if (from.isEmpty() || to.isEmpty()) return@mapNotNull null
        TransmissionStep(
            from = from,
            to = to,
            direction = normalizeDirection(row.firstString("direction", "dir", "sign")),
            rationale = row.firstString("rationale", "reason", "why", "explanation").orEmpty().trim(),
            verdict = normalizeVerdict(row.firstString("verdict", "status", "result", "validation")),
        )
    }

    private fun normalizeVerdict(raw: String?): String? = when (raw?.trim()?.lowercase(Locale.ROOT)) {
        null, "" -> null
        "confirmed", "confirm", "validated", "holds", "held", "in line", "✓" -> "confirmed"
        "contradicted", "contradiction", "invalidated", "refuted", "failed", "✗" -> "contradicted"
        "unobserved", "unknown", "unclear", "n/a", "na", "not observed", "no data" -> "unobserved"
        else -> null
    }

    private fun normalizeDirection(raw: String?): String {
        val value = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return when {
            value.isEmpty() -> "flat"
            value.startsWith("up") || value in setOf("higher", "rise", "rising", "increase", "bullish", "↑", "+") -> "up"
            value.startsWith("down") || value in setOf("lower", "fall", "falling", "decrease", "bearish", "↓", "-") -> "down"
            else -> "flat"
        }
    }

    private class ApiException(val code: Int, message: String) : IOException(message)

    /** Which prompt/payload shape a request uses. */
    private enum class AnalysisStage { EXPECTATION, COMPARISON, SINGLE_PASS }

    private fun MarketReaction.hasAnyChange(): Boolean =
        listOfNotNull(change1m, change5m, change15m, change30m, change60m).isNotEmpty()

    companion object {
        /** Debug-only hook; the app logs raw model output when a reply cannot be parsed. */
        internal var responseObserver: ((String) -> Unit)? = null

        private const val MAX_TOKENS = 8192
        private val UNSUPPORTED_JSON_MODE_CODES = setOf(400, 404, 422)
        /** Fragments of the schema echo; a real briefing never contains them. */
        private val PLACEHOLDER_TOKENS = listOf("up|down|flat", "...", "short node names", "2-4 sentences")

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val LOCAL_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        private fun JsonElement.asStringOrNull(): String? =
            takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

        private fun JsonObject.firstString(vararg names: String): String? =
            names.firstNotNullOfOrNull { name ->
                runCatching { get(name)?.takeUnless { it.isJsonNull }?.asString }.getOrNull()
            }

        private fun JsonObject.firstArray(vararg names: String): List<JsonElement>? =
            names.firstNotNullOfOrNull { name ->
                runCatching { get(name)?.takeUnless { it.isJsonNull }?.asJsonArray?.toList() }.getOrNull()
            }
    }
}
