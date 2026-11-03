package com.macroresearch.data

import com.macroresearch.data.model.EarningsQuote
import kotlinx.coroutines.flow.MutableStateFlow

internal fun currentStockQuote() = EarningsQuote("AAPL", "123.45", "USD", "2026-11-03T15:00:00Z",
    "2026-11-03T15:00:01Z", "yahoo", "provider_quote", false)

internal fun quoteRepository(
    settings: MutableStateFlow<BackendSettings> = MutableStateFlow(BackendSettings(mode = DataSourceMode.BACKEND)),
    favorites: EarningsFavoritesStore? = null,
    fetch: suspend (String) -> EarningsQuote = { currentStockQuote() },
) = EarningsRepository(settings, object : EarningsStore {
    override suspend fun read(source: String, date: String): String? = null
    override suspend fun write(source: String, date: String, payload: String) = Unit
    override suspend fun prune(before: String) = Unit
}, direct = { _, _ -> error("No public fallback") }, backend = { _, _ -> error("No calendar fetch") },
    backendQuote = fetch, favoritesStore = favorites)
