package com.macroresearch.data

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.macroresearch.data.remote.*
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NewsBackendTest {
    @get:Rule val temporary = TemporaryFolder()
    private val now = Instant.now()
    private val article = NewsArticle("US policy outlook","https://publisher.example/report","Federal Reserve",now.minusSeconds(60).toString(),"Public policy summary")
    private fun snapshot() = ReaderHeadlines(listOf(article),now.toString(),"articles_found",
        listOf(ReaderSourceStatus("fed","Federal Reserve","fresh",now.toString())))
    private fun payload() = """{"updatedAt":"$now","stale":false,"partial":false,
        "sources":[{"id":"fed","name":"Federal Reserve","status":"fresh","updatedAt":"$now"}],
        "articles":[{"title":"US policy outlook","url":"${article.url}","source":"Federal Reserve","feedId":"fed","publishedAt":"${article.publishedAt}","summary":"Public policy summary"}]}"""
    private fun settings() = MutableStateFlow(BackendSettings(mode = DataSourceMode.BACKEND,baseUrl = "https://one.example",tokenConfigured = true))
    private fun repo(settings:MutableStateFlow<BackendSettings>,fetch:suspend () -> ReaderHeadlines):NewsReaderRepository {
        val forbidden = OkHttpClient.Builder().addInterceptor { error("Backend news must not contact public media") }.build()
        return NewsReaderRepository(NewsRssClient(forbidden),ArticlePreviewClient(forbidden),
            NewsReaderCache(File(temporary.newFolder(),"headlines.json")),settings,fetch)
    }
    @Test fun backendRefreshAndReadingNeverContactRssPagesOrDirectFallback() = runBlocking {
        var calls = 0
        val repo = repo(settings()) { calls++; snapshot() }
        assertEquals(snapshot(),repo.refresh(includeInternational = true))
        assertEquals(1,calls)
        assertNull(repo.read(article))
        assertEquals(article,repo.cached().articles.single())
        assertEquals("cached",repo.cached().status)
    }
    @Test fun unavailableOrOldBackendPreservesOnlyItsOwnSnapshotWithoutDirectFallback() = runBlocking {
        val settings = settings(); var offline = false
        val repo = repo(settings) { if (offline) throw BackendException(404,"not_found","old backend") else snapshot() }
        repo.refresh(); offline = true
        try { repo.refresh(); fail("Expected old backend error") } catch (error:BackendException) { assertEquals(404,error.statusCode) }
        assertEquals(article,repo.cached().articles.single())
        settings.value = settings.value.copy(baseUrl = "https://two.example")
        assertTrue(repo.cached().articles.isEmpty())
        settings.value = settings.value.copy(mode = DataSourceMode.DIRECT)
        assertTrue(repo.cached().articles.isEmpty())
        settings.value = settings.value.copy(mode = DataSourceMode.BACKEND,baseUrl = "https://one.example")
        assertEquals(article,repo.cached().articles.single())
    }
    @Test fun changedBackendRejectsLateResultsAndUnconfiguredBackendNeverFetches() = runBlocking {
        val settings = settings()
        val repo = repo(settings) { settings.value = settings.value.copy(baseUrl = "https://two.example"); snapshot() }
        try { repo.refresh(); fail() } catch (_:CancellationException) { }
        assertTrue(repo.cached().articles.isEmpty())
        settings.value = settings.value.copy(tokenConfigured = false)
        try { repo.refresh(); fail() } catch (_:IllegalStateException) { }
    }
    @Test fun authenticatedApiUsesNoStoreAndMatchesServerContract() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody(payload()))
            val client = BackendClient(OkHttpClient(),Gson(),{ server.url("/").toString().trimEnd('/') },{ "fixture-key" })
            assertEquals(snapshot(),client.news())
            val request = server.takeRequest()
            assertEquals("/api/v1/news",request.path)
            assertEquals("Bearer fixture-key",request.getHeader("Authorization"))
            assertTrue(request.getHeader("Cache-Control").orEmpty().contains("no-store"))
            server.enqueue(MockResponse().setResponseCode(401))
            try { client.news(); fail() } catch (_:BackendUnauthorizedException) { }
            server.enqueue(MockResponse().setResponseCode(404))
            try { client.news(); fail() } catch (error:BackendException) { assertEquals(404,error.statusCode) }
        }
    }
    @Test fun malformedOversizedAndUnknownSourceResponsesAreRejected() = runBlocking {
        for (raw in listOf("{}",payload().replace("\"fresh\"","\"invented\""),payload().replace("\"feedId\":\"fed\"","\"feedId\":\"other\""))) {
            assertThrows(Exception::class.java) { decodeReaderNews(JsonParser.parseString(raw).asJsonObject,Gson(),now) }
        }
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("x".repeat(1024*1024+1)))
            val client = BackendClient(OkHttpClient(),Gson(),{ server.url("/").toString().trimEnd('/') },{ "fixture-key" })
            try { client.news(); fail() } catch (_:BackendUnavailableException) { }
        }
    }
    @Test fun staleFlagsAndSourceFailuresArePreservedWithoutInventingArticleTimes() {
        val root = JsonParser.parseString(payload()).asJsonObject
        root.addProperty("stale",true); root.addProperty("partial",true)
        root.getAsJsonArray("sources")[0].asJsonObject.addProperty("status","stale")
        val result = decodeReaderNews(root,Gson(),now)
        assertTrue(result.stale); assertEquals("partial_results",result.status)
        assertEquals(now.toString(),result.fetchedAt)
        assertEquals(article.publishedAt,result.articles.single().publishedAt)
        root.getAsJsonArray("articles")[0].asJsonObject.addProperty("publishedAt",now.plusSeconds(60).toString())
        assertTrue(decodeReaderNews(root,Gson(),now).articles.isEmpty())
    }
}
