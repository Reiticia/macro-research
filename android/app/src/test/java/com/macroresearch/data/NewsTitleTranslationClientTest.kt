package com.macroresearch.data

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.macroresearch.data.remote.TranslationClient
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class NewsTitleTranslationClientTest {
    private val gson = Gson()
    private val settings = TranslationSettings(true, "https://personal.example/v1", "news-model")
    private val parser = TranslationClient(OkHttpClient(), gson)
    private val titles = listOf("Fed holds rates at 5%", "CPI is not declining")

    @Test fun everyAppLanguageUsesThePersonalEndpointWithOnlyIndexedTitles() = runBlocking {
        for ((language, target) in listOf("en" to "English", "zh-CN" to "Simplified Chinese", "zh-TW" to "Traditional Chinese")) {
            var calls = 0
            val client = TranslationClient(OkHttpClient.Builder().addInterceptor { chain ->
                calls++
                val request = chain.request()
                assertEquals("https://personal.example/v1/chat/completions", request.url.toString())
                assertEquals("Bearer personal-key", request.header("Authorization"))
                assertNull(request.header("X-Device-Id"))
                assertNull(request.header("Cookie"))
                val buffer = Buffer()
                request.body!!.writeTo(buffer)
                val payload = JsonParser.parseString(buffer.readUtf8()).asJsonObject
                assertEquals("news-model", payload["model"].asString)
                assertFalse(payload.has("tools"))
                val messages = payload["messages"].asJsonArray
                val system = messages[0].asJsonObject["content"].asString
                assertTrue(system.contains(target))
                assertTrue(system.contains("untrusted data"))
                val input = JsonParser.parseString(messages[1].asJsonObject["content"].asString).asJsonObject
                assertEquals(setOf("titles"), input.keySet())
                assertEquals(titles, input["titles"].asJsonArray.map { row ->
                    assertEquals(setOf("id", "title"), row.asJsonObject.keySet())
                    row.asJsonObject["title"].asString
                })
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("""{"choices":[{"message":{"content":"{\"translations\":[{\"id\":1,\"title\":\"译文二\"},{\"id\":0,\"title\":\"译文一\"}]}"}}]}"""
                        .toResponseBody("application/json".toMediaType())).build()
            }.build(), gson)
            assertEquals(mapOf(titles[0] to "译文一", titles[1] to "译文二"),
                client.translateNewsTitles(titles, language, settings, "personal-key"))
            assertEquals(1, calls)
        }
    }

    @Test fun parserRequiresExactIdsAndNeverAssignsOmittedOrFractionalIdsByPosition() {
        val result = parser.parseNewsTitlesResponse("""{"translations":[
            {"title":"No id"},{"id":0.5,"title":"Fractional"},{"id":"0","title":"String id"},
            {"id":99,"title":"Unknown"},{"id":1,"title":"  正确译文  "}]}""", titles)
        assertEquals(mapOf(titles[1] to "正确译文"), result)
    }

    @Test fun duplicateIdsRejectTheWholeAmbiguousResponse() {
        try {
            parser.parseNewsTitlesResponse("""{"translations":[{"id":0,"title":"One"},{"id":0,"title":"Two"}]}""", titles)
            fail("Ambiguous mapping must fail")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun blankOversizedMarkupAndNonStringTranslationsAreNotUsable() {
        val rows = listOf("", " ", "x".repeat(601), "<b>Title</b>")
        rows.forEach { text ->
            assertTrue(parser.parseNewsTitlesResponse(gson.toJson(mapOf("translations" to listOf(mapOf("id" to 0, "title" to text)))), titles).isEmpty())
        }
        assertTrue(parser.parseNewsTitlesResponse("""{"translations":[{"id":0,"title":42}]}""", titles).isEmpty())
    }

    @Test fun fencedJsonIsAcceptedWithoutChangingIds() {
        val content = "```json\n{\"translations\":[{\"id\":1,\"title\":\"物价并未下降\"}]}\n```"
        val raw = gson.toJson(mapOf("choices" to listOf(mapOf("message" to mapOf("content" to content)))))
        assertEquals(mapOf(titles[1] to "物价并未下降"), parser.parseNewsTitlesResponse(raw, titles))
    }

    @Test fun providerFailureHasNoAutomaticRetry() = runBlocking {
        var calls = 0
        val client = TranslationClient(OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(429).message("Limited")
                .body("rate limited".toResponseBody()).build()
        }.build(), gson)
        try {
            client.translateNewsTitles(titles, "zh-CN", settings, "key")
            fail("Expected failure")
        } catch (_: IOException) { }
        assertEquals(1, calls)
    }

    @Test fun oversizedResponseIsRejectedBeforeParsing() = runBlocking {
        val client = TranslationClient(OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("x".repeat(128 * 1024 + 1).toResponseBody()).build()
        }.build(), gson)
        try {
            client.translateNewsTitles(titles, "zh-TW", settings, "key")
            fail("Expected size limit")
        } catch (error: IOException) { assertTrue(error.message.orEmpty().contains("too large")) }
    }
}
