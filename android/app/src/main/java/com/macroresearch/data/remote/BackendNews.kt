package com.macroresearch.data.remote

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.macroresearch.data.ReaderHeadlines
import com.macroresearch.data.ReaderSourceStatus
import java.time.Duration
import java.time.Instant

internal fun decodeReaderNews(root: JsonObject, gson: Gson, now: Instant = Instant.now()): ReaderHeadlines {
    require(root.get("stale")?.asJsonPrimitive?.isBoolean == true && root.get("partial")?.asJsonPrimitive?.isBoolean == true)
    require(root.get("articles")?.isJsonArray == true && root.get("sources")?.isJsonArray == true)
    require(root.getAsJsonArray("articles").size() <= 100 && root.getAsJsonArray("sources").size() in 1..6)
    val updated = root.get("updatedAt").asString.also { Instant.parse(it) }
    val sources: List<ReaderSourceStatus> = gson.fromJson(root.get("sources"), object : TypeToken<List<ReaderSourceStatus>>() {}.type)
    require(sources.map { it.id }.distinct().size == sources.size)
    sources.forEach {
        require(it.id.matches(Regex("[a-z][a-z0-9_-]{0,31}")) && it.name.isNotBlank() && it.name.length <= 120)
        require(it.status in setOf("fresh","stale","unavailable"))
        it.updatedAt?.let(Instant::parse)
    }
    root.getAsJsonArray("articles").forEach { item ->
        require(sources.any { it.id == item.asJsonObject.get("feedId")?.asString })
    }
    val rows: List<NewsArticle> = gson.fromJson(root.get("articles"), object : TypeToken<List<NewsArticle>>() {}.type)
    rows.forEach { require(it.archiveId == null || it.archiveId.matches(Regex("[0-9a-f]{64}"))) }
    val articles = rows.filter { row ->
        val time = newsPublishedInstant(row.publishedAt)
        row.title.isNotBlank() && row.source.isNotBlank() && row.url.length <= 4096 &&
            ArticlePreviewClient.safeArticleUrl(row.url) != null &&
            time != null && time <= now && time >= now.minus(Duration.ofDays(30))
    }.map { it.copy(title = it.title.take(300), source = it.source.take(120), summary = it.summary?.take(700)) }
        .sortedByDescending { newsPublishedInstant(it.publishedAt) }.distinctBy { it.url }
    return ReaderHeadlines(articles,updated, when {
        root.get("partial").asBoolean -> "partial_results"
        articles.isEmpty() -> "no_articles_found"
        else -> "articles_found"
    },sources,root.get("stale").asBoolean,root.get("nextOffset")?.takeUnless { it.isJsonNull }?.asInt?.also { require(it in 1..100_000) })
}

internal fun decodeArchivedReading(root: JsonObject, article: NewsArticle): ArticleReading? {
    require(root.get("archiveId").asString == article.archiveId && root.get("url").asString == article.url)
    val status = root.get("bodyStatus").asString
    require(status in setOf("pending","available","unavailable"))
    if (status != "available") return null
    val text = root.get("text").asString
    require(text.isNotBlank() && text.codePointCount(0,text.length) <= 64_000)
    val url = root.get("url").asString
    require(ArticlePreviewClient.safeArticleUrl(url) != null)
    val title = root.get("title").asString.also { require(it.isNotBlank() && it.length <= 600) }
    require(root.get("truncated")?.asJsonPrimitive?.isBoolean == true)
    val time = root.get("fetchedAt").asString.also { Instant.parse(it) }
    return ArticleReading(url,title,text,root.get("truncated").asBoolean,time)
}
