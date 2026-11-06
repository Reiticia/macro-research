package com.macroresearch.data

import com.google.gson.Gson
import com.macroresearch.data.remote.ArticlePreviewClient
import com.macroresearch.data.remote.ArticleReading
import com.macroresearch.data.remote.NewsArticle
import com.macroresearch.data.remote.NewsFetchResult
import com.macroresearch.data.remote.NewsRssClient
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.security.MessageDigest
import java.time.Duration
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Dedicated reader transport: never inherit calendar/backend/AI headers or a system HTTP proxy. */
internal fun newsReaderHttpClient() = okhttp3.OkHttpClient.Builder()
    .proxy(java.net.Proxy.NO_PROXY).cookieJar(okhttp3.CookieJar.NO_COOKIES)
    .followSslRedirects(false).retryOnConnectionFailure(false).build()

/** This catalog and its consent are independent of the AI-context news switches. */
object ReaderNewsSources {
    // Domestic public feed first: blocked international hosts must not delay ordinary reading.
    val urls = listOf("https://www.chinanews.com.cn/rss/finance.xml")
    val internationalUrls = listOf(
        "https://www.ftchinese.com/rss/feed",
        "https://feeds.content.dowjones.io/public/rss/mw_topstories",
        "https://feeds.bbci.co.uk/news/business/rss.xml",
        "https://rss.dw.com/rdf/rss-en-bus",
        "https://www.federalreserve.gov/feeds/press_all.xml",
    )

    fun label(source: String): String = when {
        source.contains("chinanews.com") -> "中新网财经"
        source.contains("ftchinese.com") -> "FT中文网"
        source.contains("marketwatch.com") -> "MarketWatch"
        source.contains("bbc.co.") || source.contains("bbc.com") -> "BBC Business"
        source.contains("dw.com") -> "DW Business"
        source.contains("federalreserve.gov") -> "Federal Reserve"
        else -> source
    }
}

data class ReaderSourceStatus(val id: String, val name: String, val status: String, val updatedAt: String?)

data class ReaderHeadlines(
    val articles: List<NewsArticle> = emptyList(),
    val fetchedAt: String? = null,
    val status: String = "cached",
    val sources: List<ReaderSourceStatus> = emptyList(),
    val stale: Boolean = false,
    val nextOffset: Int? = null,
)

/** Metadata only, bounded, atomic, app-private and excluded from backup by its parent directory. */
class NewsReaderCache(private val file: File, private val gson: Gson = Gson()) {
    fun load(): ReaderHeadlines = runCatching {
        if (!file.isFile || file.length() > MAX_BYTES) return ReaderHeadlines()
        val cached = gson.fromJson(file.readText(), ReaderHeadlines::class.java)
        val sources = cached.sources.orEmpty().take(6).filter {
            it.id.matches(Regex("[a-z][a-z0-9_-]{0,31}")) && it.name.isNotBlank() && it.name.length <= 120 &&
                it.status in setOf("fresh", "stale", "unavailable")
        }
        ReaderHeadlines(sanitize(cached.articles), cached.fetchedAt, "cached", sources, true)
    }.getOrDefault(ReaderHeadlines())

    fun scoped(key: String): NewsReaderCache {
        require(key.matches(Regex("[a-z0-9-]+")))
        return if (key == "direct") this else NewsReaderCache(File(file.parentFile, "headlines-$key.json"), gson)
    }

