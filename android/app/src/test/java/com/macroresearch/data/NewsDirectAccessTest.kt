package com.macroresearch.data

import com.macroresearch.data.remote.*
import kotlinx.coroutines.runBlocking
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.Proxy
import java.nio.charset.Charset
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue

class NewsDirectAccessTest {
    @get:Rule val temporary = TemporaryFolder()
    private val domestic = "www.chinanews.com.cn"
    private val date = "Thu, 5 Nov 2026 09:31:00 +0800"
    private fun feed(host: String) = """<?xml version="1.0" encoding="UTF-8"?>
        <rss><channel><item><title>财经新闻 · $host</title>
        <link>https://$host/report.shtml</link><pubDate>$date</pubDate>
        <description>央行政策与全球市场。</description></item></channel></rss>"""
    private fun repository(requests: ConcurrentLinkedQueue<String>, handler: (String) -> Pair<Int,String>): NewsReaderRepository {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            for (header in listOf("Authorization","Cookie","X-Installation-Id","X-Android-Id-Hash")) assertNull(request.header(header))
            requests.add(request.url.host)
            val (code,body) = handler(request.url.host)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                .body(body.toResponseBody("application/rss+xml".toMediaType())).build()
        }.build()
        return NewsReaderRepository(NewsRssClient(http),ArticlePreviewClient(),
            NewsReaderCache(File(temporary.newFolder(),"headlines.json")))
    }

    @Test fun defaultLoadsDomesticNewsWithoutContactingOrWaitingForInternationalHosts() = runBlocking {
        val requests = ConcurrentLinkedQueue<String>()
        val repo = repository(requests) { host ->
            assertEquals(domestic,host); 200 to feed(host)
        }
        val result = repo.refresh()
        assertEquals("articles_found",result.status)
        assertEquals(listOf(domestic),requests.toList())
        assertEquals("中新网财经",ReaderNewsSources.label(result.articles.single().source))
        assertEquals(Instant.parse("2026-11-05T01:31:00Z"),newsPublishedInstant(result.articles.single().publishedAt))
    }

    @Test fun domesticFailureTriesBoundedInternationalFallbackAndReportsPartialResults() = runBlocking {
        val requests = ConcurrentLinkedQueue<String>()
        val result = repository(requests) { host ->
            if (host == "feeds.bbci.co.uk") 200 to feed(host) else 503 to "unavailable"
        }.refresh()
        assertEquals("partial_results",result.status)
        assertEquals("feeds.bbci.co.uk",result.articles.single().source)
        assertEquals(domestic,requests.first())
        assertEquals(6,requests.size)
        assertEquals(6,requests.toSet().size)
    }

    @Test fun internationalOptionAddsSourcesAndDisablingItStopsThoseRequests() = runBlocking {
        val requests = ConcurrentLinkedQueue<String>()
        val repo = repository(requests) { host -> 200 to feed(host) }
        assertEquals(6,repo.refresh(includeInternational = true).articles.size)
        assertEquals(6,requests.size)
        requests.clear()
        assertEquals(1,repo.refresh(includeInternational = false).articles.size)
        assertEquals(listOf(domestic),requests.toList())
    }

    @Test fun htmlDenialIsNotASuccessfulEmptyFeedAndTotalFailureKeepsOldTimestamp() = runBlocking {
        val requests = ConcurrentLinkedQueue<String>()
        var online = true
        val repo = repository(requests) { host ->
            200 to if (online) feed(host) else "<html><body>Access denied</body></html>"
        }
        val initial = repo.refresh()
        online = false; requests.clear()
        val failed = repo.refresh()
        assertEquals("search_unavailable",failed.status)
        assertEquals(initial.fetchedAt,failed.fetchedAt)
        assertEquals(initial.articles,failed.articles)
        assertEquals(6,requests.size)
    }

    @Test fun aValidEmptyFeedDoesNotTriggerInternationalTraffic() = runBlocking {
        val requests = ConcurrentLinkedQueue<String>()
        val result = repository(requests) { 200 to "<rss><channel/></rss>" }.refresh()
        assertEquals("no_articles_found",result.status)
        assertTrue(result.articles.isEmpty())
        assertEquals(listOf(domestic),requests.toList())
    }

    @Test fun xmlDeclarationsPreserveChineseForBothUtf8AndGbk() {
        val parser = NewsRssClient(OkHttpClient())
        for (charset in listOf(Charsets.UTF_8,Charset.forName("GBK"))) {
            val xml = feed(domestic).replace("encoding=\"UTF-8\"","encoding=\"${charset.name()}\"")
            val row = parser.parseFeed(xml.toByteArray(charset),"https://$domestic/rss/finance.xml").single()
            assertEquals("财经新闻 · $domestic",row.title)
            assertEquals("央行政策与全球市场。",row.summary)
            assertEquals(date,row.publishedAt)
        }
    }

    @Test fun fetchHonorsXmlEncodingEvenWhenHttpHeaderDoesNotNameACharset() = runBlocking {
        val bytes = feed(domestic).replace("UTF-8","GBK").toByteArray(Charset.forName("GBK"))
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(bytes.toResponseBody("application/xml".toMediaType())).build()
        }.build()
        val result = NewsRssClient(http).latestArticles(ReaderNewsSources.urls)
        assertEquals("财经新闻 · $domestic",result.articles.single().title)
    }

    @Test fun onlyRssAtomOrRdfRootsCanBeAcceptedAsFeeds() {
        val parser = NewsRssClient(OkHttpClient())
        for (xml in listOf("<html><body/></html>","<rss/>","<error><item><title>Fake</title></item></error>")) {
            assertThrows(Exception::class.java) { parser.parseFeed(xml,"https://$domestic/rss") }
        }
    }

    @Test fun dedicatedReaderTransportNeverUsesProxyCookiesOrCredentialInterceptors() {
        val http = newsReaderHttpClient()
        assertEquals(Proxy.NO_PROXY,http.proxy)
        assertSame(CookieJar.NO_COOKIES,http.cookieJar)
        assertTrue(http.interceptors.isEmpty())
        assertTrue(http.networkInterceptors.isEmpty())
        assertFalse(http.followSslRedirects)
        assertFalse(http.retryOnConnectionFailure)
        assertEquals(1,ReaderNewsSources.urls.size)
        assertEquals(5,ReaderNewsSources.internationalUrls.size)
        assertTrue((ReaderNewsSources.urls+ReaderNewsSources.internationalUrls).all { it.startsWith("https://") })
    }
}
