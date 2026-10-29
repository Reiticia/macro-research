package com.macroresearch.data

import com.google.gson.Gson
import com.macroresearch.data.remote.ArticlePreviewClient
import com.macroresearch.data.remote.GoogleNewsResolver
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class GoogleNewsPreviewTest {
    private val url = "https://news.google.com/rss/articles/CBMfixture?oc=5"
    private val shell = "<html><title>Google News</title>" + "<!-- shell -->".repeat(44000) +
        "<div data-n-a-id='CBMfixture' data-n-a-ts='1793259099' data-n-a-sg='fixture-signature'></div></html>"
    private fun rpc(destination: String): String = ")]}'\n\n" + Gson().toJson(listOf(listOf("wrb.fr", "Fbv4je", Gson().toJson(listOf("garturlres", destination, 1)))))

    @Test fun resolvesLargeGoogleShellBeforeReadingPublisherTextAndRecordsAllRequests() = runBlocking {
        var steps = emptyList<AiExecutionStep>()
        val requests = mutableListOf<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request.url.toString()
            val (body, mime) = when (requests.size) {
                1 -> shell to "text/html"
                2 -> {
                    assertEquals("POST", request.method)
                    val buffer = okio.Buffer(); request.body!!.writeTo(buffer)
                    val payload = buffer.readUtf8()
                    assertTrue(payload.contains("Fbv4je"))
                    assertTrue(payload.contains("CBMfixture"))
                    assertNull(request.header("Cookie"))
                    assertNull(request.header("Authorization"))
                    rpc("https://publisher.example/story") to "application/json"
                }
                else -> "<html><title>Policy report</title><article>${"Federal Reserve announcement. ".repeat(20)}</article></html>" to "text/html"
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody(mime.toMediaType())).build()
        }.build()
        val result = ArticlePreviewClient(http).preview(url, AiExecutionTrace { steps = it })
        assertEquals("https://publisher.example/story", result["url"])
        assertEquals("Policy report", result["title"])
        assertFalse(result["text"].toString().contains("Google News"))
        assertEquals(3, requests.size)
        assertTrue(steps.any { it.operation == AiOperation.RESOLVE_ARTICLE && it.status == AiStepStatus.SUCCEEDED })
        assertEquals(requests.toSet(), steps.mapNotNull { it.url }.toSet())
    }

    @Test fun publisher403IsNotMisreportedAsSafetyBlockOrArticleText() = runBlocking {
        var count = 0
        var steps = emptyList<AiExecutionStep>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            count++
            val body = when (count) { 1 -> shell; 2 -> rpc("https://publisher.example/denied"); else -> "Access denied" }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(if (count == 3) 403 else 200).message("fixture")
                .body(body.toResponseBody((if (count == 2) "application/json" else "text/html").toMediaType())).build()
        }.build()
        val result = ArticlePreviewClient(http).preview(url, AiExecutionTrace { steps = it })
        assertTrue(result.containsKey("error"))
        assertFalse(result.containsKey("text"))
        assertEquals(403, steps.last().httpStatus)
        assertEquals(AiStepDetail.PAGE_HTTP_ERROR, steps.last().detail)
        assertEquals("https://publisher.example/denied", steps.last().url)
    }

    @Test fun resolverRejectsPrivateDestinationsIdentityMismatchAndUnrelatedRpc() {
        val request = GoogleNewsResolver.request(url.toHttpUrl(), shell)
        assertEquals("news.google.com", request.url.host)
        assertTrue(runCatching { GoogleNewsResolver.request(url.toHttpUrl(), shell.replace("CBMfixture", "wrong-id")) }.isFailure)
        for (destination in listOf("http://example.com/", "https://127.0.0.1/private", "https://user:pass@example.com/", "https://news.google.com/rss/articles/loop")) {
            assertTrue(runCatching { GoogleNewsResolver.publisherUrl(rpc(destination)) }.isFailure)
        }
        assertTrue(runCatching { GoogleNewsResolver.publisherUrl(rpc("https://example.com").replace("Fbv4je", "other")) }.isFailure)
        assertEquals("publisher.example", GoogleNewsResolver.publisherUrl(")]}'\n\n123\n" + rpc("https://publisher.example/story").substringAfter("\n\n")).host)
    }
}
