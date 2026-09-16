package com.macroresearch.data.remote

import com.google.gson.Gson
import com.macroresearch.data.AnalysisMethod
import com.macroresearch.data.BackendDataSource
import com.macroresearch.data.CorrectionOutcome
import com.macroresearch.data.model.EconomicEvent
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * Contract coverage for the backend REST client: the Authorization header, camelCase decoding,
 * structured error mapping, and the data-source adapter that wraps it.
 */
class BackendClientTest {
    private lateinit var server: MockWebServer
    private val gson = Gson()
    private val token = "secret-token"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(): BackendClient = BackendClient(
        client = OkHttpClient(),
        gson = gson,
        baseUrl = { server.url("/").toString().trimEnd('/') },
        token = { token },
    )

    private fun eventJson(id: Long = 7, zhCn: String? = "消费者价格指数同比") = """
        {"id":$id,"provider":"trading_view","providerId":"tv-1","releaseGroupId":null,
         "country":"United States","currency":"USD","category":"inflation","event":"CPI YoY",
         "eventZhCn":${if (zhCn == null) "null" else "\"$zhCn\""},"eventZhTw":null,
         "eventTime":"2026-09-11T12:30:00Z","importance":3,"actual":"3.2","previous":"3.0",
         "consensus":"3.1","forecast":"3.1","unit":"%","status":"released","timeExact":true}
    """.trimIndent()

    @Test
    fun requestsCarryTheBearerTokenAndDecodeCamelCaseEvents() = runBlocking {
        server.enqueue(MockResponse().setBody("[${eventJson()}]"))
        val events = client().upcoming(days = 3)

        val recorded = server.takeRequest()
        assertEquals("/api/v1/events/upcoming?days=3", recorded.path)
        assertEquals("Bearer $token", recorded.getHeader("authorization"))
        assertEquals(1, events.size)
        val event: EconomicEvent = events.single()
        assertEquals("CPI YoY", event.event)
        assertEquals("消费者价格指数同比", event.eventZhCn)
        assertEquals("3.2", event.actual)
        // The server's extra `timeExact` field must not break decoding.
        assertEquals(3, event.importance)
    }

    @Test
    fun historySendsTheCountrySetAndPagingWindow() = runBlocking {
        server.enqueue(MockResponse().setBody("[]"))
        client().history(listOf("United States", "China"), "inflation", 8, 16)
        val path = server.takeRequest().path.orEmpty()
        assertTrue(path, path.startsWith("/api/v1/events/history?"))
        assertTrue(path, path.contains("country=United%20States%2CChina"))
        assertTrue(path, path.contains("limit=8"))
        assertTrue(path, path.contains("offset=16"))
    }

