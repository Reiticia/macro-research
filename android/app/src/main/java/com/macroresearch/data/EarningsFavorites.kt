package com.macroresearch.data

import com.macroresearch.data.model.EarningsEvent
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/** A particular earnings occurrence, not an auto-rolling subscription to a ticker. */
data class FavoriteEarnings(val date: LocalDate, val event: EarningsEvent)

interface EarningsFavoritesStore {
    fun observe(source: String): Flow<List<FavoriteEarnings>>
    suspend fun set(source: String, favorite: FavoriteEarnings, enabled: Boolean)
}
