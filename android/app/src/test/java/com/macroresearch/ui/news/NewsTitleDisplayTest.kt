package com.macroresearch.ui.news

import com.macroresearch.data.ReaderHeadlines
import com.macroresearch.data.remote.NewsArticle
import com.macroresearch.ui.settings.AppLanguage
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class NewsTitleDisplayTest {
    private val article = NewsArticle("Original headline", "https://publisher.example/report", "Source", null, "Original summary")

    @Test fun selectedAppLanguageUsesServerTitlesWithoutAnyPersonalAiConfiguration() {
        val translated = article.copy(titleTranslations = mapOf("en" to "English headline", "zh-CN" to "简体标题", "zh-TW" to "繁體標題"))
        val state = NewsDeskState(backendNews = true, headlines = ReaderHeadlines(listOf(translated)))
        for ((tag, expected) in listOf("en-US" to "English headline", "zh-CN" to "简体标题", "zh-TW" to "繁體標題", "zh-Hant-HK" to "繁體標題")) {
            val language = AppLanguage.fromLocale(Locale.forLanguageTag(tag))
            assertEquals(expected, state.title(article, language.tag))
        }
    }

    @Test fun missingTranslationOrChangedOriginalFallsBackWithoutMutatingArticleOrAiInput() {
        val translated = article.copy(titleTranslations = mapOf("zh-CN" to "简体标题"))
        val state = NewsDeskState(backendNews = true, headlines = ReaderHeadlines(listOf(translated)), article = article)
        assertEquals(article.title, state.title(article, "en"))
        assertEquals(article.title, state.title(article, "zh-TW"))
        val revised = article.copy(title = "Updated original")
        assertEquals(revised.title, state.title(revised, "zh-CN"))
        assertEquals("Original headline", state.article!!.title)
        assertEquals("Original summary", state.headlines.articles.single().summary)
        assertEquals(article.url, state.article.url)
    }

    @Test fun directModeDoesNotUseBackendTranslationsOrTriggerAClientTranslator() {
        val translated = article.copy(titleTranslations = mapOf("zh-CN" to "简体标题"))
        val state = NewsDeskState(backendNews = false, headlines = ReaderHeadlines(listOf(translated)))
        assertEquals(article.title, state.title(translated, "zh-CN"))
    }
}
