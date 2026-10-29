package com.macroresearch.data

import com.google.gson.Gson
import com.macroresearch.data.model.EconomicEvent
import com.macroresearch.data.remote.BiquoteNewsClient
import com.macroresearch.data.remote.NewsArticle
import com.macroresearch.data.remote.NewsRssClient
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
import java.time.Instant
import java.util.concurrent.TimeUnit

class BiquoteNewsClientTest {
    private val time = Instant.parse("2026-10-28T18:00:00Z")
    private val now = time.plusSeconds(3600)
    private val event = EconomicEvent(
        id = 1, provider = "trading_view", providerId = "rate", releaseGroupId = null,
        country = "United States", currency = "USD", category = "money", event = "Fed Interest Rate Decision",
        eventZhCn = null, eventZhTw = null, eventTime = time.toString(), importance = 3,
        actual = null, previous = "4", consensus = null, forecast = null, unit = "%", status = "data_unavailable",
    )
    private val json = """[{"title":"Fed holds rates steady","description":"<p>Federal Reserve update</p>","publisher":"Example News","publishedDate":"2026-10-28T18:30:00Z","url":"https://example.com/fed","imageUrl":null,"language":null,"country":null}]"""

    private fun http(handler: (okhttp3.Request) -> Pair<Int, String>) = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val (code, body) = handler(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("test").body(body.toResponseBody()).build()
        }.build()

    @Test fun mapsPublicMetadataWithoutFetchingArticlesOrSendingCredentials() = runBlocking {
        var calls = 0
        val client = BiquoteNewsClient(http { request ->
            calls++
            assertEquals("https://biquote.io/api/news/market", request.url.toString().substringBefore('?'))
            assertEquals("en", request.url.queryParameter("language"))
            assertEquals("US", request.url.queryParameter("country"))
            assertEquals("50", request.url.queryParameter("maxResults"))
            assertNull(request.url.queryParameter("symbol"))
            assertNull(request.header("Authorization"))
            assertNull(request.header("X-Installation-Id"))
            assertNull(request.header("X-Android-Id-Hash"))
            200 to json
        })
        val result = client.marketNews()
        assertEquals("articles_found", result.status)
        val article = result.articles.single()
        assertEquals("Fed holds rates steady", article.title)
        assertEquals("Example News", article.source)
        assertEquals("Federal Reserve update", article.summary)
        assertEquals("2026-10-28T18:30:00Z", article.publishedAt)
        assertEquals("https://example.com/fed", article.url)
        assertEquals(1, calls)
    }

    @Test fun rejectsUnsafeLinksUnknownDatesAndMalformedRowsAndDeduplicates() {
        val row = Gson().fromJson(json, com.google.gson.JsonArray::class.java).single().asJsonObject
        val rows = com.google.gson.JsonArray().apply {
            add(row)
            add(row.deepCopy())
            add(row.deepCopy().apply { addProperty("url", "http://example.com/unsafe") })
            add(row.deepCopy().apply { addProperty("url", "javascript:alert(1)") })
            add(row.deepCopy().apply { addProperty("url", "https://user:password@example.com/private") })
            add(row.deepCopy().apply { addProperty("publishedDate", "unknown") })
            add(row.deepCopy().apply { remove("publishedDate") })
            add(row.deepCopy().apply { addProperty("title", "<p> </p>") })
            add("not an object")
        }
        val client = BiquoteNewsClient(OkHttpClient())
        assertEquals(1, client.parseArticles(rows.toString()).size)
        assertTrue(runCatching { client.parseArticles("{}") }.isFailure)
        assertTrue(client.parseArticles("[]").isEmpty())
    }

    @Test fun failuresAndRateLimitsDoNotRetryOrBlockAnalysis() = runBlocking {
        for ((code, body) in listOf(429 to "", 503 to "", 200 to "{}", 200 to "not-json", 200 to "x".repeat(512 * 1024 + 1))) {
            var calls = 0
            val result = BiquoteNewsClient(http { calls++; code to body }).marketNews()
            assertEquals("search_unavailable", result.status)
            assertTrue(result.articles.isEmpty())
            assertEquals(1, calls)
        }
        assertEquals("no_relevant_articles_found", BiquoteNewsClient(http { 200 to "[]" }).marketNews().status)
    }

