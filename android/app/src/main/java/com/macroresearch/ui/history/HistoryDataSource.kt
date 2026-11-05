package com.macroresearch.ui.history

import com.macroresearch.data.MacroRepository
import com.macroresearch.data.model.EconomicEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Small boundary for history paging and explicit refresh; preserves repository routing rules. */
internal interface HistoryDataSource {
    val selectedCountries: StateFlow<Set<String>>
    val translationsUpdated: Flow<Unit>
    suspend fun history(
        countries: Collection<String>, category: String?, limit: Int, offset: Int,
        forceRefresh: Boolean, importance: Collection<Int>?,
    ): List<EconomicEvent>
    suspend fun cachedTranslations(names: List<String>): Map<String, Pair<String, String>>
}

internal class RepositoryHistoryDataSource(private val repository: MacroRepository) : HistoryDataSource {
    override val selectedCountries = repository.selectedCountries
    override val translationsUpdated = repository.translationsUpdated
    override suspend fun history(
        countries: Collection<String>, category: String?, limit: Int, offset: Int,
        forceRefresh: Boolean, importance: Collection<Int>?,
    ) = repository.history(countries, category, limit, offset, forceRefresh, importance)
    override suspend fun cachedTranslations(names: List<String>) = repository.cachedTranslations(names)
}
