package com.macroresearch.data

import com.google.gson.Gson
import com.macroresearch.data.model.EconomicEvent
import com.macroresearch.data.model.ExpectedReaction
import com.macroresearch.data.model.MarketReaction
import com.macroresearch.data.remote.AiAnalysisClient
import com.macroresearch.data.remote.AiAnalysisDraft
import com.macroresearch.data.remote.AiAnalysisInput
import com.macroresearch.data.remote.NewsArticle
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/** The three configured methods must send, and withhold, market moves exactly as described. */
class AnalysisMethodTest {
    private val expectationJson = """
        {"chain":[{"from":"CPI surprise","to":"real yields","direction":"up","rationale":"hot print"}],
         "dataAnalysis":"Core CPI 0.3% vs 0.2% expected.","marketOutlook":"Yields and the dollar firm up.","risks":"Revisions."}
    """.trimIndent()

    private val comparisonJson = """
        {"chain":[{"from":"CPI surprise","to":"real yields","direction":"up","rationale":"hot print","verdict":"confirmed"},
                  {"from":"real yields","to":"gold","direction":"down","rationale":"higher real yields","verdict":"contradicted"}],
         "dataAnalysis":"ignored by the caller","marketOutlook":"Expected a firmer dollar; it fell instead.","risks":"Positioning."}
    """.trimIndent()

    @Test
    fun numbersOnlyNeverSendsMarketMoves() {
        val (draft, bodies) = run(listOf(expectationJson), AnalysisMethod.NUMBERS_ONLY)
        assertEquals(1, bodies.size)
        assertFalse(bodies.single().contains("observedReactions"))
        assertFalse(bodies.single().contains("-0.67"))
        assertTrue(bodies.single().contains("available event data"))
        assertTrue(bodies.single().contains("Core CPI m/m"))
        assertEquals("Core CPI 0.3% vs 0.2% expected.", draft.dataAnalysis)
    }

    @Test
    fun singlePassSendsEverythingAtOnce() {
        val (_, bodies) = run(listOf(expectationJson), AnalysisMethod.SINGLE_PASS)
        assertEquals(1, bodies.size)
        assertTrue(bodies.single().contains("observedReactions"))
        assertTrue(bodies.single().contains("-0.67"))
        assertFalse(bodies.single().contains("exAnteExpectation"))
    }

    @Test
    fun twoStageAsksForTheExpectationBeforeRevealingMoves() {
        val (draft, bodies) = run(listOf(expectationJson, comparisonJson), AnalysisMethod.EX_ANTE_THEN_COMPARE)

        assertEquals(2, bodies.size)
        val (first, second) = bodies
        // First pass: numbers and rules only, so the chain cannot be fitted to past prices.
        assertFalse(first.contains("observedReactions"))
        assertFalse(first.contains("-0.67"))
        assertFalse(first.contains("verdict"))
        // Second pass: the expectation goes back in, together with the moves.
        assertTrue(second.contains("observedReactions"))
        assertTrue(second.contains("-0.67"))
        assertTrue(second.contains("exAnteExpectation"))
        assertTrue(second.contains("Core CPI 0.3% vs 0.2% expected."))
        // The ex-ante read is kept; only the outlook and verdicts come from the comparison.
        assertEquals("Core CPI 0.3% vs 0.2% expected.", draft.dataAnalysis)
        assertEquals(listOf("confirmed", "contradicted"), draft.chain.map { it.verdict })
        assertEquals("Expected a firmer dollar; it fell instead.", draft.marketOutlook)
        assertEquals("Positioning.", draft.risks)
    }

    @Test
    fun missingMovesCollapseEveryMethodToOneExpectationPass() {
        val (draft, bodies) = run(listOf(expectationJson), AnalysisMethod.EX_ANTE_THEN_COMPARE, withMoves = false)
        assertEquals(1, bodies.size)
        assertFalse(bodies.single().contains("observedReactions"))
        assertEquals("Core CPI 0.3% vs 0.2% expected.", draft.dataAnalysis)
    }

    @Test
    fun missingPublishedValueStillProducesAnEventContextBriefingWithoutInventingNumbers() {
        val (_, bodies) = run(
            listOf(expectationJson),
            AnalysisMethod.NUMBERS_ONLY,
            actual = null,
            consensus = null,
        )
        val body = bodies.single()
        // Gson omits absent map values, which makes the missing fields explicit to the model.
        assertFalse(body.contains("\"actual\""))
        assertFalse(body.contains("\"consensus\""))
        assertTrue(body.contains("When actual is absent, do not describe a data surprise"))
        assertTrue(body.contains("do not infer or fabricate them"))
    }

    @Test
    fun twoStageMethodWithholdsNewsFromExAntePassAndAddsItToComparisonPass() {
        val (_, bodies) = run(
            listOf(expectationJson, comparisonJson),
            AnalysisMethod.EX_ANTE_THEN_COMPARE,
            newsArticles = listOf(
                NewsArticle(
                    title = "Federal Reserve signals steady rates",
                    url = "https://example.com/fed-story",
                    source = "Example News",
                    publishedAt = "2026-09-23T14:00:00Z",
                    summary = "Policy outlook remains uncertain.",
                ),
            ),
        )
        assertEquals(2, bodies.size)
        assertFalse(bodies.first().contains("Federal Reserve signals steady rates"))
        assertTrue(bodies.last().contains("Federal Reserve signals steady rates"))
    }