    @Test
    fun errorsAreMappedToTypedExceptions() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"error":{"code":"unauthorized","message":"bad token"}}"""),
        )
        val unauthorized = runCatching { client().upcoming() }
        assertTrue(unauthorized.exceptionOrNull() is BackendUnauthorizedException)

        server.enqueue(
            MockResponse().setResponseCode(429)
                .setHeader("retry-after", "60")
                .setBody("""{"error":{"code":"quota_exceeded","message":"quota"}}"""),
        )
        val quota = runCatching { client().upcoming() }.exceptionOrNull()
        assertTrue(quota is BackendException)
        assertEquals("quota_exceeded", (quota as BackendException).code)
        assertEquals(429, quota.statusCode)

        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        assertTrue(runCatching { client().upcoming() }.exceptionOrNull() is BackendUnavailableException)
    }

    @Test
    fun theCachedBriefingIsNullWhenNothingHasBeenGeneratedYet() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(404)
                .setBody("""{"error":{"code":"not_found","message":"missing"}}"""),
        )
        assertNull(client().aiAnalysis(7, "zh-CN", AnalysisMethod.EX_ANTE_THEN_COMPARE))
    }

    @Test
    fun metaReportsTheProtocolVersion() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"name":"macro-research","version":"0.2.0","apiVersion":1,
                    "languages":["en","zh-CN"],"capabilities":["calendar","ws"],"aiEnabled":true}""",
            ),
        )
        val meta = client().meta()
        assertEquals(1, meta.apiVersion)
        assertEquals("0.2.0", meta.version)
        assertTrue(meta.aiEnabled)
        // /meta must be reachable before a token exists.
        assertEquals(null, server.takeRequest().getHeader("authorization"))
    }

    @Test
    fun webSocketUrlFollowsTheAddressScheme() {
        val https = BackendClient(OkHttpClient(), gson, { "https://example.com" }, { token })
        assertEquals("wss://example.com/api/v1/ws", https.webSocketUrl())
        val http = BackendClient(OkHttpClient(), gson, { "" }, { token })
        assertNull(http.webSocketUrl())
    }

    @Test
    fun theBackendDataSourceKeepsCountryFilteringAndCorrectionSemantics() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                "[${eventJson()},${eventJson(id = 8, zhCn = null).replace("United States", "China")}]",
            ),
        )
        val source = BackendDataSource(client())
        val filtered = source.calendar(LocalDate.of(2026, 9, 11), LocalDate.of(2026, 9, 11), listOf("China"))
        assertEquals(1, filtered.events.size)
        assertEquals("China", filtered.events.single().country)
        server.takeRequest()

        server.enqueue(
            MockResponse().setBody(
                """{"id":3,"eventName":"CPI YoY","status":"pending","zhCn":"x","zhTw":"y"}""",
            ),
        )
        val outcome = source.submitCorrection(
            EconomicEvent(
                id = 7, provider = "trading_view", providerId = "tv-1", releaseGroupId = null,
                country = "United States", currency = "USD", category = "inflation",
                event = "CPI YoY", eventTime = "2026-09-11T12:30:00Z", importance = 3,
                actual = "3.2", previous = "3.0", consensus = "3.1", forecast = "3.1", unit = "%",
                status = "released",
            ),
            "x",
            "y",
        )
        assertTrue(outcome is CorrectionOutcome.Queued)
        assertEquals("pending", (outcome as CorrectionOutcome.Queued).status)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("\"eventName\":\"CPI YoY\""))
    }

    @Test
    fun aMissingBackendAddressIsReportedBeforeAnyRequest() {
        val unconfigured = BackendClient(OkHttpClient(), gson, { "" }, { token })
        assertTrue(runCatching { runBlocking { unconfigured.upcoming() } }.exceptionOrNull() is IllegalStateException)
        val noToken = BackendClient(OkHttpClient(), gson, { "https://example.com" }, { null })
        assertTrue(runCatching { runBlocking { noToken.upcoming() } }.exceptionOrNull() is IllegalStateException)
    }

    // ------------------------------------------------------------------
    // AI briefing: per-method metadata and the throttle fallback
    // ------------------------------------------------------------------

    private fun briefingJson() = """
        {"eventId":7,"method":2,"revision":3,"chain":[],
         "dataAnalysis":"d","marketOutlook":"o","risks":null,
         "model":"deepseek-v4-flash","generatedAt":"2026-09-15T09:00:00Z",
         "usage":{"promptTokens":120,"completionTokens":800,"totalTokens":920,"calls":2},
         "fromCache":true,"rateLimited":true,"retryAfterSeconds":480}
    """.trimIndent()

    /** A throttled response carries the previous analysis plus the reason it was served. */
    @Test
    fun throttledBriefingDecodesWithItsMetadata() = runBlocking {
        server.enqueue(MockResponse().setBody(briefingJson()))
        val analysis = client().generateAiAnalysis(
            id = 7,
            languageTag = "zh-CN",
            method = AnalysisMethod.EX_ANTE_THEN_COMPARE,
            regenerate = true,
        )
        assertEquals(2, analysis.method)
        assertEquals(3, analysis.revision)
        assertTrue(analysis.rateLimited)
        assertTrue(analysis.fromCache)
        assertEquals(480L, analysis.retryAfterSeconds)
        assertEquals(920, analysis.usage?.totalTokens)
        assertEquals(2, analysis.usage?.calls)
        // The audit line the analysis screen shows must be human-readable.
        assertEquals("2 × 920 tokens", analysis.usageSummary())
    }

    /** A cache hit is not throttled, and the method the client asked for is preserved. */
    @Test
    fun cachedBriefingIsNotMarkedThrottled() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                briefingJson()
                    .replace("\"method\":2", "\"method\":1")
                    .replace("\"rateLimited\":true", "\"rateLimited\":false")
                    .replace("\"retryAfterSeconds\":480", "\"retryAfterSeconds\":null"),
            ),
        )
        val analysis = client().aiAnalysis(7, "en", AnalysisMethod.NUMBERS_ONLY)
        requireNotNull(analysis)
        assertEquals(1, analysis.method)
        assertTrue(analysis.fromCache)
        assertTrue(!analysis.rateLimited)
        assertNull(analysis.retryAfterSeconds)
        // A cache hit still reports what its generation cost.
        assertEquals(2, analysis.usage?.calls)
        assertEquals("2 × 920 tokens", analysis.usageSummary())
    }

    /** The method numbers must match the server's `AnalysisMethod::from_u8`. */
    @Test
    fun theThreeMethodsMapToDistinctCacheSlots() {
        assertEquals(1, AnalysisMethod.NUMBERS_ONLY.wireValue)
        assertEquals(2, AnalysisMethod.EX_ANTE_THEN_COMPARE.wireValue)
        assertEquals(3, AnalysisMethod.SINGLE_PASS.wireValue)
        assertEquals(
            3,
            setOf(
                AnalysisMethod.NUMBERS_ONLY,
                AnalysisMethod.EX_ANTE_THEN_COMPARE,
                AnalysisMethod.SINGLE_PASS,
            ).map { it.wireValue }.toSet().size,
        )
    }
}
