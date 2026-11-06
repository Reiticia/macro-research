package com.macroresearch.data.remote

import com.macroresearch.data.model.EconomicEvent
import com.macroresearch.data.AiExecutionTrace
import com.macroresearch.data.AiOperation
import com.macroresearch.data.AiStepStatus
import com.macroresearch.data.AiStepDetail
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
import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.util.Locale
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** Minimal article metadata from RSS/Atom or BiQuote; article bodies are never fetched. */
data class NewsArticle(
    val title: String,
    val url: String,
    val source: String,
    val publishedAt: String?,
    val summary: String?,
    val archiveId: String? = null,
    val titleTranslations: Map<String, String>? = emptyMap(),
)

/** Shared server translations only; never resolve a client AI configuration here. */
internal fun safeNewsTitleTranslations(value: Map<*, *>?): Map<String, String> = listOf("en", "zh-CN", "zh-TW").mapNotNull { language ->
    val title = value?.get(language) as? String ?: return@mapNotNull null
    if (title.isBlank() || title.codePointCount(0, title.length) > 600 ||
        title.any { it.isISOControl() || it == '<' || it == '>' }) null else language to title
}.toMap()

data class NewsFetchResult(
    val articles: List<NewsArticle>,
    val status: String,
)

fun newsPublishedInstant(value: String?): Instant? = value?.let {
    runCatching { Instant.parse(it) }.getOrNull()
        ?: runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull()
}

