package com.macroresearch.data.remote

import com.macroresearch.data.model.EconomicEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Minimal metadata from an RSS/Atom article; article bodies are never fetched. */
data class NewsArticle(
    val title: String,
    val url: String,
    val source: String,
    val publishedAt: String?,
    val summary: String?,
)

data class NewsFetchResult(
    val articles: List<NewsArticle>,
    val status: String,
)

/** Reads user-selected RSS feeds and returns a small, event-relevant set for a direct AI request. */
class NewsRssClient(client: OkHttpClient) {
    private val client = client.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun relevantArticles(event: EconomicEvent, sourceUrls: List<String>): NewsFetchResult =
        withContext(Dispatchers.IO) {
            coroutineScope {
                val urls = sourceUrls.mapNotNull(::validatedFeedUrl).distinct().take(MAX_SOURCES)
                val results = urls.map { url -> async { fetchFeed(url) } }.awaitAll()
                val articles = results.flatMap(FeedFetch::articles)
                    .mapNotNull { article -> relevance(event, article)?.let { it to article } }
                    .sortedByDescending { it.first }
                    .map { it.second }
                    .distinctBy(NewsArticle::url)
                    .take(MAX_ARTICLES)
                val failures = results.count { !it.succeeded }
                val status = when {
                    urls.isEmpty() || failures == results.size -> "search_unavailable"
                    failures > 0 -> "partial_results"
                    articles.isEmpty() -> "no_relevant_articles_found"
                    else -> "articles_found"
                }
                NewsFetchResult(articles, status)
            }
        }

    private data class FeedFetch(val articles: List<NewsArticle>, val succeeded: Boolean)

    private fun fetchFeed(url: String): FeedFetch {
        val request = Request.Builder().url(url).header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml").get().build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return FeedFetch(emptyList(), false)
                val body = response.body ?: return FeedFetch(emptyList(), false)
                val bytes = body.source().readByteArray(MAX_FEED_BYTES + 1L)
                if (bytes.size > MAX_FEED_BYTES) return FeedFetch(emptyList(), false)
                FeedFetch(parseFeed(bytes.toString(Charsets.UTF_8), url), true)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            FeedFetch(emptyList(), false)
        }
    }

    internal fun parseFeed(xml: String, feedUrl: String): List<NewsArticle> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isXIncludeAware = false
            isExpandEntityReferences = false
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
        }
        val builder = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> InputSource(StringReader("")) }
        }
        val document = builder.parse(InputSource(StringReader(xml)))
        val entries = document.getElementsByTagName("item").let { rssItems ->
            if (rssItems.length > 0) rssItems else document.getElementsByTagNameNS("*", "entry")
        }
        return (0 until entries.length).take(MAX_FEED_ARTICLES).mapNotNull { index ->
            val entry = entries.item(index) as? Element ?: return@mapNotNull null
            val title = cleanMarkup(childText(entry, "title"))
            val linkElement = childElement(entry, "link")
            val link = linkElement?.getAttribute("href")?.takeIf(String::isNotBlank)
                ?: linkElement?.textContent.orEmpty().trim()
            val validLink = link.toHttpUrlOrNull()?.takeIf { it.isHttps }?.toString()
                ?: return@mapNotNull null
            if (title.isBlank()) return@mapNotNull null
            val host = validLink.toHttpUrlOrNull()?.host.orEmpty()
            val fallbackHost = feedUrl.toHttpUrlOrNull()?.host.orEmpty()
            val source = cleanMarkup(childText(entry, "source")).ifBlank { host.ifBlank { fallbackHost } }
            val published = sequenceOf("pubDate", "published", "updated", "date")
                .map { childText(entry, it) }
                .firstOrNull(String::isNotBlank)
            val summary = sequenceOf("description", "summary", "encoded", "content")
                .map { childText(entry, it) }
                .firstOrNull(String::isNotBlank)
            NewsArticle(
                title = title.take(MAX_TITLE_CHARS),
                url = validLink,
                source = source,
                publishedAt = published?.take(MAX_DATE_CHARS),
                summary = summary?.let(::cleanMarkup)?.take(MAX_SUMMARY_CHARS)?.ifBlank { null },
            )
        }
    }

    private fun childText(parent: Element, wanted: String): String =
        childElement(parent, wanted)?.textContent?.trim().orEmpty()

    private fun childElement(parent: Element, wanted: String): Element? {
        val children = parent.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index) as? Element ?: continue
            val localName = (child.localName ?: child.tagName.substringAfter(':')).lowercase(Locale.ROOT)
            if (localName == wanted.lowercase(Locale.ROOT)) return child
        }
        return null
    }

    private fun relevance(event: EconomicEvent, article: NewsArticle): Int? {
        val eventName = event.event.lowercase(Locale.ROOT)
        val terms = eventName.split(WORD_BREAKS)
            .filter { it.length >= 3 && it !in STOP_WORDS }
            .toMutableSet()
        if ("cpi" in eventName || "consumer price" in eventName) terms += setOf("inflation", "prices")
        if ("ppi" in eventName || "producer price" in eventName) terms += setOf("inflation", "producer")
        if ("fomc" in eventName || "fed funds" in eventName || "interest rate" in eventName) terms += setOf("federal reserve", "fed", "rates")
        if ("nonfarm" in eventName || "payroll" in eventName || "unemployment" in eventName) terms += setOf("jobs", "employment")
        if ("gdp" in eventName || "gross domestic product" in eventName) terms += setOf("growth", "economy")
        val text = "${article.title} ${article.summary.orEmpty()}".lowercase(Locale.ROOT)
        val score = terms.count { text.contains(it) }
        return score.takeIf { it > 0 }
    }

    private fun validatedFeedUrl(value: String): String? = value.trim().toHttpUrlOrNull()
        ?.takeIf { it.isHttps && it.host.isNotBlank() }
        ?.toString()

    private fun cleanMarkup(value: String): String =
        value.replace(HTML_TAGS, " ").replace(HTML_ENTITIES, " ").replace(WHITESPACE, " ").trim()

    companion object {
        private const val MAX_SOURCES = 8
        private const val MAX_FEED_BYTES = 512 * 1024
        private const val MAX_FEED_ARTICLES = 40
        private const val MAX_ARTICLES = 8
        private const val MAX_TITLE_CHARS = 300
        private const val MAX_SUMMARY_CHARS = 700
        private const val MAX_DATE_CHARS = 100
        private val WORD_BREAKS = Regex("[^\\p{L}\\p{N}]+")
        private val WHITESPACE = Regex("\\s+")
        private val HTML_TAGS = Regex("<[^>]*>")
        private val HTML_ENTITIES = Regex("&(?:nbsp|amp|quot|apos|lt|gt|#\\d+|#x[0-9a-fA-F]+);")
        private val STOP_WORDS = setOf("the", "and", "for", "with", "from", "rate", "rates", "index", "mom", "yoy", "month", "year", "annual", "weekly", "monthly", "quarterly", "final", "preliminary", "change", "total", "core", "us", "united", "states")
    }
}
