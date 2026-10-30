package com.macroresearch.data

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParser

/** Actual outbound JSON snapshots, redacted before local persistence; separate from operational trace. */
enum class AiRequestPhase { EXPECTATION, COMPARISON, SINGLE_PASS, FOLLOW_UP }

data class AiRequestRecord(
    val id: Int,
    val phase: AiRequestPhase,
    val startedAt: String,
    val requestJson: String?,
    val status: AiStepStatus = AiStepStatus.RUNNING,
    val httpStatus: Int? = null,
    val durationMs: Long? = null,
)

class AiRequestLog(
    initialRecords: List<AiRequestRecord> = emptyList(),
    private val observer: (List<AiRequestRecord>) -> Unit = {},
) {
    private val records = initialRecords.take(MAX_REQUESTS).toMutableList()
    private val starts = mutableMapOf<Int, Long>()
    private var storedChars = records.sumOf { it.requestJson?.length ?: 0 }

    @Synchronized
    fun start(phase: AiRequestPhase, serializedBody: String, apiKey: String): Int {
        if (records.size >= MAX_REQUESTS) return -1
        // Never retain keys or headers. Serialize first so later tool-message mutations cannot
        // alter an earlier round. Oversized content is explicitly omitted, never silently cut.
        val safe = if (serializedBody.length <= MAX_REQUEST_CHARS)
            runCatching { sanitize(serializedBody, apiKey) }.getOrNull() else null
        val retained = safe?.takeIf { storedChars + it.length <= MAX_TOTAL_CHARS }
        storedChars += retained?.length ?: 0
        val id = records.size
        records += AiRequestRecord(id, phase, java.time.Instant.now().toString(), retained)
        starts[id] = System.nanoTime()
        observer(records.toList())
        return id
    }

    @Synchronized
    fun finish(id: Int, status: AiStepStatus, httpStatus: Int? = null) {
        if (id !in records.indices || records[id].status != AiStepStatus.RUNNING) return
        records[id] = records[id].copy(status = status, httpStatus = httpStatus,
            durationMs = starts.remove(id)?.let { (System.nanoTime() - it) / 1_000_000 })
        observer(records.toList())
    }

    private fun sanitize(body: String, apiKey: String): String {
        val root = JsonParser.parseString(body)
        fun redactText(value: String): String {
            var result = if (apiKey.isNotEmpty()) value.replace(apiKey, "[REDACTED]") else value
            result = result.replace(Regex("(?i)Bearer\\s+[^\\s\"\\\\]+"), "Bearer [REDACTED]")
                .replace(Regex("\\bsk-[A-Za-z0-9_-]{8,}"), "[REDACTED]")
            return result
        }
        fun scrub(value: JsonElement, depth: Int = 0): JsonElement {
            check(depth <= 48) { "Request nesting exceeds inspection limit" }
            return when {
            value.isJsonObject -> com.google.gson.JsonObject().apply {
                value.asJsonObject.entrySet().forEach { (key, item) ->
                    val normalized = key.lowercase().replace(Regex("[^a-z]"), "")
                    when (normalized) {
                        "reasoning", "reasoningcontent", "thinking" -> Unit
                        "authorization", "apikey", "token", "accesstoken", "password", "secret" -> addProperty(key, "[REDACTED]")
                        else -> add(redactText(key), scrub(item, depth + 1))
                    }
                }
            }
            value.isJsonArray -> com.google.gson.JsonArray().apply { value.asJsonArray.forEach { add(scrub(it, depth + 1)) } }
            value.isJsonPrimitive && value.asJsonPrimitive.isString -> {
                val text = value.asString
                // message.content and function.arguments can themselves contain serialized JSON.
                val nested = if (text.trimStart().startsWith("{") || text.trimStart().startsWith("["))
                    runCatching { JsonParser.parseString(text) }.getOrNull() else null
                com.google.gson.JsonPrimitive(if (nested != null && (nested.isJsonObject || nested.isJsonArray))
                    compact.toJson(scrub(nested, depth + 1)) else redactText(text))
            }
            else -> value.deepCopy()
            }
        }
        return pretty.toJson(scrub(root))
    }

    companion object {
        // The bounded analysis tool loop cannot reach 64 requests. This also bounds follow-ups.
        const val MAX_REQUESTS = 64
        const val MAX_REQUEST_CHARS = 256 * 1024
        const val MAX_TOTAL_CHARS = 2 * 1024 * 1024
        private val compact = GsonBuilder().serializeNulls().disableHtmlEscaping().create()
        private val pretty = GsonBuilder().serializeNulls().disableHtmlEscaping().setPrettyPrinting().create()
    }
}
