package com.macroresearch.data

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.macroresearch.data.remote.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

class NewsArchiveClientTest {
    @get:Rule val temporary = TemporaryFolder()
    private val now=Instant.now()
    private val id="a".repeat(64)
    private val article=NewsArticle("Policy release","https://www.federalreserve.gov/newsevents/original.htm","Federal Reserve",now.minusSeconds(60).toString(),"RSS summary",id)
    private fun detail(status:String="available",url:String=article.url,text:String="Saved public article text.") = """{
        "archiveId":"$id","url":"$url","title":"Policy release","text":"$text","truncated":false,"fetchedAt":"$now","bodyStatus":"$status"} """
    private fun page(date:String=article.publishedAt!!,offset:Int?=100) = """{
        "updatedAt":"$now","stale":false,"partial":false,"nextOffset":$offset,
        "sources":[{"id":"fed","name":"Federal Reserve","status":"fresh","updatedAt":"$now"}],
        "articles":[{"archiveId":"$id","title":"Policy release","url":"${article.url}","source":"Federal Reserve","feedId":"fed","publishedAt":"$date","summary":"RSS summary"}]}"""
    private fun client(server:MockWebServer)=BackendClient(OkHttpClient(),Gson(),{ server.url("/").toString().trimEnd('/') },{ "fixture-key" })

    @Test fun bodyReadsUseOnlyAuthenticatedArchiveIdsAndKeepOriginalUrlAndFetchTime() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val client=client(server)
            server.enqueue(MockResponse().setBody(detail()))
            val body=client.newsArticle(article)!!
            assertEquals(article.url,body.url); assertEquals(now.toString(),body.fetchedAt)
            assertEquals("Saved public article text.",body.text)
            val request=server.takeRequest()
            assertEquals("/api/v1/news/articles/$id",request.path)
            assertEquals("Bearer fixture-key",request.getHeader("Authorization"))
            assertTrue(request.getHeader("Cache-Control").orEmpty().contains("no-store"))
            for (status in listOf("pending","unavailable")) {
                server.enqueue(MockResponse().setBody(detail(status)))
                assertNull(client.newsArticle(article))
            }
            val count=server.requestCount
            assertNull(client.newsArticle(article.copy(archiveId=null)))
            try { client.newsArticle(article.copy(archiveId="../unsafe")); fail() } catch (_:IllegalArgumentException) { }
            assertEquals(count,server.requestCount)
        }
    }
    @Test fun returnedBodiesMustMatchRequestedOriginalUrlAndStayBounded() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val client=client(server)
            for (raw in listOf(detail(url="https://other.example/report"),detail(text="x".repeat(64001)),detail().replace(id,"b".repeat(64)))) {
                server.enqueue(MockResponse().setBody(raw))
                try { client.newsArticle(article); fail() } catch (_:BackendUnavailableException) { }
            }
            server.enqueue(MockResponse().setResponseCode(404))
            try { client.newsArticle(article); fail() } catch (error:BackendException) { assertEquals(404,error.statusCode) }
        }
    }
    @Test fun archivedNewsAcceptsLastThirtyDaysAndUsesBoundedPaginationNotArbitraryUrls() = runBlocking {
        val old=now.minusSeconds(20*86400L).toString()
        val decoded=decodeReaderNews(JsonParser.parseString(page(old)).asJsonObject,Gson(),now)
        assertEquals(id,decoded.articles.single().archiveId); assertEquals(100,decoded.nextOffset)
        assertTrue(decodeReaderNews(JsonParser.parseString(page(now.minusSeconds(31*86400L).toString())).asJsonObject,Gson(),now).articles.isEmpty())
        MockWebServer().use { server ->
            server.start(); val client=client(server)
            server.enqueue(MockResponse().setBody(page(old,200)))
            assertEquals(200,client.newsPage(100).nextOffset)
            assertEquals("/api/v1/news?offset=100",server.takeRequest().path)
            try { client.newsPage(-1); fail() } catch (_:IllegalArgumentException) { }
            assertEquals(1,server.requestCount)
        }
    }
    @Test fun repositoryBodyAndHistoryReadBackendOnlyAndRejectLateCrossSourceResults() = runBlocking {
        val settings=MutableStateFlow(BackendSettings(mode=DataSourceMode.BACKEND,baseUrl="https://one.example",tokenConfigured=true))
        val forbidden=OkHttpClient.Builder().addInterceptor { error("Must not contact publisher from backend mode") }.build()
        var bodies=0; var pages=0; var change=false
        val repo=NewsReaderRepository(NewsRssClient(forbidden),ArticlePreviewClient(forbidden),
            NewsReaderCache(File(temporary.root,"headlines.json")),settings,
            backendRead={ input -> bodies++; if(change) settings.value=settings.value.copy(baseUrl="https://two.example");
                ArticleReading(input.url,input.title,"Saved text",false,now.toString()) },
            backendPage={ offset -> pages++; assertEquals(100,offset); ReaderHeadlines(listOf(article),now.toString(),"articles_found") })
        assertEquals(article.url,repo.read(article)!!.url)
        assertEquals(article,repo.more(100).articles.single()); assertEquals(1,bodies); assertEquals(1,pages)
        change=true
        try { repo.read(article); fail() } catch (_:CancellationException) { }
    }
}