/** Credential-free feeds shared by opt-in personal AI and the separately enabled news reader. */
class NewsRssClient(
    client: OkHttpClient,
    private val biquote: BiquoteNewsClient = BiquoteNewsClient(client),
) {
    private val client = client.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    suspend fun relevantArticles(
        event: EconomicEvent,
        sourceUrls: List<String>,
        includeBiquote: Boolean = false,
        now: Instant = Instant.now(),
        trace: AiExecutionTrace? = null,
    ): NewsFetchResult = withContext(Dispatchers.IO) {
        coroutineScope {
            val urls = sourceUrls.mapNotNull(::validatedFeedUrl).distinct().take(MAX_SOURCES)
            val tasks = urls.map { url -> async { fetchFeed(url, trace) } }.toMutableList()
            if (includeBiquote) tasks += async {
                val result = biquote.marketNews(trace)
                FeedFetch(result.articles, result.status != "search_unavailable")
            }
            val results = tasks.awaitAll()
            val articles = selectArticles(event, results.flatMap(FeedFetch::articles), now)
            val failures = results.count { !it.succeeded }
            val status = when {
                results.isEmpty() || failures == results.size -> "search_unavailable"
                failures > 0 -> "partial_results"
                articles.isEmpty() -> "no_relevant_articles_found"
                else -> "articles_found"
            }
            NewsFetchResult(articles, status)
        }
    }

    /** Explicit, user-initiated reading: not subject to AI event relevance/time filtering. */
    suspend fun latestArticles(sourceUrls: List<String>): NewsFetchResult = withContext(Dispatchers.IO) {
        coroutineScope {
            val results = sourceUrls.mapNotNull(::validatedFeedUrl).distinct().take(MAX_SOURCES)
                .map { url -> async { fetchFeed(url, null) } }.awaitAll()
            val articles = results.flatMap(FeedFetch::articles).distinctBy(NewsArticle::url)
                .sortedByDescending { article -> newsPublishedInstant(article.publishedAt) ?: Instant.MIN }
                .take(160)
            NewsFetchResult(articles, when {
                results.isEmpty() || results.none(FeedFetch::succeeded) -> "search_unavailable"
                results.any { !it.succeeded } -> "partial_results"
                articles.isEmpty() -> "no_articles_found"
                else -> "articles_found"
            })
        }
    }

    internal fun selectArticles(event: EconomicEvent, articles: List<NewsArticle>, now: Instant): List<NewsArticle> {
        // The endpoint is a latest-news feed, not a historical search. Never attach today's
        // headlines to an old event, future-dated articles, or unknown publication times.
        if (!event.timeExact) return emptyList()
        val time = runCatching { Instant.parse(event.eventTime) }.getOrNull() ?: return emptyList()
        if (time.isAfter(now)) return emptyList()
        val start = time.minus(Duration.ofHours(24))
        val end = minOf(now, time.plus(Duration.ofHours(24)))
        return articles.mapNotNull { article ->
            val published = newsPublishedInstant(article.publishedAt) ?: return@mapNotNull null
            if (published < start || published > end) return@mapNotNull null
            relevance(event, article)?.let { Triple(it, published, article) }
        }.sortedWith(compareByDescending<Triple<Int, Instant, NewsArticle>> { it.first }.thenByDescending { it.second })
            .map { it.third }
            .distinctBy(NewsArticle::url)
            .take(MAX_ARTICLES)
    }

    private data class FeedFetch(val articles: List<NewsArticle>, val succeeded: Boolean)

    private suspend fun fetchFeed(url: String, trace: AiExecutionTrace?): FeedFetch {
        val step = trace?.start(AiOperation.FETCH_NEWS, url)
        var code: Int? = null
        val request = Request.Builder().url(url).header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml").get().build()
        return try {
            client.newCall(request).awaitResponse { response ->
                code = response.code
                if (!response.isSuccessful) return@awaitResponse FeedFetch(emptyList(), false)
                val body = response.body ?: return@awaitResponse FeedFetch(emptyList(), false)
                val source = body.source()
                source.request(MAX_FEED_BYTES + 1L)
                val bytes = source.buffer.readByteArray()
                if (bytes.size > MAX_FEED_BYTES) return@awaitResponse FeedFetch(emptyList(), false)
                FeedFetch(parseFeed(bytes, url), true)
            }.also { result -> step?.let { trace.finish(it, if (result.succeeded) AiStepStatus.SUCCEEDED else AiStepStatus.FAILED, count = result.articles.size, httpStatus = code) } }
        } catch (cancelled: CancellationException) {
            step?.let { trace.finish(it, AiStepStatus.CANCELLED, httpStatus = code) }
            throw cancelled
        } catch (_: Exception) {
            step?.let { trace.finish(it, AiStepStatus.FAILED, AiStepDetail.PAGE_UNAVAILABLE, httpStatus = code) }
            FeedFetch(emptyList(), false)
        }
    }

    internal fun parseFeed(xml: String, feedUrl: String): List<NewsArticle> =
        parseFeed(InputSource(StringReader(xml)), feedUrl)

    // Let the XML parser honor the declaration (e.g. GBK), rather than corrupting Chinese text.
    internal fun parseFeed(xml: ByteArray, feedUrl: String): List<NewsArticle> =
        parseFeed(InputSource(ByteArrayInputStream(xml)), feedUrl)

    private fun parseFeed(input: InputSource, feedUrl: String): List<NewsArticle> {
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
        val document = builder.parse(input)
        val root = document.documentElement.localName ?: document.documentElement.tagName
        require(root in setOf("rss", "RDF", "feed")) { "Not a news feed" }
        if (root == "rss") require(document.getElementsByTagName("channel").length > 0)
        val entries = document.getElementsByTagName("item").let { rssItems ->
            if (rssItems.length > 0) rssItems else document.getElementsByTagNameNS("*", "entry")
        }
        return (0 until entries.length).take(MAX_FEED_ARTICLES).mapNotNull { index ->
            val entry = entries.item(index) as? Element ?: return@mapNotNull null
            val title = cleanMarkup(childText(entry, "title"))
            val linkElement = (0 until entry.childNodes.length).mapNotNull { entry.childNodes.item(it) as? Element }
                .firstOrNull { (it.localName ?: it.tagName) == "link" && it.getAttribute("rel") in listOf("", "alternate") }
            val link = linkElement?.getAttribute("href")?.takeIf(String::isNotBlank)
                ?: linkElement?.textContent.orEmpty().trim()
            val validLink = link.toHttpUrlOrNull()?.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() }?.toString()
                ?: return@mapNotNull null
            if (title.isBlank()) return@mapNotNull null
            val host = validLink.toHttpUrlOrNull()?.host.orEmpty()
            val fallbackHost = feedUrl.toHttpUrlOrNull()?.host.orEmpty()
            val source = cleanMarkup(childText(entry, "source")).ifBlank { host.ifBlank { fallbackHost } }.take(120)
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
        if ("fomc" in eventName || "fed funds" in eventName ||
            "interest rate" in eventName && event.country == "United States") terms += setOf("federal reserve", "fed")
        if ("pce" in eventName || "personal consumption expenditure" in eventName) terms += setOf("pce", "inflation")
        if ("nonfarm" in eventName || "payroll" in eventName || "unemployment" in eventName) terms += setOf("jobs", "employment")
        if ("gdp" in eventName || "gross domestic product" in eventName) terms += setOf("growth", "economy")
        // Google News descriptions may concatenate other publishers' headlines. Only match
        // the actual title; a keyword buried in an aggregate description is not evidence.
        val text = article.title.lowercase(Locale.ROOT)
        val score = terms.count { term -> Regex("(?<![\\p{L}\\p{N}])${Regex.escape(term)}(?![\\p{L}\\p{N}])").containsMatchIn(text) }
        return score.takeIf { it > 0 }
    }

    private fun validatedFeedUrl(value: String): String? = value.trim().toHttpUrlOrNull()
        ?.takeIf { it.isHttps && it.host.isNotBlank() }
        ?.toString()

    private fun cleanMarkup(value: String): String = org.jsoup.Jsoup.parse(value).text().trim()

    companion object {
        private const val MAX_SOURCES = 8
        private const val MAX_FEED_BYTES = 512 * 1024
        private const val MAX_FEED_ARTICLES = 40
        private const val MAX_ARTICLES = 8
        private const val MAX_TITLE_CHARS = 300
        private const val MAX_SUMMARY_CHARS = 700
        private const val MAX_DATE_CHARS = 100
        private val WORD_BREAKS = Regex("[^\\p{L}\\p{N}]+")
        private val STOP_WORDS = setOf("the", "and", "for", "with", "from", "rate", "rates", "index", "mom", "yoy", "month", "year", "annual", "weekly", "monthly", "quarterly", "final", "preliminary", "change", "total", "core", "us", "united", "states")
    }
}
