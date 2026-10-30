package com.macroresearch.data

import com.macroresearch.data.remote.NewsArticle
import com.macroresearch.ui.analysis.manualNewsMessage
import com.macroresearch.ui.analysis.reviewableNewsUrl
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class NewsReviewTest {
    private val article = NewsArticle("Fed outlook", "https://news.example/article?edition=en", "Example News", "2026-10-28T18:00:00Z", "Summary")

    @Test fun browserLinksRequireHttpsAndRejectCredentialsAndActiveSchemes() {
        assertEquals(article.url, reviewableNewsUrl(article.url))
        for (url in listOf("javascript:alert(1)", "intent://article", "file:///etc/passwd", "http://example.com", "https://user:pass@example.com/")) assertNull(reviewableNewsUrl(url))
    }

    @Test fun manualSubmissionIncludesSourceAttributionAndExplicitlyUnverifiedNotes() {
        val message = manualNewsMessage(article, "  The article discusses guidance. ", "How does this affect the prior outlook?")
        for (text in listOf(article.title, article.source, article.publishedAt!!, article.url, "not independently verified", "The article discusses guidance.", "How does this affect")) assertTrue(message.contains(text))
        assertTrue(manualNewsMessage(article, "notes", "").contains("Please reassess"))
        assertTrue(runCatching { manualNewsMessage(article, " ", "") }.isFailure)
        assertTrue(runCatching { manualNewsMessage(article, "x".repeat(6001), "") }.isFailure)
        assertTrue(runCatching { manualNewsMessage(article, "notes", "x".repeat(1001)) }.isFailure)
    }

    @Test fun newsListSurvivesEventExitAndLegacyHistoriesRemainReadable() {
        val directory = Files.createTempDirectory("news-review-history").toFile()
        try {
            val store = AiHistoryStore(directory)
            val initial = store.begin("event")
            val history = initial.copy(articles = listOf(article))
            store.save("event", history)
            store.finish("event", history, AiStepStatus.FAILED)
            assertEquals(listOf(article), AiHistoryStore(directory).load("event").articles)
            // Simulate pre-feature files without the new articles field.
            val file = directory.listFiles()!!.single()
            file.writeText("{\"session\":\"legacy\",\"steps\":[],\"requests\":[]}")
            assertTrue(AiHistoryStore(directory).load("event").articles.orEmpty().isEmpty())
            assertTrue(AiHistoryStore(directory).load("different-event").articles.orEmpty().isEmpty())
        } finally { directory.deleteRecursively() }
    }
}
