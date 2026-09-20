package com.macroresearch.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AnalysisDao {
    /** The cached result of one method; the three methods live side by side. */
    @Query("SELECT * FROM ai_analysis WHERE eventId = :eventId AND method = :method")
    fun observe(eventId: Long, method: Int): Flow<AiAnalysisEntity?>

    @Query("SELECT * FROM ai_analysis WHERE eventId = :eventId AND method = :method")
    suspend fun analysis(eventId: Long, method: Int): AiAnalysisEntity?

    @Upsert
    suspend fun upsert(analysis: AiAnalysisEntity)

    /** Used when the data-source mode changes: the two modes do not share event ids. */
    @Query("DELETE FROM ai_analysis")
    suspend fun clearAll()
}
