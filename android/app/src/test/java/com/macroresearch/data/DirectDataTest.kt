package com.macroresearch.data

import com.macroresearch.data.model.EconomicEvent
import com.google.gson.Gson
import com.macroresearch.data.remote.AiAnalysisClient
import com.macroresearch.data.remote.EconomicCalendarClient
import com.macroresearch.data.remote.TranslationClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

class DirectDataTest {
    @Test
    fun tradingViewCalendarJsonIsParsedIntoStableEvents() {
        val json = """
            {"status":"ok","result":[
              {"id":"393891","title":"ECB Interest Rate Decision","country":"EU",
               "indicator":"Interest Rate","currency":"EUR","unit":"%",
               "importance":1,"date":"2026-09-10T12:15:00.000Z",
               "actual":2.65,"previous":2.4,"forecast":2.65},
              {"id":"393892","title":"Existing Home Sales","country":"US",
               "indicator":"Existing Home Sales","currency":"USD","unit":null,
               "importance":0,"date":"2026-09-10T14:00:00.000Z",
               "actual":3.98,"previous":4.06,"forecast":null},
              {"id":"393891","title":"ECB Interest Rate Decision","country":"EU",
               "indicator":"Interest Rate","currency":"EUR","unit":"%",
               "importance":1,"date":"2026-09-10T12:15:00.000Z",
               "actual":2.65,"previous":2.4,"forecast":2.65}
            ]}
        """.trimIndent()
        val client = EconomicCalendarClient(OkHttpClient())
        val now = Instant.parse("2026-09-10T13:00:00Z")

        val events = client.parseTradingView(json, now)

        assertEquals(2, events.size)
        val first = events.first()
        assertEquals("trading_view", first.provider)
        assertEquals("Euro Area", first.country)
        assertEquals("EUR", first.currency)
        assertEquals("Interest Rate", first.category)
        assertEquals("2.65", first.actual)
        assertEquals("2.4", first.previous)
        // The single source expectation also becomes the consensus baseline.
        assertEquals("2.65", first.forecast)
        assertEquals("2.65", first.consensus)
        assertEquals("%", first.unit)
        assertEquals(3, first.importance)
        assertEquals("released", first.status)
        // Re-parsing must produce identical IDs so Room upserts overwrite instead of duplicating.
        assertEquals(first.id, client.parseTradingView(json, now).first().id)
        // TradingView rates importance -1/0/1 (low/medium/high); missing values stay displayable.
        assertEquals(2, events.last().importance)
        assertEquals("scheduled", client.parseTradingView(
            """{"status":"ok","result":[{"id":"1","title":"Future Event","country":"JP",
                "importance":-1,"date":"2026-09-20T00:00:00.000Z"}]}""", now,
        ).single().status)
    }

    @Test
    fun forexFactoryWeeklyJsonIsParsedWithFallbackRangeFilter() {
        val json = """
            [
              {"title":"Main Refinancing Rate","country":"EUR",
               "date":"2026-09-10T08:15:00-04:00","impact":"High",
               "forecast":"2.65%","previous":"2.40%","actual":"2.65%"},
              {"title":"Foreign Currency Reserves","country":"CHF",
               "date":"2026-09-07T03:00:00-04:00","impact":"Low",
               "forecast":"","previous":"768B","actual":""},
              {"title":"Outside Requested Range","country":"USD",
               "date":"2026-10-01T08:30:00-04:00","impact":"High",
               "forecast":"","previous":"","actual":""}
            ]
        """.trimIndent()
        val client = EconomicCalendarClient(OkHttpClient())
        val now = Instant.parse("2026-09-10T13:00:00Z")

        val events = client.parseForexFactory(
            json, now,
            start = LocalDate.of(2026, 9, 6),
            end = LocalDate.of(2026, 9, 13),
        )

        assertEquals(2, events.size)
        val first = events.first { it.providerId.startsWith("EUR") }
        assertEquals("forex_factory", first.provider)
        assertEquals("Euro Area", first.country)
        assertEquals("EUR", first.currency)
        assertEquals("2.65", first.actual)
        assertEquals("2.65", first.consensus)
        assertEquals("%", first.unit)
        assertEquals(3, first.importance)
        assertEquals("released", first.status)
        val reserves = events.first { it.currency == "CHF" }
        assertEquals("768000000000", reserves.previous)
        assertEquals("number", reserves.unit)
        // Elapsed time is not evidence that a numeric release value was retrieved.
        assertEquals("data_unavailable", reserves.status)
    }