    @Test fun optInCombinesRssAndBiquoteAndKeepsPartialFailureVisible() = runBlocking {
        val rss = """<rss><channel><item><title>Fed holds rates steady</title><link>https://example.com/fed</link><pubDate>Wed, 28 Oct 2026 18:30:00 GMT</pubDate></item></channel></rss>"""
        var marketCalls = 0
        val client = NewsRssClient(http { request ->
            if (request.url.host == "biquote.io") { marketCalls++; 200 to json } else 200 to rss
        })
        assertEquals("search_unavailable", client.relevantArticles(event, emptyList(), now = now).status)
        assertEquals(0, marketCalls)
        assertEquals(1, client.relevantArticles(event, listOf("https://rss.example.com/feed"), now = now).articles.size)
        assertEquals(0, marketCalls)
        val combined = client.relevantArticles(event, listOf("https://rss.example.com/feed"), includeBiquote = true, now = now)
        assertEquals("articles_found", combined.status)
        assertEquals(1, combined.articles.size)
        assertEquals(1, marketCalls)
        val partial = NewsRssClient(http { request ->
            if (request.url.host == "biquote.io") 200 to json else 503 to ""
        }).relevantArticles(event, listOf("https://rss.example.com/feed"), includeBiquote = true, now = now)
        assertEquals("partial_results", partial.status)
        assertEquals(1, partial.articles.size)
    }

    @Test fun filtersByEventTimeAndTitleRatherThanDescriptionsOrSubstrings() {
        val client = NewsRssClient(OkHttpClient())
        val relevant = NewsArticle("Fed holds rates steady", "https://example.com/fed", "Example", time.toString(), null)
        val articles = listOf(
            relevant,
            relevant.copy(url = "https://example.com/old", publishedAt = time.minusSeconds(86401).toString()),
            relevant.copy(url = "https://example.com/future", publishedAt = now.plusSeconds(1).toString()),
            relevant.copy(url = "https://example.com/unknown", publishedAt = null),
            relevant.copy(url = "https://example.com/unrelated", title = "Technology earnings", summary = "Fed interest rate decision"),
            relevant.copy(url = "https://example.com/substring", title = "Federated computing grows"),
        )
        assertEquals(listOf(relevant), client.selectArticles(event, articles, now))
        assertTrue(client.selectArticles(event.copy(eventTime = time.minusSeconds(86400 * 30).toString()), articles, now).isEmpty())
        assertTrue(client.selectArticles(event.copy(timeExact = false), articles, now).isEmpty())
        assertTrue(client.selectArticles(event.copy(eventTime = now.plusSeconds(1).toString()), articles, now).isEmpty())
        assertTrue(client.selectArticles(event, listOf(relevant.copy(publishedAt = time.plusSeconds(86401).toString())), time.plusSeconds(172800)).isEmpty())
    }

    @Test fun keepsTheNewestTitleMatchesWithAnEightArticleBudget() {
        val client = NewsRssClient(OkHttpClient())
        val articles = (0..11).map { index ->
            NewsArticle("Fed outlook", "https://example.com/$index", "Example", time.plusSeconds(index.toLong()).toString(), null)
        }
        val selected = client.selectArticles(event, articles + articles.last(), now)
        assertEquals(8, selected.size)
        assertEquals("https://example.com/11", selected.first().url)
        assertEquals(8, selected.map { it.url }.distinct().size)
        assertFalse(NewsSettings().enabled)
        assertFalse(NewsSettings().biquoteEnabled)
    }

    @Test fun emptyAndOversizedRssSourcesDoNotPreventBiquoteResults() = runBlocking {
        for (rss in listOf("<rss><channel/></rss>", "x".repeat(512 * 1024 + 1))) {
            val client = NewsRssClient(http { request ->
                if (request.url.host == "biquote.io") 200 to json else 200 to rss
            })
            val result = client.relevantArticles(event, listOf("https://rss.example.com/feed"), includeBiquote = true, now = now)
            assertEquals(1, result.articles.size)
            assertEquals(if (rss.length > 512 * 1024) "partial_results" else "articles_found", result.status)
        }
        val unrelated = NewsRssClient(http { 200 to json.replace("Fed holds rates steady", "Technology earnings") })
            .relevantArticles(event, emptyList(), includeBiquote = true, now = now)
        assertEquals("no_relevant_articles_found", unrelated.status)
        assertTrue(unrelated.articles.isEmpty())
    }

    @Test fun cancellationPropagatesAndCancelsTheHttpCall() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(json).throttleBody(1, 250, TimeUnit.MILLISECONDS))
            val started = CompletableDeferred<Unit>()
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request()).also { started.complete(Unit) }
            }.build()
            val client = BiquoteNewsClient(http, server.url("/api/news/market").toString())
            val job = launch { client.marketNews(); fail("cancelled fetch must not return a result") }
            withTimeout(3000) { started.await() }
            withTimeout(3000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
        }
    }
}
