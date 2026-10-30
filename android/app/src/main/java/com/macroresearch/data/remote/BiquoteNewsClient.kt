package com.macroresearch.data.remote

import com.macroresearch.data.AiExecutionTrace
import com.macroresearch.data.AiOperation
import com.macroresearch.data.AiStepStatus
import com.macroresearch.data.AiStepDetail
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Public headline metadata only. No article pages, credentials or event data are sent upstream. */
class BiquoteNewsClient(
    client: OkHttpClient,
    private val endpoint: String = "https://biquote.io/api/news/market",
) {
    private val client = client.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    suspend fun marketNews(trace: AiExecutionTrace? = null): NewsFetchResult {
        var step: Int? = null
        var code: Int? = null
        return try {
        // English US edition is an editorial selection, not the event's country or a strict filter.
        val url = endpoint.toHttpUrl().newBuilder()
            .addQueryParameter("language", "en")
            .addQueryParameter("country", "US")
            .addQueryParameter("maxResults", "50")
            .build()
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        step = trace?.start(AiOperation.FETCH_NEWS, url.toString())
        val articles = client.newCall(request).awaitResponse { response ->
            code = response.code
            check(response.isSuccessful) { "Market news unavailable" }
            val body = checkNotNull(response.body) { "Missing market news response" }
            val source = body.source()
            source.request(MAX_BYTES + 1L)
            val bytes = source.buffer.readByteArray()
            check(bytes.size <= MAX_BYTES) { "Market news response too large" }
            parseArticles(bytes.toString(Charsets.UTF_8))
        }
        step?.let { trace?.finish(it, count = articles.size, httpStatus = code) }
        NewsFetchResult(articles, if (articles.isEmpty()) "no_relevant_articles_found" else "articles_found")
    } catch (cancelled: CancellationException) {
        step?.let { trace?.finish(it, AiStepStatus.CANCELLED, httpStatus = code) }
        throw cancelled
    } catch (_: Exception) {
        step?.let { trace?.finish(it, AiStepStatus.FAILED, AiStepDetail.PAGE_UNAVAILABLE, httpStatus = code) }
        NewsFetchResult(emptyList(), "search_unavailable")
    }
    }

    internal fun parseArticles(json: String): List<NewsArticle> {
        val root = JsonParser.parseString(json)
        check(root.isJsonArray) { "Unexpected market news payload" }
        return root.asJsonArray.take(50).mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val row = element.asJsonObject
            val title = row.text("title")?.let(::cleanNewsText)?.take(300)
                ?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val url = row.text("url")?.toHttpUrlOrNull()
                ?.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() }
                ?: return@mapNotNull null
            // Unknown publication times cannot safely be associated with an economic release.
            val published = row.text("publishedDate")?.let {
                runCatching { Instant.parse(it).toString() }.getOrNull()
            } ?: return@mapNotNull null
            NewsArticle(
                title = title,
                url = url.toString(),
                source = row.text("publisher")?.let(::cleanNewsText)?.take(120)
                    ?.takeIf(String::isNotBlank) ?: url.host,
                publishedAt = published,
                summary = row.text("description")?.let(::cleanNewsText)?.take(700)
                    ?.takeIf(String::isNotBlank),
            )
        }.distinctBy(NewsArticle::url)
    }

    private fun JsonObject.text(key: String): String? = get(key)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString?.trim()?.takeIf(String::isNotEmpty)

    companion object {
        private const val MAX_BYTES = 512 * 1024
    }
}

internal fun cleanNewsText(value: String): String = value
    .replace(Regex("<[^>]*>"), " ")
    .replace(Regex("&(?:nbsp|amp|quot|apos|lt|gt|#\\d+|#x[0-9a-fA-F]+);"), " ")
    .replace(Regex("[\\s\\u00a0]+"), " ").trim()
