package com.macroresearch.data

import com.google.gson.Gson
import com.macroresearch.data.model.EconomicEvent
import com.macroresearch.data.model.EventDetailResponse
import com.macroresearch.data.remote.BackendClient
import com.macroresearch.data.remote.CalendarFetchResult
import com.macroresearch.data.remote.EconomicCalendarClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class ReleaseBatchFetcherTest {
    private fun event(id: Long, actual: String? = null, time: String = "2026-09-10T12:30:00Z") = EconomicEvent(
        id, "trading_view", "$id", null, "United States", "USD", "inflation", "Core CPI MoM",
        eventTime = time, importance = 3, actual = actual, previous = "0.2", consensus = "0.3",
        forecast = "0.3", unit = "%", status = if (actual == null) "scheduled" else "released",
    )

    private fun backend(server: MockWebServer, zone: ZoneId = ZoneOffset.UTC): BackendDataSource = BackendDataSource(
        BackendClient(OkHttpClient(), Gson(), { server.url("/").toString() }, { "test-token" }), zone,
    )

    @Test fun backendSynchronizesTheDateOnceThenLoadsAllReleasedEventsWithoutMarketOrAI() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val first = event(1, "0.4")
            val second = event(2, "0.31")
            val outside = event(3, "0.4", "2026-09-11T00:00:00Z")
            server.enqueue(MockResponse().setBody(Gson().toJson(EventDetailResponse(first, emptyList()))))
            server.enqueue(MockResponse().setBody(Gson().toJson(listOf(first, second, outside))))
            val result = ReleaseBatchFetcher.fetch(listOf(event(1), event(2)), backend(server, ZoneId.of("Asia/Shanghai")))
            assertEquals(listOf(1L, 2L), result.events.map { it.id })
            assertEquals(ReleaseImpactStrength.MATERIAL, ReleaseImpactEvaluator.evaluate(result.events[0]).strength)
            assertEquals(ReleaseImpactStrength.LIMITED, ReleaseImpactEvaluator.evaluate(result.events[1]).strength)
            assertEquals(2, server.requestCount)
            assertEquals("/api/v1/events/1/refresh", server.takeRequest().path)
            val calendar = server.takeRequest()
            assertEquals("/api/v1/calendar", calendar.requestUrl!!.encodedPath)
            assertTrue(calendar.getHeader("Cache-Control")!!.contains("no-cache"))
            val from = java.time.Instant.parse(calendar.requestUrl!!.queryParameter("from"))
            val to = java.time.Instant.parse(calendar.requestUrl!!.queryParameter("to"))
            assertTrue(from < java.time.Instant.parse("2026-09-10T00:00:00Z"))
            assertTrue(to >= java.time.Instant.parse("2026-09-10T23:59:59Z"))
        } finally { server.shutdown() }
    }

    @Test fun directModeFetchesOnePrimaryCalendarRequestForMultipleEvents() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"status":"ok","result":[
                {"id":"1","title":"Core CPI MoM","country":"US","date":"2026-09-10T12:30:00Z","actual":0.4,"forecast":0.3,"unit":"%"},
                {"id":"2","title":"CPI MoM","country":"US","date":"2026-09-10T12:30:00Z","actual":0.31,"forecast":0.3,"unit":"%"}]}"""))
            val client = EconomicCalendarClient(OkHttpClient(), ZoneOffset.UTC,
                primaryUrl = server.url("/primary").toString(), fallbackUrl = server.url("/fallback").toString())
            val source = object : DataSource by backend(server) {
                override val mode = DataSourceMode.DIRECT
                override suspend fun calendar(start: LocalDate, end: LocalDate, countryCodes: Collection<String>?): CalendarFetchResult {
                    assertEquals(LocalDate.parse("2026-09-10"), start)
                    assertEquals(start, end)
                    assertEquals(listOf("US"), countryCodes)
                    return client.fetch(start, end, countryCodes)
                }
            }
            val result = ReleaseBatchFetcher.fetch(listOf(event(1), event(2)), source, ZoneOffset.UTC)
            assertEquals(2, result.events.size)
            assertEquals(1, server.requestCount)
            assertEquals(ReleaseImpactStatus.DIRECTIONAL, ReleaseImpactEvaluator.evaluate(result.events.first()).status)
        } finally { server.shutdown() }
    }

    @Test fun backendDetailObservationSurvivesCalendarSnapshotWithMissingValue() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody(Gson().toJson(EventDetailResponse(event(1, "0.4"), emptyList()))))
            server.enqueue(MockResponse().setBody(Gson().toJson(listOf(event(1), event(2)))))
            val result = ReleaseBatchFetcher.fetch(listOf(event(1), event(2)), backend(server))
            assertEquals("0.4", result.events.first { it.id == 1L }.actual)
            assertNull(result.events.first { it.id == 2L }.actual)
        } finally { server.shutdown() }
    }
}