    @Test
    fun relatedNewsIsIncludedWithItsSourceTimeAndLinkWhenRequested() {
        val (_, bodies) = run(
            listOf(expectationJson),
            AnalysisMethod.NUMBERS_ONLY,
            newsArticles = listOf(
                NewsArticle(
                    title = "Federal Reserve signals steady rates",
                    url = "https://example.com/fed-story",
                    source = "Example News",
                    publishedAt = "2026-09-23T14:00:00Z",
                    summary = "Policy outlook remains uncertain.",
                ),
            ),
        )
        val body = bodies.single()
        assertTrue(body.contains("relatedNews"))
        assertTrue(body.contains("Federal Reserve signals steady rates"))
        assertTrue(body.contains("https://example.com/fed-story"))
        assertTrue(body.contains("2026-09-23T14:00:00Z"))
        assertTrue(body.contains("cite the exact supplied title"))
    }

    @Test
    fun verdictsAreNormalisedAndOptional() {
        val client = AiAnalysisClient(OkHttpClient(), Gson())
        assertEquals(
            listOf("confirmed", "contradicted"),
            client.parseResponse(comparisonJson).chain.map { it.verdict },
        )
        // Briefings cached before verdicts existed simply have none.
        assertNull(client.parseResponse(expectationJson).chain.single().verdict)
        assertEquals(
            "unobserved",
            client.parseResponse(
                """{"chain":[{"from":"a","to":"b","direction":"up","rationale":"r","verdict":"N/A"}],
                    "dataAnalysis":"x","marketOutlook":"y"}""",
            ).chain.single().verdict,
        )
        assertNull(
            client.parseResponse(
                """{"chain":[{"from":"a","to":"b","direction":"up","rationale":"r","verdict":"maybe"}],
                    "dataAnalysis":"x","marketOutlook":"y"}""",
            ).chain.single().verdict,
        )
    }

    @Test
    fun methodDefaultsToTheTwoStageComparisonAndSurvivesUnknownKeys() {
        assertEquals(AnalysisMethod.EX_ANTE_THEN_COMPARE, AnalysisMethod.DEFAULT)
        assertEquals(AnalysisMethod.EX_ANTE_THEN_COMPARE, AnalysisMethod.fromKey(null))
        assertEquals(AnalysisMethod.EX_ANTE_THEN_COMPARE, AnalysisMethod.fromKey("legacy_value"))
        AnalysisMethod.entries.forEach { method -> assertEquals(method, AnalysisMethod.fromKey(method.key)) }
    }

    /** Runs the configured method against a stub gateway and returns the request bodies it saw. */
    private fun run(
        replies: List<String>,
        method: AnalysisMethod,
        withMoves: Boolean = true,
        actual: String? = "0.3",
        consensus: String? = "0.2",
        newsArticles: List<NewsArticle> = emptyList(),
    ): Pair<AiAnalysisDraft, List<String>> = runBlocking {
        val server = MockWebServer()
        replies.forEach { server.enqueue(MockResponse().setBody(envelope(it))) }
        server.start()
        try {
            val client = AiAnalysisClient(OkHttpClient(), Gson())
            val settings = TranslationSettings(true, server.url("/v1").toString().removeSuffix("/"), "test-model")
            val draft = client.analyze(input(withMoves, actual, consensus, newsArticles), settings, "test-key", method)
            val bodies = generateSequence { server.takeRequest(1, TimeUnit.SECONDS)?.body?.readUtf8() }.toList()
            draft to bodies
        } finally {
            server.shutdown()
        }
    }

    private fun envelope(content: String) =
        """{"choices":[{"message":{"content":${Gson().toJson(content)}}}]}"""

    private fun input(
        withMoves: Boolean = true,
        actual: String? = "0.3",
        consensus: String? = "0.2",
        newsArticles: List<NewsArticle> = emptyList(),
    ) = AiAnalysisInput(
        event = EconomicEvent(
            id = 1, provider = "trading_view", providerId = "1", releaseGroupId = null,
            country = "United States", currency = "USD", category = "inflation",
            event = "Core CPI m/m", eventTime = "2026-09-11T12:30:00Z", importance = 3,
            actual = actual, previous = "0.2", consensus = consensus, forecast = "0.2",
            unit = "%", status = "released",
        ),
        macroSignal = "hawkish",
        rawSurprise = "0.1",
        expectedReactions = listOf(ExpectedReaction("gold", "down", "tighter_policy_baseline")),
        observedReactions = listOf(
            MarketReaction(
                eventId = 1, symbol = "gold", baselinePrice = 4384.2, reactionUnit = "percent",
                change1m = if (withMoves) -0.67 else null,
                change5m = if (withMoves) -0.14 else null,
                change15m = if (withMoves) 0.44 else null,
                change30m = if (withMoves) 1.07 else null,
                change60m = if (withMoves) 1.08 else null,
            ),
        ),
        languageTag = "zh-CN",
        newsSearchRequested = newsArticles.isNotEmpty(),
        newsSearchStatus = if (newsArticles.isNotEmpty()) "articles_found" else null,
        newsArticles = newsArticles,
    )
}
