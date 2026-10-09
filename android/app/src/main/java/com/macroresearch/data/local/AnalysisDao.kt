package com.macroresearch.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AnalysisDao {
    @Query("SELECT * FROM ai_auto_refresh WHERE eventId = :eventId AND method = :method AND language = :language")
    suspend fun autoRefreshAttempt(eventId: Long, method: Int, language: String): AiAutoRefreshEntity?

    @Upsert
    suspend fun recordAutoRefreshAttempt(attempt: AiAutoRefreshEntity)

    @Query("SELECT * FROM ai_conversation WHERE eventId = :eventId")
    suspend fun conversation(eventId: Long): AiConversationEntity?

    @Upsert
    suspend fun upsertConversation(conversation: AiConversationEntity)

    @Query("DELETE FROM ai_conversation WHERE eventId = :eventId")
    suspend fun clearConversation(eventId: Long)

    @Query("DELETE FROM ai_conversation")
    suspend fun clearAllConversations()

    @Query("DELETE FROM ai_auto_refresh")
    suspend fun clearAllAutoRefreshAttempts()
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
