package com.macroresearch.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Opt-in news context for personally configured, direct AI briefings. */
data class NewsSettings(
    val enabled: Boolean = false,
    val sourceUrls: List<String> = NewsPreferences.DEFAULT_NEWS_SOURCES,
)

class NewsPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<NewsSettings> = _settings.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(ENABLED_KEY, enabled).apply()
        _settings.value = _settings.value.copy(enabled = enabled)
    }

    fun setSources(urls: List<String>) {
        val normalized = urls.map(String::trim).filter(String::isNotEmpty).distinct().take(MAX_SOURCES)
        preferences.edit().putString(SOURCES_KEY, normalized.joinToString("\n")).apply()
        _settings.value = _settings.value.copy(sourceUrls = normalized)
    }

    private fun load(): NewsSettings {
        val urls = preferences.getString(SOURCES_KEY, null)
            ?.lines()
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.distinct()
            ?.take(MAX_SOURCES)
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_NEWS_SOURCES
        return NewsSettings(
            enabled = preferences.getBoolean(ENABLED_KEY, false),
            sourceUrls = urls,
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
    }
}