    @Test
    fun localAnalysisAppliesIndicatorDirectionWithoutServer() {
        val event = event("Unemployment Rate", "4.3", "4.1")
        val report = LocalAnalysisEngine().analyze(event, emptyList())

        assertTrue(report.macroSignal.contains("dovish"))
        assertEquals("0.2", report.rawSurprise)
        assertEquals("up", report.expectedReactions.first { it.symbol == "nasdaq100" }.direction)
        // Static rule text is emitted as a stable key and rendered through string resources,
        // so it never depends on an AI translation pass.
        assertEquals("easier_policy_baseline", report.expectedReactions.first().rationale)
        assertEquals("rule_engine_summary", report.summary)
    }

    @Test
    fun openAiCompatibleModelListsAreParsedAndSorted() {
        val client = TranslationClient(OkHttpClient(), Gson())
        val models = client.parseModelsResponse(
            """{"object":"list","data":[{"id":"z-model"},{"id":"a-model"},{"id":"a-model"}]}""",
        )

        assertEquals(listOf("a-model", "z-model"), models)
        assertEquals(
            listOf("model-one", "model-two"),
            client.parseModelsResponse("""{"models":["model-two",{"name":"model-one"}]}"""),
        )
    }

    @Test
    fun cancellingTranslationStopsTheInFlightHttpCall() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        try {
            val client = TranslationClient(
                OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build(),
                Gson(),
            )
            val settings = TranslationSettings(
                configured = true,
                baseUrl = server.url("v1").toString().removeSuffix("/"),
                model = "test-model",
            )
            val request = launch(Dispatchers.IO) {
                client.translate(listOf("Existing Home Sales"), settings, "test-key")
            }

            assertTrue(server.takeRequest(5, TimeUnit.SECONDS) != null)
            withTimeout(2_000) { request.cancelAndJoin() }

            assertTrue(request.isCancelled)
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun compatibleTranslationResponsesAcceptFencesAndSnakeCaseKeys() {
        val client = TranslationClient(OkHttpClient(), Gson())
        val response = """
            {"choices":[{"message":{"content":"```JSON\n{\"translations\":[{\"id\":0,\"zh_cn\":\"现房销售\",\"zh_tw\":\"成屋銷售\"}]}\n```"}}]}
        """.trimIndent()

        val translated = client.parseChatResponse(response, listOf("Existing Home Sales"))

        assertEquals("现房销售", translated.getValue("Existing Home Sales").first)
        assertEquals("成屋銷售", translated.getValue("Existing Home Sales").second)
    }

    @Test
    fun translationEndpointMustBeHttpsAndContainsNoCredentials() {
        assertEquals(
            "https://api.example.com/v1",
            TranslationPreferences.normalizeBaseUrl(" https://api.example.com/v1/ "),
        )
        assertTrue(runCatching { TranslationPreferences.normalizeBaseUrl("http://api.example.com") }.isFailure)
        assertTrue(runCatching { TranslationPreferences.normalizeBaseUrl("https://key@api.example.com/v1") }.isFailure)
    }

    @Test
    fun reviewVerdictsGateTranslationsAndTolerateProviderShapes() {
        val client = TranslationClient(OkHttpClient(), Gson())
        val translations = mapOf(
            "Core CPI m/m" to ("核心CPI环比" to "核心CPI環比"),
            "Nonfarm Payrolls" to ("非农就业人口" to "非農就業人口"),
            "PPI MoM" to ("PPI环比" to "PPI環比"),
        )

        // Only entries flagged ok pass the review gate.
        assertEquals(
            setOf("Core CPI m/m", "PPI MoM"),
            client.parseVerdictResponse(
                """{"choices":[{"message":{"content":"{\"verdicts\":[{\"id\":0,\"ok\":true},{\"id\":1,\"ok\":false},{\"id\":2,\"ok\":true}]}"}}]}""",
                translations,
            ),
        )

        // A bare id list means those entries passed review.
        assertEquals(
            setOf("Nonfarm Payrolls"),
            client.parseVerdictResponse("""{"verified":[1]}""", translations),
        )

        // Fenced JSON inside message content is unwrapped like the translation response.
        assertEquals(
            setOf("Nonfarm Payrolls"),
            client.parseVerdictResponse(
                """{"choices":[{"message":{"content":"```json\n{\"verdicts\":[{\"id\":1,\"verified\":true}]}\n```"}}]}""",
                translations,
            ),
        )

        // Unknown ids never count as verified, so those names get translated again.
        assertEquals(
            emptySet<String>(),
            client.parseVerdictResponse("""{"verdicts":[{"id":9,"ok":true}]}""", translations),
        )
    }

    @Test
    fun aiAnalysisResponseIsParsedIntoTransmissionChain() {
        val client = AiAnalysisClient(OkHttpClient(), Gson())
        val draft = client.parseResponse(
            """{"choices":[{"message":{"content":"```json\n{\"chain\":[{\"from\":\"CPI surprise\",\"to\":\"real yields\",\"direction\":\"up\",\"rationale\":\"Hotter print lifts real yields.\"}],\"dataAnalysis\":\"CPI beat consensus.\",\"marketOutlook\":\"Dollar stays bid.\",\"risks\":\"Revisions.\"}\n```"}}]}""",
        )

        assertEquals(1, draft.chain.size)
        assertEquals("CPI surprise", draft.chain.first().from)
        assertEquals("real yields", draft.chain.first().to)
        assertEquals("up", draft.chain.first().direction)
        assertEquals("CPI beat consensus.", draft.dataAnalysis)
        assertEquals("Dollar stays bid.", draft.marketOutlook)
        assertEquals("Revisions.", draft.risks)
        // The chain survives the Room round-trip used by the cache.
        assertEquals(draft.chain, client.decodeChain(client.encodeChain(draft.chain)))
    }

    @Test
    fun aiAnalysisParserToleratesSnakeCaseAndDropsIncompleteChainSteps() {
        val client = AiAnalysisClient(OkHttpClient(), Gson())
        val draft = client.parseResponse(
            """{"choices":[{"message":{"content":"{\"transmission_chain\":[{\"from_node\":\"NFP\",\"to_node\":\"USD\",\"dir\":\"higher\"},{\"from\":\"orphan\"}],\"data_analysis\":\"Beat.\",\"market_outlook\":\"Bid.\"}"}}]}""",
        )

        // A step without both endpoints is dropped instead of rendering a broken link.
        assertEquals(1, draft.chain.size)
        assertEquals("NFP", draft.chain.single().from)
        assertEquals("USD", draft.chain.single().to)
        assertEquals("up", draft.chain.single().direction)
        assertEquals("Beat.", draft.dataAnalysis)
        assertNull(draft.risks)
    }

    @Test
    fun signalUsesForecastWhenTheSourcePublishesNoSeparateConsensus() {
        // Regression: calendar sources expose one expectation value ("forecast"). Before the
        // fallback existed every release was classified as neutral because consensus was null.
        val event = EconomicEvent(
            id = 1, provider = "test", providerId = "test", releaseGroupId = null,
            country = "United States", currency = "USD", category = "inflation",
            event = "CPI YoY", eventTime = "2026-09-10T12:30:00Z", importance = 3,
            actual = "3.2", previous = "2.8", consensus = null, forecast = "2.9",
            unit = "%", status = "released",
        )

        val report = LocalAnalysisEngine().analyze(event, emptyList())

        assertEquals("0.3", report.rawSurprise)
        assertTrue(report.macroSignal.contains("hawkish"))
        assertEquals("tighter_policy_baseline", report.expectedReactions.first().rationale)
    }

    @Test
    fun calendarDaysFollowTheDeviceZoneNotUtc() {
        // 2026-09-10T16:30-04:00 == 2026-09-10T20:30Z == 2026-09-11 04:30 in Asia/Shanghai.
        val json = """
            [{"title":"Beijing Morning Release","country":"USD",
              "date":"2026-09-10T16:30:00-04:00","impact":"High",
              "forecast":"0.1%","previous":"0.2%","actual":"0.3%"}]
        """.trimIndent()
        val now = Instant.parse("2026-09-11T02:00:00Z")

        val beijing = EconomicCalendarClient(OkHttpClient(), ZoneId.of("Asia/Shanghai"))
        assertEquals(1, beijing.parseForexFactory(json, now, LocalDate.of(2026, 9, 11), LocalDate.of(2026, 9, 11)).size)
        assertEquals(0, beijing.parseForexFactory(json, now, LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 10)).size)

        // Bucketing the same instant in UTC would file it under September 10 instead.
        val utc = EconomicCalendarClient(OkHttpClient(), ZoneOffset.UTC)
        assertEquals(0, utc.parseForexFactory(json, now, LocalDate.of(2026, 9, 11), LocalDate.of(2026, 9, 11)).size)
        assertEquals(1, utc.parseForexFactory(json, now, LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 10)).size)
    }

    private fun event(name: String, actual: String, consensus: String) = EconomicEvent(
        id = 1,
        provider = "test",
        providerId = "test",
        releaseGroupId = null,
        country = "United States",
        currency = "USD",
        category = "employment",
        event = name,
        eventTime = "2026-09-10T12:30:00Z",
        importance = 3,
        actual = actual,
        previous = null,
        consensus = consensus,
        forecast = null,
        unit = "%",
        status = "released",
    )
}
