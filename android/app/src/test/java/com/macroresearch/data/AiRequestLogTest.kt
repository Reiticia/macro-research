package com.macroresearch.data

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class AiRequestLogTest {
    @Test fun capturesImmutableBodiesAndStatusesWithoutHeadersOrCredentials() {
        var records = emptyList<AiRequestRecord>()
        val log = AiRequestLog { records = it }
        val key = "fixture-current-model-key"
        val nested = Gson().toJson(mapOf("api_key" to "fixture-other-secret", "value" to "3"))
        val body = Gson().toJson(mapOf(
            "model" to "fixture-model", "messages" to listOf(
                mapOf("role" to "system", "content" to "Use provided data only."),
                mapOf("role" to "user", "content" to nested),
                mapOf("role" to "user", "content" to "pasted $key Bearer fixture-token and sk-fixturesecret123456"),
            ), "authorization" to "fixture-auth", "reasoning_content" to "private-reasoning",
        ))
        val first = log.start(AiRequestPhase.EXPECTATION, body, key)
        val initial = records
        assertEquals(AiStepStatus.RUNNING, initial.single().status)
        log.finish(first, AiStepStatus.SUCCEEDED, 200)
        assertEquals(AiStepStatus.RUNNING, initial.single().status)
        assertEquals(AiStepStatus.SUCCEEDED, records.single().status)
        assertEquals(200, records.single().httpStatus)
        assertNotNull(records.single().durationMs)
        val safe = records.single().requestJson!!
        for (secret in listOf(key, "fixture-token", "sk-fixturesecret123456", "fixture-auth", "fixture-other-secret", "private-reasoning")) assertFalse(secret, safe.contains(secret))
        assertTrue(safe.contains("Use provided data only."))
        assertTrue(safe.contains("[REDACTED]"))
        val user = JsonParser.parseString(safe).asJsonObject["messages"].asJsonArray[1].asJsonObject["content"].asString
        assertEquals("3", JsonParser.parseString(user).asJsonObject["value"].asString)
        log.finish(first, AiStepStatus.FAILED, 500)
        assertEquals(AiStepStatus.SUCCEEDED, records.single().status)
    }

    @Test fun oversizedOrUnsafeBodiesHaveExplicitlyMissingContentAndBoundedRetention() {
        var records = emptyList<AiRequestRecord>()
        val log = AiRequestLog { records = it }
        log.start(AiRequestPhase.SINGLE_PASS, "x".repeat(AiRequestLog.MAX_REQUEST_CHARS + 1), "")
        assertNull(records.single().requestJson)
        log.start(AiRequestPhase.SINGLE_PASS, "invalid json", "")
        assertNull(records.last().requestJson)
        val body = Gson().toJson(mapOf("messages" to listOf(mapOf("content" to "x".repeat(200_000)))))
        repeat(100) { log.start(AiRequestPhase.FOLLOW_UP, body, "") }
        assertEquals(AiRequestLog.MAX_REQUESTS, records.size)
        assertTrue(records.sumOf { it.requestJson?.length ?: 0 } <= AiRequestLog.MAX_TOTAL_CHARS)
        assertTrue(records.any { it.requestJson == null })
    }

    @Test fun separateSessionsDoNotReconstructOrSharePrompts() {
        var first = emptyList<AiRequestRecord>()
        var second = emptyList<AiRequestRecord>()
        AiRequestLog { first = it }.start(AiRequestPhase.FOLLOW_UP, "{\"messages\":[]}", "")
        AiRequestLog { second = it }
        assertEquals(1, first.size)
        assertTrue(second.isEmpty())
    }
}
