package com.macroresearch.data

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Opt-in news context for personally configured, direct AI briefings. */
data class NewsSettings(
    val enabled: Boolean = false,
    val sourceUrls: List<String> = NewsPreferences.DEFAULT_NEWS_SOURCES,
    val biquoteEnabled: Boolean = false,
    val articlePreviewEnabled: Boolean = false,
)

class NewsPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<NewsSettings> = _settings.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(ENABLED_KEY, enabled).apply()
        _settings.value = _settings.value.copy(enabled = enabled)
    }

    fun setBiquoteEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(BIQUOTE_KEY, enabled).apply()
        _settings.value = _settings.value.copy(biquoteEnabled = enabled)
    }

    fun setArticlePreviewEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(ARTICLE_KEY, enabled).apply()
        _settings.value = _settings.value.copy(articlePreviewEnabled = enabled)
    }

    fun setSources(urls: List<String>) {
        val normalized = urls.map(String::trim).filter(String::isNotEmpty).distinct().take(MAX_SOURCES)
        preferences.edit().putString(SOURCES_KEY, normalized.joinToString("\n")).apply()
        _settings.value = _settings.value.copy(sourceUrls = normalized)
    }

    private fun load(): NewsSettings {
        // null means never configured; an explicitly saved empty string disables RSS.
        val urls = restoredNewsSources(preferences.getString(SOURCES_KEY, null))
        return NewsSettings(
            enabled = preferences.getBoolean(ENABLED_KEY, false),
            sourceUrls = urls,
            biquoteEnabled = preferences.getBoolean(BIQUOTE_KEY, false),
            articlePreviewEnabled = preferences.getBoolean(ARTICLE_KEY, false),
        )
    }

    companion object {
        const val MAX_SOURCES = 8
        val DEFAULT_NEWS_SOURCES = listOf(
            "https://feeds.content.dowjones.io/public/rss/mw_topstories",
            "https://feeds.bbci.co.uk/news/business/rss.xml",
            "https://rss.dw.com/rdf/rss-en-bus",
        )
        private const val PREFERENCES_NAME = "research_preferences"
        private const val ENABLED_KEY = "ai_news_context_enabled"
        private const val SOURCES_KEY = "ai_news_rss_sources"
        private const val BIQUOTE_KEY = "ai_news_biquote_enabled"
        private const val ARTICLE_KEY = "ai_news_article_preview_enabled"
    }
}

internal fun restoredNewsSources(saved: String?): List<String> = saved?.lines()
    ?.map(String::trim)?.filter(String::isNotEmpty)?.distinct()?.take(NewsPreferences.MAX_SOURCES)
    ?: NewsPreferences.DEFAULT_NEWS_SOURCES

internal fun validNewsSources(enabled: Boolean, biquoteEnabled: Boolean, urls: List<String>): Boolean =
    urls.size <= NewsPreferences.MAX_SOURCES && urls.all {
        it.toHttpUrlOrNull()?.isHttps == true
    } && (!enabled || biquoteEnabled || urls.isNotEmpty())
