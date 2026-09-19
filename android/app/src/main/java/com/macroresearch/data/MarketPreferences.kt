package com.macroresearch.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MarketPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val _selectedMarkets = MutableStateFlow(loadMarkets())
    val selectedMarkets: StateFlow<List<String>> = _selectedMarkets.asStateFlow()

    fun setMarketEnabled(market: String, enabled: Boolean) {
        if (market !in SUPPORTED_MARKETS) return
        val selected = _selectedMarkets.value.toSet()
        if (enabled && market !in selected && selected.size >= MAX_SELECTIONS) return
        if (!enabled && market in selected && selected.size <= MIN_SELECTIONS) return

        val updated = if (enabled) selected + market else selected - market
        val ordered = SUPPORTED_MARKETS.filter(updated::contains)
        preferences.edit().putStringSet(SELECTED_MARKETS_KEY, ordered.toSet()).apply()
        _selectedMarkets.value = ordered
    }

    private fun loadMarkets(): List<String> {
        if (!preferences.contains(SELECTED_MARKETS_KEY)) return DEFAULT_MARKETS
        val saved = preferences.getStringSet(SELECTED_MARKETS_KEY, emptySet()).orEmpty()
        val supported = SUPPORTED_MARKETS.filter(saved::contains)
        return if (supported.size in MIN_SELECTIONS..MAX_SELECTIONS) supported else DEFAULT_MARKETS
    }

    companion object {
        val SUPPORTED_MARKETS = listOf(
            "nasdaq100", "sp500", "gold", "silver", "dxy", "eur_usd",
            "us2y", "us10y", "wti", "natural_gas", "bitcoin", "ethereum",
        )
        val DEFAULT_MARKETS = listOf("nasdaq100", "dxy", "gold")
        const val MIN_SELECTIONS = 2
        const val MAX_SELECTIONS = 4

        private const val PREFERENCES_NAME = "research_preferences"
        private const val SELECTED_MARKETS_KEY = "selected_markets"
    }
}
