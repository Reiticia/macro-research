package com.macroresearch.data

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.macroresearch.data.local.*
import com.macroresearch.data.model.*
import com.macroresearch.data.remote.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.Protocol
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.UUID

/** Uses a separate in-memory Room DB, namespaced preferences, fake providers/topics; no network. */
@RunWith(AndroidJUnit4::class)
class MacroSourceIsolationTest {
    private class Fixture : AutoCloseable {
        private val base = InstrumentationRegistry.getInstrumentation().targetContext
        private val prefix = "source-isolation-${UUID.randomUUID()}-"
        private val preferenceNames = mutableSetOf<String>()
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences(prefix + name, mode).also { preferenceNames += prefix + name }
        }
        val directory = File(base.cacheDir, prefix).apply { mkdirs() }
        val db = Room.inMemoryDatabaseBuilder(context, MacroDatabase::class.java).build()
        val backend = BackendPreferences(context)
        val network = NetworkPreferences(context)
        val countries = CountryPreferences(context)
        val history = AiHistoryStore(File(directory, "ai"))
        val topicsRemoved = mutableListOf<Long>()
        var upcoming: suspend () -> CalendarFetchResult = { CalendarFetchResult(emptyList()) }
        var calendar: suspend () -> CalendarFetchResult = { CalendarFetchResult(emptyList()) }
        var historyPage: suspend () -> List<EconomicEvent> = { emptyList() }
        var generate: suspend (AiAnalysisRequest) -> AiAnalysis = { error("Unexpected model call") }
        private fun provider(mode: DataSourceMode): DataSource = object : DataSource by sourceStub(mode) {
            override suspend fun upcoming(days: Int) = upcoming()
            override suspend fun calendar(start: java.time.LocalDate, end: java.time.LocalDate, countryCodes: Collection<String>?) = calendar()
            override suspend fun history(countries: Collection<String>, category: String?, limit: Int, offset: Int, importance: Collection<Int>?) = historyPage()
            override suspend fun translations(names: List<String>) = emptyMap<String, Pair<String, String>>()
            override suspend fun aiAnalysis(request: AiAnalysisRequest) = generate(request)
            override suspend fun ruleAnalysis(event: EconomicEvent, market: suspend () -> MarketResponse) = AnalysisReport(
                1, event.id, null, "neutral", emptyList(), emptyList(), emptyList(), "fixture", "now", "now")
            override suspend fun eventMarket(event: EconomicEvent) = MarketResponse(emptyList(), emptyList())
        }
        private fun sourceStub(mode: DataSourceMode): DataSource = Proxy.newProxyInstance(
            DataSource::class.java.classLoader, arrayOf(DataSource::class.java),
        ) { _, method, _ -> if (method.name == "getMode") mode else error("Unexpected provider call: ${method.name}") } as DataSource

