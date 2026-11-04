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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** This catalog and its consent are independent of the AI-context news switches. */
object ReaderNewsSources {
    val urls = listOf(
        "https://www.ftchinese.com/rss/feed",
        "https://feeds.content.dowjones.io/public/rss/mw_topstories",
        "https://feeds.bbci.co.uk/news/business/rss.xml",
        "https://rss.dw.com/rdf/rss-en-bus",
        "https://www.federalreserve.gov/feeds/press_all.xml",
    )

    fun label(source: String): String = when {
        source.contains("ftchinese.com") -> "FT中文网"
        source.contains("marketwatch.com") -> "MarketWatch"
        source.contains("bbc.co.") || source.contains("bbc.com") -> "BBC Business"
        source.contains("dw.com") -> "DW Business"
        source.contains("federalreserve.gov") -> "Federal Reserve"
        else -> source
    }
}

data class ReaderHeadlines(
    val articles: List<NewsArticle> = emptyList(),
    val fetchedAt: String? = null,
    val status: String = "cached",
)

/** Metadata only, bounded, atomic, app-private and excluded from backup by its parent directory. */
class NewsReaderCache(private val file: File, private val gson: Gson = Gson()) {
    fun load(): ReaderHeadlines = runCatching {
        if (!file.isFile || file.length() > MAX_BYTES) return ReaderHeadlines()
        val cached = gson.fromJson(file.readText(), ReaderHeadlines::class.java)
        ReaderHeadlines(sanitize(cached.articles), cached.fetchedAt, "cached")
    }.getOrDefault(ReaderHeadlines())

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
    }

    private fun sanitize(articles: List<NewsArticle>): List<NewsArticle> = articles.take(160)
        .filter { it.title.isNotBlank() && ArticlePreviewClient.safeArticleUrl(it.url) != null }
        .map { it.copy(title = it.title.take(300), source = it.source.take(120),
            summary = it.summary?.take(700), publishedAt = it.publishedAt?.take(100)) }
        .distinctBy(NewsArticle::url)

    companion object { private const val MAX_BYTES = 1024 * 1024 }
}

class NewsReaderRepository(
    private val rss: NewsRssClient,
    private val pages: ArticlePreviewClient,
    private val cache: NewsReaderCache,
) {
    private val mutex = Mutex()

    suspend fun cached(): ReaderHeadlines = withContext(Dispatchers.IO) { cache.load() }

    suspend fun refresh(): ReaderHeadlines = mutex.withLock {
        val result: NewsFetchResult = rss.latestArticles(ReaderNewsSources.urls)
        if (result.status == "search_unavailable") {
            return@withLock cached().copy(status = result.status)
        }
        val headlines = ReaderHeadlines(result.articles, Instant.now().toString(), result.status)
        withContext(Dispatchers.IO) { runCatching { cache.save(headlines) } }
        headlines
    }

    suspend fun read(article: NewsArticle): ArticleReading? = withContext(Dispatchers.IO) { pages.read(article.url) }
}