    fun save(value: ReaderHeadlines) {
        val text = gson.toJson(value.copy(articles = sanitize(value.articles)))
        require(text.toByteArray().size <= MAX_BYTES)
        file.parentFile?.mkdirs()
        val temp = File(file.path + ".tmp")
        temp.writeText(text)
        try {
            java.nio.file.Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        file.parentFile?.listFiles()?.filter { it.name.startsWith("headlines-backend-") && it.extension == "json" }
            ?.sortedByDescending { it.lastModified() }?.drop(8)?.forEach { it.delete() }
    }

    private fun sanitize(articles: List<NewsArticle>): List<NewsArticle> = articles.take(160)
        .filter { it.title.isNotBlank() && ArticlePreviewClient.safeArticleUrl(it.url) != null }
        .map { it.copy(title = it.title.take(300), source = it.source.take(120),
            summary = it.summary?.take(700), publishedAt = it.publishedAt?.take(100),
            titleTranslations = com.macroresearch.data.remote.safeNewsTitleTranslations(it.titleTranslations)) }
        .distinctBy(NewsArticle::url)

    companion object { private const val MAX_BYTES = 4 * 1024 * 1024 }
}

class NewsReaderRepository(
    private val rss: NewsRssClient,
    private val pages: ArticlePreviewClient,
    private val cache: NewsReaderCache,
    val settings: StateFlow<BackendSettings> = MutableStateFlow(BackendSettings()),
    private val backend: (suspend () -> ReaderHeadlines)? = null,
    private val backendRead: (suspend (NewsArticle) -> ArticleReading?)? = null,
    private val backendPage: (suspend (Int) -> ReaderHeadlines)? = null,
) {
    private val mutex = Mutex()

    private fun key(context: BackendSettings) = if (context.mode == DataSourceMode.DIRECT) "direct" else "backend-" +
        MessageDigest.getInstance("SHA-256").digest(context.baseUrl.trim().trimEnd('/').toByteArray()).joinToString("") { "%02x".format(it) }

    suspend fun cached(): ReaderHeadlines = withContext(Dispatchers.IO) {
        val context = settings.value
        val result = cache.scoped(key(context)).load()
        if (settings.value != context) throw CancellationException("News source changed")
        if (context.mode == DataSourceMode.BACKEND && result.fetchedAt?.let {
            runCatching { Duration.between(Instant.parse(it), Instant.now()).seconds in 0..86400 }.getOrDefault(false)
        } != true) ReaderHeadlines() else result
    }

    suspend fun refresh(includeInternational: Boolean = false): ReaderHeadlines = mutex.withLock {
        val context = settings.value
        if (context.mode == DataSourceMode.BACKEND) {
            check(context.configured) { "Configure the backend address and Key first" }
            val result = checkNotNull(backend) { "Backend news is not configured" }.invoke()
            if (settings.value != context) throw CancellationException("News source changed")
            withContext(Dispatchers.IO) { runCatching { cache.scoped(key(context)).save(result) } }
            if (settings.value != context) throw CancellationException("News source changed")
            return@withLock result
        }
        val primary: NewsFetchResult = rss.latestArticles(ReaderNewsSources.urls +
            if (includeInternational) ReaderNewsSources.internationalUrls else emptyList())
        val result = if (!includeInternational && primary.status == "search_unavailable") {
            val fallback = rss.latestArticles(ReaderNewsSources.internationalUrls)
            if (fallback.status == "search_unavailable") primary
            else fallback.copy(status = "partial_results")
        } else primary
        if (result.status == "search_unavailable") {
            return@withLock cached().copy(status = result.status)
        }
        if (settings.value != context) throw CancellationException("News source changed")
        val headlines = ReaderHeadlines(result.articles, Instant.now().toString(), result.status)
        withContext(Dispatchers.IO) { runCatching { cache.save(headlines) } }
        if (settings.value != context) throw CancellationException("News source changed")
        headlines
    }

    suspend fun more(offset: Int): ReaderHeadlines {
        val context = settings.value
        check(context.mode == DataSourceMode.BACKEND && context.configured)
        val result = checkNotNull(backendPage).invoke(offset)
        if (settings.value != context) throw CancellationException("News source changed")
        return result
    }

    suspend fun read(article: NewsArticle): ArticleReading? = withContext(Dispatchers.IO) {
        val context = settings.value
        val result = if (context.mode == DataSourceMode.BACKEND) {
            check(context.configured)
            backendRead?.invoke(article)
        } else pages.read(article.url)
        if (settings.value != context) throw CancellationException("News source changed")
        result
    }
}
