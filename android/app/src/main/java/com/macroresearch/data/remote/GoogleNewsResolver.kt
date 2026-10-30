package com.macroresearch.data.remote

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.Request
import org.jsoup.Jsoup

/** Google's public article handoff. Fixed first-party endpoint, never executes page scripts. */
internal object GoogleNewsResolver {
    private val gson = Gson()
    fun isArticle(url: HttpUrl): Boolean = url.host == "news.google.com" &&
        (url.encodedPath.startsWith("/rss/articles/") || url.encodedPath.startsWith("/articles/"))

    fun request(url: HttpUrl, html: String): Request {
        check(isArticle(url)) { "Not a Google News article" }
        val id = url.pathSegments.last()
        check(id.length in 1..2048 && id.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid Google article identifier" }
        val element = Jsoup.parse(html).selectFirst("[data-n-a-id][data-n-a-ts][data-n-a-sg]")
            ?: error("Google News resolver metadata unavailable")
        check(element.attr("data-n-a-id") == id) { "Google article identity mismatch" }
        val timestamp = element.attr("data-n-a-ts").toLongOrNull() ?: error("Missing Google timestamp")
        val signature = element.attr("data-n-a-sg")
        check(signature.length in 1..512) { "Missing Google signature" }
        val request = listOf("garturlreq", listOf(
            listOf("en-US", "US", listOf("FINANCE_TOP_INDICES", "WEB_TEST_1_0_0"), null, null, 1, 1,
                "US:en", null, 180, null, null, null, null, null, 0, null, null, listOf(1608992183, 723341000)),
            "en-US", "US", 1, listOf(2, 3, 4, 8), 1, 0, "655000234", 0, 0, null, 0,
        ), id, timestamp, signature)
        val envelope = listOf(listOf(listOf("Fbv4je", gson.toJson(request), null, "generic")))
        return Request.Builder()
            .url("https://news.google.com/_/DotsSplashUi/data/batchexecute?rpcids=Fbv4je")
            .header("Accept", "application/json")
            .post(FormBody.Builder().add("f.req", gson.toJson(envelope)).build()).build()
    }

    fun publisherUrl(body: String): HttpUrl {
        // Tolerate Google's XSSI prefix and optional frame-length lines; match the exact RPC.
        fun find(value: JsonElement, depth: Int = 0): HttpUrl? {
            if (depth > 8 || !value.isJsonArray) return null
            val array = value.asJsonArray
            if (array.size() >= 3 && array[0].isJsonPrimitive && array[0].asString == "wrb.fr" &&
                array[1].isJsonPrimitive && array[1].asString == "Fbv4je" && array[2].isJsonPrimitive) {
                val result = runCatching { JsonParser.parseString(array[2].asString).asJsonArray }.getOrNull()
                if (result != null && result.size() >= 2 && result[0].asString == "garturlres") {
                    return ArticlePreviewClient.safeArticleUrl(result[1].asString)?.takeUnless { it.host == "news.google.com" }
                }
            }
            return array.firstNotNullOfOrNull { find(it, depth + 1) }
        }
        val json = body.removePrefix(")]}'").trim()
        runCatching { find(JsonParser.parseString(json)) }.getOrNull()?.let { return it }
        for (line in json.lineSequence().filter { it.trimStart().startsWith("[") }) {
            runCatching { find(JsonParser.parseString(line)) }.getOrNull()?.let { return it }
        }
        error("Google News did not return a safe publisher URL")
    }
}
