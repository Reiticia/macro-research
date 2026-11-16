package com.macroresearch.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.time.Instant

data class CachedTranslation(val event: String, val eventZhCn: String, val eventZhTw: String)

@Dao
interface EventDao {
    fun observeUpcoming(from: String): Flow<List<CachedEventEntity>> {
        val time = Instant.parse(from)
        return observeUpcomingAt(time.epochSecond, time.nano)
    }

    @Query("SELECT * FROM cached_event WHERE (eventEpochSecond > :second OR (eventEpochSecond = :second AND eventNano >= :nano)) ORDER BY eventEpochSecond, eventNano, importance DESC, id")
    fun observeUpcomingAt(second: Long, nano: Int): Flow<List<CachedEventEntity>>

    @Query("SELECT * FROM cached_event WHERE id = :id")
    fun observeEvent(id: Long): Flow<CachedEventEntity?>

    @Query("SELECT * FROM cached_event WHERE id = :id")
    suspend fun event(id: Long): CachedEventEntity?

    suspend fun history(before: String, countries: List<String>, category: String?, limit: Int, offset: Int,
        importance: List<Int> = listOf(0, 1, 2, 3)): List<CachedEventEntity> {
        val time = Instant.parse(before)
        return historyAt(time.epochSecond, time.nano, countries, category, limit, offset, importance)
    }

    @Query("""SELECT * FROM cached_event
        WHERE (eventEpochSecond < :second OR (eventEpochSecond = :second AND eventNano < :nano))
          AND country IN (:countries)
          AND (:category IS NULL OR LOWER(category) LIKE '%' || LOWER(:category) || '%')
          AND importance IN (:importance)
        ORDER BY eventEpochSecond DESC, eventNano DESC, importance DESC, id DESC
        LIMIT :limit OFFSET :offset""")
    suspend fun historyAt(second: Long, nano: Int, countries: List<String>, category: String?,
        limit: Int, offset: Int, importance: List<Int>): List<CachedEventEntity>

    @Query("""SELECT event, eventZhCn, eventZhTw FROM cached_event
        WHERE event IN (:names) AND eventZhCn IS NOT NULL AND eventZhTw IS NOT NULL""")
    suspend fun translations(names: List<String>): List<CachedTranslation>

    @Query("UPDATE cached_event SET eventZhCn = :zhCn, eventZhTw = :zhTw WHERE event = :name")
    suspend fun updateTranslation(name: String, zhCn: String, zhTw: String)

    /** Closed interval for release polling and occurrence merging, including a single instant. */
    suspend fun cachedRange(from: String, to: String): List<CachedEventEntity> {
        val start = Instant.parse(from); val end = Instant.parse(to)
        return cachedRangeAt(start.epochSecond, start.nano, end.epochSecond, end.nano, true)
    }

    /** Calendar days use [from, until), not an artificial last second/nanosecond. */
    suspend fun cachedCalendarRange(from: String, until: String): List<CachedEventEntity> {
        val start = Instant.parse(from); val end = Instant.parse(until)
        return cachedRangeAt(start.epochSecond, start.nano, end.epochSecond, end.nano, false)
    }

    @Query("""SELECT * FROM cached_event
        WHERE (eventEpochSecond > :startSecond OR (eventEpochSecond = :startSecond AND eventNano >= :startNano))
          AND (eventEpochSecond < :endSecond OR (eventEpochSecond = :endSecond AND (eventNano < :endNano OR (:inclusive AND eventNano = :endNano))))
        ORDER BY eventEpochSecond, eventNano, importance DESC, id""")
    suspend fun cachedRangeAt(startSecond: Long, startNano: Int, endSecond: Long, endNano: Int, inclusive: Boolean): List<CachedEventEntity>

    fun observeRange(from: String, until: String): Flow<List<CachedEventEntity>> {
        val start = Instant.parse(from); val end = Instant.parse(until)
        return observeRangeAt(start.epochSecond, start.nano, end.epochSecond, end.nano)
    }

    @Query("""SELECT * FROM cached_event
        WHERE (eventEpochSecond > :startSecond OR (eventEpochSecond = :startSecond AND eventNano >= :startNano))
          AND (eventEpochSecond < :endSecond OR (eventEpochSecond = :endSecond AND eventNano < :endNano))
        ORDER BY eventEpochSecond, eventNano, importance DESC, id""")
    fun observeRangeAt(startSecond: Long, startNano: Int, endSecond: Long, endNano: Int): Flow<List<CachedEventEntity>>

    @Transaction
    suspend fun mergeCalendar(events: List<CachedEventEntity>): List<CachedEventEntity> {
        if (events.isEmpty()) return events
        val times = events.mapNotNull { parseEventTimestamp(it.eventTime) }
        val cached = if (times.isEmpty()) emptyList() else cachedRange(times.min().toString(), times.max().toString())
        val merged = mergeCalendarRows(events, cached)
        val byId = LinkedHashMap<Long, CachedEventEntity>()
        merged.forEach { byId[it.id] = it }
        fillMissingValues(merged + cached).forEach { byId[it.id] = it }
        upsert(byId.values.toList())
        return merged.map { byId[it.id] ?: it }
    }

    // copy(eventTime=...) must not retain a stale sort key. Normalize every write at the DAO boundary.
    suspend fun upsert(events: List<CachedEventEntity>) = upsertTimestamped(events.map { row ->
        val time = parseEventTimestamp(row.eventTime)
        row.copy(eventEpochSecond = time?.epochSecond, eventNano = time?.nano)
    })

    @Upsert
    suspend fun upsertTimestamped(events: List<CachedEventEntity>)

    /** Source switching drops both id-scoped event cache and follows. */
    @Transaction
    suspend fun clearCache() { clearFollows(); clearEvents() }

    @Query("DELETE FROM cached_event")
    suspend fun clearEvents()

    @Query("DELETE FROM followed_event")
    suspend fun clearFollows()

    suspend fun deleteOlderThan(before: String) {
        val time = Instant.parse(before)
        deleteOlderThanAt(time.epochSecond, time.nano)
    }

    @Query("DELETE FROM cached_event WHERE eventEpochSecond < :second OR (eventEpochSecond = :second AND eventNano < :nano)")
    suspend fun deleteOlderThanAt(second: Long, nano: Int)

    @Query("DELETE FROM cached_event WHERE provider IN (:providers)")
    suspend fun deleteByProviders(providers: List<String>)

    @Query("SELECT EXISTS(SELECT 1 FROM followed_event WHERE eventId = :eventId)")
    fun observeFollowed(eventId: Long): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM followed_event WHERE eventId = :eventId)")
    suspend fun isFollowed(eventId: Long): Boolean

    @Query("SELECT cached_event.* FROM cached_event INNER JOIN followed_event ON followed_event.eventId = cached_event.id ORDER BY cached_event.eventEpochSecond IS NULL, cached_event.eventEpochSecond, cached_event.eventNano, cached_event.id")
    fun observeFollowedEvents(): Flow<List<CachedEventEntity>>

    @Query("SELECT eventId FROM followed_event ORDER BY followedAt")
    suspend fun followedEventIds(): List<Long>

    @Upsert
    suspend fun follow(event: FollowedEventEntity)

    @Query("DELETE FROM followed_event WHERE eventId = :eventId")
    suspend fun unfollow(eventId: Long)
}