        var modelResponse: () -> String = { """{"choices":[{"message":{"content":"new answer"}}]}""" }
        private val http = OkHttpClient.Builder().addInterceptor { chain ->
            check(chain.request().url.host == "personal-model.example.com") { "Unexpected network request" }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(modelResponse().toResponseBody("application/json".toMediaType())).build()
        }.build()
        val personal = TranslationPreferences(context)
        private val gson = Gson()
        private val rss = NewsRssClient(http)
        val repository = MacroRepository(
            provider(DataSourceMode.DIRECT), provider(DataSourceMode.BACKEND),
            EconomicCalendarClient(http), network, AnalysisPreferences(context), NewsPreferences(context),
            rss, history, backend, TranslationClient(http, gson), AiAnalysisClient(http, gson),
            db.eventDao(), db.analysisDao(), countries, MarketPreferences(context), personal,
            object : EventPushTopics {
                override suspend fun subscribe(eventId: Long) = Unit
                override suspend fun unsubscribe(eventId: Long) { topicsRemoved += eventId }
                override suspend fun synchronize(eventIds: List<Long>) = Unit
                override suspend fun unsubscribeAll(eventIds: List<Long>) { topicsRemoved += eventIds }
            }, CalendarPreferences(context), NewsReaderRepository(rss, ArticlePreviewClient(http), NewsReaderCache(File(directory, "news.json"))),
        )
        suspend fun seed() {
            db.eventDao().upsert(listOf(event().asEntity()))
            db.eventDao().follow(FollowedEventEntity(7))
            db.analysisDao().upsert(AiAnalysisEntity(7, 2, 1, "[]", "fixture", "fixture", null, "fixture",
                "2026-11-01T00:00:00Z", 1, 0, 0, 0, 0, false, false, null))
            db.analysisDao().upsertConversation(AiConversationEntity(7, "[]", 1))
            db.earningsDao().save(EarningsCacheEntity("backend:fixture", "2026-11-01", "preserved"))
            db.earningsDao().saveFavorite(EarningsFavoriteEntity("backend:fixture", "2026-11-01", "AAPL", "preserved"))
            db.analysisDao().recordAutoRefreshAttempt(AiAutoRefreshEntity(7, 2, "en", 1))
            val checkpoint = history.begin("fixture")
            history.save("fixture", checkpoint)
            network.markUpcomingSynced()
        }
        override fun close() {
            db.close()
            preferenceNames.forEach { base.deleteSharedPreferences(it) }
            directory.deleteRecursively()
            http.dispatcher.executorService.shutdownNow()
            http.connectionPool.evictAll()
        }
    }

    @Test(timeout = 30_000) fun changingBackendClearsMacroNamespaceButTokenOnlyAndDirectAddressEditsPreserveIt() = runBlocking {
        Fixture().use { f ->
            f.backend.save("https://a.example.com", "")
            f.backend.setMode(DataSourceMode.BACKEND)
            f.seed()
            val countries = f.countries.selectedCountries.value
            f.repository.saveBackendSettings("https://a.example.com/", "")
            assertNotNull(f.db.eventDao().event(7))
            assertEquals(listOf(7L), f.db.eventDao().followedEventIds())
            assertTrue(f.topicsRemoved.isEmpty())
            val invalid = runCatching { f.repository.saveBackendSettings("not a URL", "") }
            assertTrue(invalid.exceptionOrNull() is IllegalArgumentException)
            assertNotNull(f.db.eventDao().event(7))
            assertEquals("https://a.example.com", f.backend.settings.value.baseUrl)
            f.repository.saveBackendSettings("https://b.example.com", "")
            assertNull(f.db.eventDao().event(7))
            assertTrue(f.db.eventDao().followedEventIds().isEmpty())
            assertNull(f.db.analysisDao().analysis(7, 2))
            assertNull(f.db.analysisDao().conversation(7))
            assertNull(f.db.analysisDao().autoRefreshAttempt(7, 2, "en"))
            assertEquals("", f.history.load("fixture").session)
            assertFalse(f.network.upcomingSyncFresh(Long.MAX_VALUE))
            assertEquals(listOf(7L), f.topicsRemoved)
            assertEquals(countries, f.countries.selectedCountries.value)
            assertEquals("preserved", f.db.earningsDao().payload("backend:fixture", "2026-11-01"))
            assertEquals(1, f.db.earningsDao().favorites("backend:fixture").first().size)
            f.backend.setMode(DataSourceMode.DIRECT)
            f.seed()
            f.repository.saveBackendSettings("https://c.example.com", "")
            assertNotNull(f.db.eventDao().event(7))
            assertEquals(listOf(7L), f.topicsRemoved)
        }
    }

    @Test(timeout = 60_000) fun oldUpcomingAndHistoryResponsesCannotRefillCacheOrAdvanceFreshness() = runBlocking {
        for (request in listOf("backendUpcoming", "backendHistory", "directUpcoming", "directHistory")) {
            for (outcome in listOf("rows", "empty", "failure")) Fixture().use { f ->
                f.backend.save("https://a.example.com", "")
                if (request.startsWith("backend")) f.backend.setMode(DataSourceMode.BACKEND)
                val entered = CompletableDeferred<Unit>()
                val finish = CompletableDeferred<Unit>()
                suspend fun response(): List<EconomicEvent> {
                    entered.complete(Unit)
                    finish.await()
                    if (outcome == "failure") throw java.io.IOException("old source unavailable")
                    return if (outcome == "empty") emptyList() else listOf(event())
                }
                f.upcoming = { CalendarFetchResult(response()) }
                f.calendar = f.upcoming
                f.historyPage = { response() }
                val old = async {
                    runCatching {
                        if (request.endsWith("Upcoming")) f.repository.refreshUpcoming()
                        else f.repository.history(listOf("United States"), forceRefresh = true)
                    }
                }
                entered.await()
                if (request.startsWith("direct")) f.repository.setDataSourceMode(DataSourceMode.BACKEND)
                else f.repository.saveBackendSettings("https://b.example.com", "")
                finish.complete(Unit)
                assertTrue("$request $outcome", old.await().exceptionOrNull() is CancellationException)
                assertTrue(f.repository.observeUpcoming().first().isEmpty())
                assertFalse(f.network.upcomingSyncFresh(Long.MAX_VALUE))
                assertNull(f.repository.calendarWarning.value)
                // The new source is still immediately refreshable after rejecting the old one.
                f.upcoming = { CalendarFetchResult(listOf(event().copy(event = "new source"))) }
                f.repository.refreshUpcoming()
                assertEquals("new source", f.db.eventDao().event(7)?.event)
                assertTrue(f.network.upcomingSyncFresh(Long.MAX_VALUE))
            }
        }
    }

    @Test(timeout = 30_000) fun sourceSwitchCancelsBriefingAndPreventsLateRoomAndHistoryWrites() = runBlocking {
        Fixture().use { f ->
            f.personal.save("synthetic-model-key", "https://personal-model.example.com/v1", "fixture")
            f.seed()
            f.db.eventDao().upsert(listOf(event().copy(eventTime = Instant.now().minusSeconds(7200).toString()).asEntity()))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.generate = { request ->
                entered.complete(Unit)
                // Deliberately uncooperative provider: it returns even after cancellation.
                withContext(NonCancellable) { release.await() }
                AiAnalysis(7, request.method.wireValue, 2, emptyList(), "old answer", "old outlook", null, "fixture", Instant.now().toString())
            }
            val old = async { runCatching { f.repository.generateAiAnalysis(7, "en") } }
            entered.await()
            f.repository.setDataSourceMode(DataSourceMode.BACKEND)
            release.complete(Unit)
            assertTrue(old.await().exceptionOrNull() is CancellationException)
            assertNull(f.db.analysisDao().analysis(7, 2))
            assertTrue(f.repository.aiHistory(7).requests.isEmpty())
            assertEquals("", f.repository.aiHistory(7).session)
        }
    }

    @Test(timeout = 30_000) fun resettingInFlightFollowUpCannotRestoreOldMessagesAndAllowsNewConversation() = runBlocking {
        Fixture().use { f ->
            f.personal.save("synthetic-model-key", "https://personal-model.example.com/v1", "fixture")
            f.seed()
            f.db.eventDao().upsert(listOf(event().copy(eventTime = Instant.now().minusSeconds(7200).toString()).asEntity()))
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            f.modelResponse = {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                """{"choices":[{"message":{"content":"old answer"}}]}"""
            }
            val old = async { runCatching { f.repository.askAiFollowUp(7, "en", "old question") } }
            withContext(Dispatchers.IO) { assertTrue(entered.await(10, TimeUnit.SECONDS)) }
            f.repository.resetAiConversation(7)
            release.countDown()
            assertTrue(old.await().exceptionOrNull() is CancellationException)
            assertTrue(f.repository.aiConversation(7).isEmpty())
            assertTrue(f.repository.aiConversationHistory(7).requests.isEmpty())
            assertNotNull(f.db.analysisDao().analysis(7, 2)) // Reset never deletes the briefing.
            f.modelResponse = { """{"choices":[{"message":{"content":"new answer"}}]}""" }
            val messages = f.repository.askAiFollowUp(7, "en", "new question")
            assertEquals(listOf("new question", "new answer"), messages.map { it.content })
            assertEquals(messages, f.repository.aiConversation(7))
        }
    }

    companion object {
        private fun event() = EconomicEvent(7, "trading_view", "fixture", null, "United States", "USD", "inflation", "CPI",
            eventTime = Instant.now().plusSeconds(3600).toString(), importance = 3, actual = null,
            previous = "1", consensus = "2", forecast = "2", unit = "%", status = "scheduled")
    }
}
