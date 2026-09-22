package com.macroresearch.data

import com.macroresearch.data.local.CachedEventEntity
import com.macroresearch.data.local.CachedTranslation
import com.macroresearch.data.local.EventDao
import com.macroresearch.data.local.FollowedEventEntity
import com.macroresearch.data.remote.EconomicCalendarClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/** The detail-screen retry must fill the value without ever dropping the cached record. */
class EventReleaseRefreshTest {
    private val eventTime = "2026-09-10T12:30:00Z"

    @Test
    fun manualRetryFillsTheMissingValueAndKeepsRecordIdentity() = runBlocking {
        val dao = FakeEventDao().apply { rows[1] = cachedRow() }
        server { primary -> primary.enqueue(MockResponse().setBody(primaryBody("0.2"))) }
            .use { (server, client) ->
                val result = EventReleaseRefresher(client, dao, ZoneOffset.UTC).refresh(1)
                val stored = dao.rows.getValue(1)
                assertEquals("0.2", result.event.actual)
                assertNull(result.warning)
                assertEquals(1L, stored.id)
                assertEquals("0.2", stored.actual)
                assertEquals("核心PPI月率", stored.eventZhCn)
                assertTrue(server.requestCount == 1)
            }
    }

    @Test
    fun manualRetryFillsAnAliasedTitleFromThePrimarySource() = runBlocking {
        // The user-reported case: the stored row is the weekly-schedule title, while the primary
        // source publishes the same occurrence as "Core Inflation Rate MoM".
        val dao = FakeEventDao().apply {
            rows[1] = cachedRow().copy(event = "Core CPI m/m", eventZhCn = "核心CPI月率")
        }
        server {
            it.enqueue(MockResponse().setBody(primaryBody("0.3", title = "Core Inflation Rate MoM")))
        }.use { (_, client) ->
            val result = EventReleaseRefresher(client, dao, ZoneOffset.UTC).refresh(1)

            assertEquals("0.3", result.event.actual)
            assertEquals(1L, dao.rows.getValue(1).id)
            assertEquals("核心CPI月率", dao.rows.getValue(1).eventZhCn)
            assertNull(result.warning)
        }
    }

    @Test
    fun manualRetryKeepsTheRecordAndReportsWhenTheValueIsStillUnavailable() = runBlocking {
        val dao = FakeEventDao().apply { rows[1] = cachedRow() }
        server { primary ->
            primary.enqueue(MockResponse().setResponseCode(503))
            primary.enqueue(MockResponse().setBody(weeklyBody))
        }.use { (_, client) ->
            val result = EventReleaseRefresher(client, dao, ZoneOffset.UTC).refresh(1)

            assertNull(result.event.actual)
            assertNotNull(result.warning)
            // The row survives a failed retry: nothing is deleted when no value arrives.
            assertEquals(1L, dao.rows.getValue(1).id)
            assertNull(dao.rows.getValue(1).actual)
            assertEquals("核心PPI月率", dao.rows.getValue(1).eventZhCn)
        }
    }

    @Test
    fun manualRetryFailsLoudlyWhenTheSourceIsUnreachable() = runBlocking {
        val dao = FakeEventDao().apply { rows[1] = cachedRow() }
        server { primary ->
            primary.enqueue(MockResponse().setResponseCode(503))
            primary.enqueue(MockResponse().setResponseCode(503))
        }.use { (_, client) ->
            val failure = runCatching { EventReleaseRefresher(client, dao, ZoneOffset.UTC).refresh(1) }
            assertTrue(failure.isFailure)
            assertEquals(1L, dao.rows.getValue(1).id)
        }
    }

    private fun server(configure: (MockWebServer) -> Unit): Pair<MockWebServer, EconomicCalendarClient> {
        val server = MockWebServer().apply(configure)
        server.start()
        val client = EconomicCalendarClient(
            OkHttpClient(),
            ZoneOffset.UTC,
            primaryUrl = server.url("/primary").toString(),
            fallbackUrl = server.url("/fallback").toString(),
        )
        return server to client
    }

    /** `use` on a Pair is unavailable, so tests close the server through this helper. */
    private inline fun <T> Pair<MockWebServer, EconomicCalendarClient>.use(block: (Pair<MockWebServer, EconomicCalendarClient>) -> T): T =
        try {
            block(this)
        } finally {
            first.shutdown()
        }

    private fun primaryBody(actual: String, title: String = "Core PPI MoM") =
        """{"status":"ok","result":[{"id":"398283","title":"$title","country":"US","date":"$eventTime","actual":$actual,"forecast":0.3,"unit":"%"}]}"""

    private val weeklyBody =
        """[{"title":"Core PPI m/m","country":"USD","date":"2026-09-10T08:30:00-04:00","forecast":"0.3%","previous":"0.2%"}]"""

    private fun cachedRow() = CachedEventEntity(
        id = 1, provider = "forex_factory", providerId = "ff-core-ppi",
        releaseGroupId = null, country = "United States", currency = "USD", category = "inflation",
        event = "Core PPI m/m", eventZhCn = "核心PPI月率", eventZhTw = null,
        eventTime = eventTime, importance = 3, actual = null, previous = "0.2",
        consensus = "0.3", forecast = "0.3", unit = "%", status = "scheduled",
    )

    private class FakeEventDao : EventDao {
        val rows = linkedMapOf<Long, CachedEventEntity>()

        override fun observeUpcoming(from: String): Flow<List<CachedEventEntity>> = flowOf(emptyList())
        override fun observeEvent(id: Long): Flow<CachedEventEntity?> = flowOf(rows[id])
        override suspend fun event(id: Long): CachedEventEntity? = rows[id]
        override suspend fun history(before: String, countries: List<String>, category: String?, limit: Int, offset: Int) =
            emptyList<CachedEventEntity>()
        override suspend fun cachedRange(from: String, to: String) =
            rows.values.filter { it.eventTime in from..to }
        override suspend fun translations(names: List<String>) = emptyList<CachedTranslation>()
        override suspend fun updateTranslation(name: String, zhCn: String, zhTw: String) {
            rows.replaceAll { _, row ->
                if (row.event == name) row.copy(eventZhCn = zhCn, eventZhTw = zhTw) else row
            }
        }
        override suspend fun upsert(events: List<CachedEventEntity>) {
            events.forEach { rows[it.id] = it }
        }
        override suspend fun deleteOlderThan(before: String) = Unit
        override suspend fun deleteByProviders(providers: List<String>) = Unit
        override suspend fun clearEvents() {
            rows.clear()
        }

        override suspend fun clearFollows() = Unit
        override fun observeFollowedEvents(): Flow<List<CachedEventEntity>> = flowOf(emptyList())
        override fun observeFollowed(eventId: Long): Flow<Boolean> = flowOf(false)
        override suspend fun isFollowed(eventId: Long) = false
        override suspend fun followedEventIds() = emptyList<Long>()
        override suspend fun follow(event: FollowedEventEntity) = Unit
        override suspend fun unfollow(eventId: Long) = Unit
    }
}
