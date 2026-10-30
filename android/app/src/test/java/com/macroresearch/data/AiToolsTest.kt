package com.macroresearch.data

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.macroresearch.data.model.EconomicEvent
import com.macroresearch.data.model.MarketReaction
import com.macroresearch.data.remote.AiAnalysisClient
import com.macroresearch.data.remote.AiAnalysisInput
import com.macroresearch.data.remote.NewsArticle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class AiToolsTest {
    private val gson = Gson()
    private val final = """{"chain":[],"dataAnalysis":"Available release evidence.","marketOutlook":"Conditional outlook; no causal certainty.","risks":"Missing observations."}"""
    private fun answer() = gson.toJson(mapOf("choices" to listOf(mapOf("message" to mapOf("content" to final, "reasoning_content" to "private-model-reasoning")))))
    private fun calls(vararg names: String, arguments: String = "{}") = gson.toJson(mapOf("choices" to listOf(mapOf("message" to mapOf(
        "reasoning_content" to "private-model-reasoning", "content" to null,
        "tool_calls" to names.mapIndexed { i, name -> mapOf("id" to "call_$i", "type" to "function", "function" to mapOf("name" to name, "arguments" to arguments)) },
    )))))
    private fun input(trace: AiExecutionTrace? = null, enabled: Boolean = true) = AiAnalysisInput(
        event = EconomicEvent(1, "trading_view", "tv", null, "United States", "USD", "inflation", "CPI MoM", eventTime = "2026-10-28T12:30:00Z", importance = 3, actual = null, previous = "2", consensus = "3", forecast = "3", unit = "%", status = "data_unavailable"),
        macroSignal = "neutral", rawSurprise = null, expectedReactions = emptyList(),
        observedReactions = listOf(MarketReaction(1, "gold", baselinePrice = 100.0, reactionUnit = "percent", change1m = 123.456, change5m = null, change15m = null, change30m = null, change60m = null)),
        languageTag = "en", newsSearchRequested = true, newsSearchStatus = "articles_found",
        newsArticles = listOf(NewsArticle("Fed outlook", "https://example.com/news", "Example", "2026-10-28T13:00:00Z", "NEWS_MARKER")),
        trace = trace, toolsEnabled = enabled,
    )
    private fun client(handler: (JsonObject) -> Pair<Int, String>): AiAnalysisClient = AiAnalysisClient(
        OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = okio.Buffer()
            chain.request().body!!.writeTo(buffer)
            val (status, response) = handler(JsonParser.parseString(buffer.readUtf8()).asJsonObject)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("test")
                .body(response.toResponseBody()).build()
        }.build(), gson,
    )
    private val settings = TranslationSettings(true, "https://model.example.com/v1", "fixture-model")

    @Test fun realFunctionCallsUseToolMessagesAndTraceDoesNotExposeReasoningOrSecrets() = runBlocking {
        val requests = mutableListOf<JsonObject>()
        var steps = emptyList<AiExecutionStep>()
        val trace = AiExecutionTrace { steps = it }
        val client = client { body -> requests += body; 200 to if (requests.size == 1) calls("read_current_event", "read_rule_assessment") else answer() }
        val result = client.analyze(input(trace), settings, "fixture-key", AnalysisMethod.NUMBERS_ONLY)
        assertEquals("Available release evidence.", result.dataAnalysis)
        assertEquals(2, requests.size)
        assertTrue(requests.first().has("tools"))
        val messages = requests.last()["messages"].asJsonArray
        assertEquals(2, messages.count { it.asJsonObject["role"].asString == "tool" })
        assertTrue(messages.any { it.asJsonObject["role"].asString == "tool" && it.asJsonObject["content"].asString.contains("\"actual\":null") })
        assertFalse(requests.last().toString().contains("private-model-reasoning"))
        assertTrue(steps.any { it.operation == AiOperation.READ_EVENT && it.status == AiStepStatus.SUCCEEDED })
        assertTrue(steps.any { it.operation == AiOperation.VALIDATE })
        assertFalse(steps.toString().contains("fixture-key"))
        assertFalse(steps.toString().contains("private-model-reasoning"))
    }

    @Test fun numbersOnlyAndFirstStageCannotReadMarketOrNewsEvenWithHostileToolRequests() = runBlocking {
        val requests = mutableListOf<JsonObject>()
        val client = client { body ->
            requests += body
            200 to when (requests.size) {
                1 -> calls("read_market_windows", "read_related_news")
                2 -> answer()
                else -> answer()
            }
        }
        client.analyze(input(), settings, "fixture", AnalysisMethod.EX_ANTE_THEN_COMPARE)
        assertEquals(3, requests.size)
        for (request in requests.take(2)) {
            assertFalse(request.toString().contains("123.456"))
            assertFalse(request.toString().contains("NEWS_MARKER"))
            assertFalse(request["tools"].toString().contains("read_market_windows"))
            assertFalse(request["tools"].toString().contains("read_related_news"))
        }
        assertTrue(requests.last()["tools"].toString().contains("read_market_windows"))
        assertTrue(requests.last()["tools"].toString().contains("read_related_news"))
        assertTrue(requests.last()["messages"].toString().contains("exAnteExpectation"))
        val numbers = mutableListOf<JsonObject>()
        client { body -> numbers += body; 200 to answer() }.analyze(input(), settings, "fixture", AnalysisMethod.NUMBERS_ONLY)
        assertFalse(numbers.single()["tools"].toString().contains("read_market_windows"))
    }

    @Test fun disabledNewsAndArbitraryArgumentsAreNeverDispatched() = runBlocking {
        val requests = mutableListOf<JsonObject>()
        var steps = emptyList<AiExecutionStep>()
        val client = client { body -> requests += body; 200 to if (requests.size == 1) calls("read_related_news", "fetch_url", "read_current_event", arguments = "{\"url\":\"https://evil.invalid\"}") else answer() }
        client.analyze(input(AiExecutionTrace { steps = it }).copy(newsSearchRequested = false), settings, "fixture", AnalysisMethod.SINGLE_PASS)
        assertFalse(requests.first()["tools"].toString().contains("read_related_news"))
        val results = requests.last()["messages"].asJsonArray.filter { it.asJsonObject["role"].asString == "tool" }
        assertEquals(3, results.size)
        assertTrue(results.all { it.asJsonObject["content"].asString.contains("error") })
        assertFalse(requests.last().toString().contains("NEWS_MARKER"))
        assertEquals(3, steps.count { it.detail == AiStepDetail.INVALID_TOOL })
    }

    @Test fun toolRejectionFallsBackButAuthAndRateErrorsDoNotRetry() = runBlocking {
        var calls = 0
        var steps = emptyList<AiExecutionStep>()
        client { body -> calls++; if (calls == 1) 400 to "{}" else { assertFalse(body.has("tools")); 200 to answer() } }
            .analyze(input(AiExecutionTrace { steps = it }), settings, "fixture", AnalysisMethod.SINGLE_PASS)
        assertEquals(2, calls)
        assertTrue(steps.any { it.detail == AiStepDetail.UNSUPPORTED_TOOLS })
        for (code in listOf(401, 403, 429, 500)) {
            var count = 0
            val result = runCatching { client { count++; code to "{}" }.analyze(input(), settings, "fixture", AnalysisMethod.SINGLE_PASS) }
            assertTrue(result.isFailure)
            assertEquals(1, count)
        }
    }

    @Test fun normalModelsMayReturnFinalAnswersWithoutToolsAndOptOutPreservesOldPayload() = runBlocking {
        var body: JsonObject? = null
        client { body = it; 200 to answer() }.analyze(input(enabled = false), settings, "fixture", AnalysisMethod.SINGLE_PASS)
        assertFalse(body!!.has("tools"))
        assertTrue(body!!["messages"].toString().contains("123.456"))
        client { 200 to answer() }.analyze(input(), settings, "fixture", AnalysisMethod.SINGLE_PASS)
        Unit
    }

    @Test fun toolRoundsAreBoundedAndErrorsAfterDispatchDoNotRestartTheConversation() = runBlocking {
        var count = 0
        val failed = runCatching {
            client { count++; 200 to calls("read_current_event").replace("call_0", "call_$count") }
                .analyze(input(), settings, "fixture", AnalysisMethod.SINGLE_PASS)
        }
        assertTrue(failed.isFailure)
        assertEquals(4, count)
        count = 0
        val rejected = runCatching {
            client { count++; if (count == 1) 200 to calls("read_current_event") else 400 to "{}" }
                .analyze(input(), settings, "fixture", AnalysisMethod.SINGLE_PASS)
        }
        assertTrue(rejected.isFailure)
        assertEquals(2, count)
    }

    @Test fun inspectorCapturesEveryActualToolRoundWithoutMutatingEarlierSnapshots() = runBlocking {
        var records = emptyList<AiRequestRecord>()
        val log = AiRequestLog { records = it }
        val sent = mutableListOf<JsonObject>()
        val client = client { body ->
            sent += body.deepCopy()
            200 to if (sent.size == 1) calls("read_current_event", "read_market_windows") else answer()
        }
        client.analyze(input().copy(requestLog = log), settings, "fixture-key", AnalysisMethod.SINGLE_PASS)
        assertEquals(2, records.size)
        records.forEachIndexed { index, record ->
            assertEquals(AiRequestPhase.SINGLE_PASS, record.phase)
            assertEquals(AiStepStatus.SUCCEEDED, record.status)
            assertEquals(200, record.httpStatus)
            assertEquals(sent[index], JsonParser.parseString(record.requestJson))
        }
        val firstMessages = JsonParser.parseString(records.first().requestJson).asJsonObject["messages"].asJsonArray
        val secondMessages = JsonParser.parseString(records.last().requestJson).asJsonObject["messages"].asJsonArray
        assertEquals(2, firstMessages.size())
        assertEquals(5, secondMessages.size())
        assertTrue(secondMessages.any { it.asJsonObject["role"].asString == "tool" })
        assertFalse(records.toString().contains("private-model-reasoning"))
        assertFalse(records.toString().contains("fixture-key"))
    }

    @Test fun inspectorCapturesBothStagesCompatibilityFallbacksAndFormatRetries() = runBlocking {
        var records = emptyList<AiRequestRecord>()
        var sent = 0
        client { sent++; 200 to answer() }.analyze(input(enabled = false).copy(requestLog = AiRequestLog { records = it }), settings, "fixture", AnalysisMethod.EX_ANTE_THEN_COMPARE)
        assertEquals(listOf(AiRequestPhase.EXPECTATION, AiRequestPhase.COMPARISON), records.map { it.phase })
        assertFalse(records.first().requestJson!!.contains("123.456"))
        assertTrue(records.last().requestJson!!.contains("123.456"))
        sent = 0
        client { sent++; if (sent == 1) 400 to "{}" else 200 to answer() }
            .analyze(input().copy(requestLog = AiRequestLog { records = it }), settings, "fixture", AnalysisMethod.SINGLE_PASS)
        assertEquals(2, records.size)
        assertEquals(AiStepStatus.FAILED, records.first().status)
        assertEquals(400, records.first().httpStatus)
        assertTrue(JsonParser.parseString(records.first().requestJson).asJsonObject.has("tools"))
        assertFalse(JsonParser.parseString(records.last().requestJson).asJsonObject.has("tools"))
        sent = 0
        client { sent++; 200 to if (sent == 1) "{\"choices\":[{\"message\":{\"content\":\"not a briefing\"}}]}" else answer() }
            .analyze(input(enabled = false).copy(requestLog = AiRequestLog { records = it }), settings, "fixture", AnalysisMethod.SINGLE_PASS)
        assertEquals(2, records.size)
        assertTrue(records.last().requestJson!!.contains("RETRY:"))
    }

    @Test fun inspectorIncludesFollowUpMessagesAndRetainsFailedAttempts() = runBlocking {
        var records = emptyList<AiRequestRecord>()
        val log = AiRequestLog { records = it }
        val client = client { 200 to "{\"choices\":[{\"message\":{\"content\":\"A sourced answer.\"}}]}" }
        client.converse(input().copy(requestLog = log), settings, "fixture-key", listOf(
            com.macroresearch.data.remote.AiConversationMessage("user", "Earlier question"),
            com.macroresearch.data.remote.AiConversationMessage("assistant", "Earlier answer"),
        ), "Current question")
        assertEquals(AiRequestPhase.FOLLOW_UP, records.single().phase)
        assertTrue(records.single().requestJson!!.contains("Earlier question"))
        assertTrue(records.single().requestJson!!.contains("Current question"))
        val result = runCatching { client { 429 to "{}" }.analyze(input().copy(requestLog = log), settings, "fixture-key", AnalysisMethod.SINGLE_PASS) }
        assertTrue(result.isFailure)
        assertEquals(2, records.size)
        assertEquals(AiStepStatus.FAILED, records.last().status)
        assertEquals(429, records.last().httpStatus)
    }

    @Test fun traceIsBoundedAndCancellationDoesNotBecomeFailure() = runBlocking {
        var latest = emptyList<AiExecutionStep>()
        val trace = AiExecutionTrace { latest = it }
        repeat(100) { trace.start(AiOperation.LOAD_EVENT) }
        trace.finishRunning(AiStepStatus.CANCELLED)
        assertEquals(64, latest.size)
        assertTrue(latest.all { it.status == AiStepStatus.CANCELLED })
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            val started = CompletableDeferred<okhttp3.Call>()
            val http = OkHttpClient.Builder().eventListener(object : okhttp3.EventListener() {
                override fun callStart(call: okhttp3.Call) { started.complete(call) }
            }).build()
            var requests = emptyList<AiRequestRecord>()
            val requestLog = AiRequestLog { requests = it }
            val task = launch {
                AiAnalysisClient(http, gson).analyze(input(enabled = false).copy(requestLog = requestLog), settings.copy(baseUrl = server.url("/v1").toString()), "fixture", AnalysisMethod.SINGLE_PASS)
                fail("cancelled request must not succeed")
            }
            val call = withTimeout(5000) { started.await() }
            withTimeout(3000) { task.cancelAndJoin() }
            assertTrue(task.isCancelled)
            assertTrue(call.isCanceled())
            assertEquals(AiStepStatus.CANCELLED, requests.single().status)
            assertNotNull(requests.single().requestJson)
        }
    }
}
