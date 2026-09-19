package com.macroresearch.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class CachedTranslation(
    val event: String,
    val eventZhCn: String,
    val eventZhTw: String,
)

@Dao
interface EventDao {
    @Query("SELECT * FROM cached_event WHERE eventTime >= :from ORDER BY eventTime, importance DESC")
    fun observeUpcoming(from: String): Flow<List<CachedEventEntity>>

    @Query("SELECT * FROM cached_event WHERE id = :id")
    fun observeEvent(id: Long): Flow<CachedEventEntity?>

    @Query("SELECT * FROM cached_event WHERE id = :id")
    suspend fun event(id: Long): CachedEventEntity?

    @Query(
        """SELECT * FROM cached_event
           WHERE eventTime < :before
             AND country IN (:countries)
             AND (:category IS NULL OR LOWER(category) LIKE '%' || LOWER(:category) || '%')
           ORDER BY eventTime DESC, importance DESC
           LIMIT :limit OFFSET :offset""",
    )
    suspend fun history(
        before: String,
        countries: List<String>,
        category: String?,
        limit: Int,
        offset: Int,
    ): List<CachedEventEntity>

    @Query(
        """SELECT event, eventZhCn, eventZhTw FROM cached_event
           WHERE event IN (:names) AND eventZhCn IS NOT NULL AND eventZhTw IS NOT NULL""",
    )
    suspend fun translations(names: List<String>): List<CachedTranslation>

    @Query(
        "UPDATE cached_event SET eventZhCn = :zhCn, eventZhTw = :zhTw WHERE event = :name",
    )
    suspend fun updateTranslation(name: String, zhCn: String, zhTw: String)

    @Query("SELECT * FROM cached_event WHERE eventTime >= :from AND eventTime <= :to")
    suspend fun cachedRange(from: String, to: String): List<CachedEventEntity>

    @Transaction
    suspend fun mergeCalendar(events: List<CachedEventEntity>): List<CachedEventEntity> {
        if (events.isEmpty()) return events
        val cached = cachedRange(events.minOf { it.eventTime }, events.maxOf { it.eventTime })
        val merged = mergeCalendarRows(events, cached)
        // Fill weekly-schedule rows whose indicator was published under a different title.
        val byId = LinkedHashMap<Long, CachedEventEntity>()
        merged.forEach { byId[it.id] = it }
        fillMissingValues(merged + cached).forEach { byId[it.id] = it }
        // Always persist: skipping this write would drop every freshly fetched event.
        upsert(byId.values.toList())
        return merged.map { byId[it.id] ?: it }
    }

    @Upsert
    suspend fun upsert(events: List<CachedEventEntity>)

    /**
     * Drops every cached event and follow. Called when the data-source mode changes, because
     * direct-mode ids are content digests while backend ids are database keys.
     */
    @Transaction
    suspend fun clearCache() {
        clearFollows()
        clearEvents()
    }

    @Query("DELETE FROM cached_event")
    suspend fun clearEvents()

    @Query("DELETE FROM followed_event")
    suspend fun clearFollows()

    @Query("DELETE FROM cached_event WHERE eventTime < :before")
    suspend fun deleteOlderThan(before: String)

    @Query("DELETE FROM cached_event WHERE provider IN (:providers)")
    suspend fun deleteByProviders(providers: List<String>)

    @Query("SELECT EXISTS(SELECT 1 FROM followed_event WHERE eventId = :eventId)")
    fun observeFollowed(eventId: Long): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM followed_event WHERE eventId = :eventId)")
    suspend fun isFollowed(eventId: Long): Boolean

    @Upsert
    suspend fun follow(event: FollowedEventEntity)

    @Query("DELETE FROM followed_event WHERE eventId = :eventId")
    suspend fun unfollow(eventId: Long)

    /** Manual correction memory; survives refreshes and mode switches by design. */
    @Upsert
    suspend fun correctName(correction: NameCorrectionEntity)

    @Query("SELECT * FROM name_correction")
    suspend fun corrections(): List<NameCorrectionEntity>
}
