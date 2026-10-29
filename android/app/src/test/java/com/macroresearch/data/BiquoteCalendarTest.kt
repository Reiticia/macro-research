package com.macroresearch.data

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.macroresearch.data.local.asEntity
import com.macroresearch.data.local.asExternalModel
import com.macroresearch.data.local.mergeCalendarRows
import com.macroresearch.data.local.fillMissingValues
import com.macroresearch.data.remote.EconomicCalendarClient
import com.macroresearch.data.remote.biquoteRows
import com.macroresearch.data.remote.parseBiquoteCalendar
import com.macroresearch.ui.common.localTime
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class BiquoteCalendarTest {
    private val day = LocalDate.parse("2026-10-28")
    private val now = Instant.parse("2026-10-28T16:00:00Z")
    private val tv = """{"status":"ok","result":[{"id":"tv","title":"CPI MoM","country":"AU","date":"2026-10-28T12:30:00Z","actual":0.2}]}"""

    private fun fixture() = javaClass.getResourceAsStream("/calendar-units/biquote.json")!!
        .bufferedReader().use { it.readText() }

    private fun client(server: MockWebServer, zone: ZoneId = ZoneOffset.UTC, backoff: CalendarBackoffStore = CalendarBackoffStore.inMemory()) =
        EconomicCalendarClient(OkHttpClient(), zone,
            primaryUrl = server.url("/tradingview").toString(),
            biquoteUrl = server.url("/biquote").toString(),
            fallbackUrl = server.url("/weekly").toString(), backoff = backoff)

    @Test fun sharedFixturePreservesNullZeroRevisionsUnitsAndOccurrenceIds() {
        val events = parseBiquoteCalendar(biquoteRows(fixture()), now).associateBy { it.providerId }
        assertEquals(7, events.size)
        val cpi = events.getValue("mql5:302115")
        assertEquals("biquote", cpi.provider)
        assertEquals("Australia", cpi.country)
        assertEquals("1.3", cpi.actual)
        assertEquals("1.1", cpi.consensus)
        assertEquals(cpi.consensus, cpi.forecast)
        assertEquals("%", cpi.unit)
        val zero = events.getValue("mql5:zero")
        assertEquals("0", zero.actual)
        assertEquals("1", zero.previous)
        assertNotEquals(cpi.id, zero.id) // same eventId (series), different occurrences
        val jobs = events.getValue("mql5:299659")
        assertNull(jobs.actual)
        assertEquals("3104.4", jobs.forecast)
        assertEquals("K jobs", jobs.unit)
        assertEquals("B USD", events.getValue("mql5:currency").unit)
        // Diffusion indices: only the exact ISM names get a dimension.
        val ism = events.getValue("mql5:ism")
        assertEquals("index points", ism.unit)
        assertEquals(ReleaseImpactStatus.DIRECTIONAL, ReleaseImpactEvaluator.evaluate(ism).status)
        assertEquals("-1.5", ReleaseImpactEvaluator.evaluate(ism).surprise!!.stripTrailingZeros().toPlainString())
        assertNull(events.getValue("mql5:richmond").unit)
        val tentative = events.getValue("fxempire:tentative")
        assertFalse(tentative.timeExact)
        assertEquals("--:--", tentative.localTime())
        assertFalse(tentative.asEntity().asExternalModel().timeExact)
        assertNull(tentative.unit)
        assertEquals(events.keys, parseBiquoteCalendar(biquoteRows(fixture()), now).map { it.providerId }.toSet())
        val claims = jobs.copy(country = "United States", event = "Initial Jobless Claims", actual = "197", consensus = "201")
        assertEquals("-4000", ReleaseImpactEvaluator.evaluate(claims).surprise!!.stripTrailingZeros().toPlainString())
    }

    @Test fun productionDefaultCallsTradingViewWithoutAKeyOrFallback() = runBlocking {
        var calls = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            val request = chain.request()
            assertEquals("economic-calendar.tradingview.com", request.url.host)
            assertEquals("/events", request.url.encodedPath)
            assertEquals("AU", request.url.queryParameter("countries"))
            assertNull(request.header("Authorization"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(tv.toResponseBody()).build()
        }.build()
        val result = EconomicCalendarClient(http, ZoneOffset.UTC).fetch(day, day, listOf("AU"))
        assertTrue(result.events.isNotEmpty())
        assertNull(result.warning)
        assertEquals(1, calls)
    }

    @Test fun emptyInvalidAndFailedPrimaryResponsesUseBiquoteBeforeWeekly() = runBlocking {
        for (response in listOf(MockResponse().setBody("""{"status":"no_data"}"""), MockResponse().setBody("[]"), MockResponse().setBody("{}"), MockResponse().setResponseCode(503))) {
            MockWebServer().use { server ->
                server.enqueue(response)
                server.enqueue(MockResponse().setBody(fixture()))
                val result = client(server).fetch(day, day)
                assertTrue(result.events.isNotEmpty())
                assertTrue(result.events.all { it.provider == "biquote" })
                assertEquals(CalendarWarning.Reason.PRIMARY_UNAVAILABLE, result.warning?.reason)
                assertEquals(2, server.requestCount)
                assertEquals("/tradingview", server.takeRequest().requestUrl!!.encodedPath)
                assertEquals("/biquote", server.takeRequest().requestUrl!!.encodedPath)
            }
        }
    }

    @Test fun primaryRetryAfterIsHonoredWhileTheSecondaryRemainsUsable() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "300"))
            server.enqueue(MockResponse().setBody(fixture()))
            server.enqueue(MockResponse().setBody(fixture()))
            val backoff = CalendarBackoffStore.inMemory()
            val client = client(server, backoff = backoff)
            val first = client.fetch(day, day).events
            assertTrue(first.isNotEmpty())
            assertTrue(first.all { it.provider == "biquote" })
            assertNotNull(backoff.blockedUntil(CalendarSource.PRIMARY))
            assertNull(client.nextRetryAt())
            val second = client.fetch(day, day).events
            assertTrue(second.isNotEmpty())
            assertTrue(second.all { it.provider == "biquote" })
            assertEquals(3, server.requestCount)
            server.takeRequest()
            assertEquals("/biquote", server.takeRequest().requestUrl!!.encodedPath)
            assertEquals("/biquote", server.takeRequest().requestUrl!!.encodedPath)
        }
    }

    @Test fun biquoteRetryAfterDoesNotBlockTradingViewRecovery() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "300"))
            server.enqueue(MockResponse().setBody("""[{"title":"CPI m/m","country":"USD","date":"2026-10-28T12:30:00Z"}]"""))
            server.enqueue(MockResponse().setBody(tv))
            val backoff = CalendarBackoffStore.inMemory()
            val client = client(server, backoff = backoff)
            assertEquals("forex_factory", client.fetch(day, day).events.single().provider)
            assertNotNull(backoff.blockedUntil(CalendarSource.BIQUOTE))
            assertNull(backoff.blockedUntil(CalendarSource.PRIMARY))
            assertEquals("trading_view", client.fetch(day, day).events.single().provider)
            assertEquals(4, server.requestCount)
            assertEquals("/tradingview", server.takeRequest().requestUrl!!.encodedPath)
            assertEquals("/biquote", server.takeRequest().requestUrl!!.encodedPath)
            assertEquals("/weekly", server.takeRequest().requestUrl!!.encodedPath)
            assertEquals("/tradingview", server.takeRequest().requestUrl!!.encodedPath)
        }
    }

    @Test fun allBlockedSourcesRetryAtTheEarliestProviderDeadline() {
        val backoff = CalendarBackoffStore.inMemory()
        backoff.block(CalendarSource.PRIMARY, now.plusSeconds(300))
        backoff.block(CalendarSource.BIQUOTE, now.plusSeconds(120))
        backoff.block(CalendarSource.FALLBACK, now.plusSeconds(600))
        MockWebServer().use { server ->
            assertEquals(now.plusSeconds(120), client(server, backoff = backoff).nextRetryAt(now))
            backoff.clear(CalendarSource.BIQUOTE)
            assertNull(client(server, backoff = backoff).nextRetryAt(now))
        }
    }

    @Test fun cappedLocalDaysAreBisectedAndInclusiveBoundaryRowsAreNotDuplicated() = runBlocking {
        val zone = ZoneId.of("Asia/Shanghai")
        val start = day.atStartOfDay(zone).toInstant()
        val rows = (0..600).map { index -> row(index, start.plusSeconds(index * 120L)) }
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val url = request.requestUrl!!
                    if (url.encodedPath == "/tradingview") return MockResponse().setResponseCode(503)
                    assertEquals("/biquote", url.encodedPath)
                    assertEquals("US", url.queryParameter("countries"))
                    assertEquals("500", url.queryParameter("limit"))
                    val from = Instant.parse(url.queryParameter("from"))
                    val to = Instant.parse(url.queryParameter("to"))
                    val result = JsonArray()
                    rows.filter { Instant.parse(it["time"].asString) in from..to }.take(500).forEach(result::add)
                    return MockResponse().setBody(result.toString())
                }
            }
            val result = client(server, zone).fetch(day, day, listOf("US"))
            assertEquals(CalendarWarning.Reason.PRIMARY_UNAVAILABLE, result.warning?.reason)
            assertEquals(601, result.events.size)
            assertEquals(601, result.events.map { it.id }.toSet().size)
            assertEquals(4, server.requestCount)
            assertEquals("/tradingview", server.takeRequest().requestUrl!!.encodedPath)
            val request = server.takeRequest().requestUrl!!
            assertEquals(start, Instant.parse(request.queryParameter("from")))
            assertEquals(day.plusDays(1).atStartOfDay(zone).toInstant(), Instant.parse(request.queryParameter("to")))
        }
    }

    @Test fun anUnresolvableBiquoteCapFallsThroughToWeeklyWithoutPartialData() = runBlocking {
        val capped = JsonArray().apply { repeat(500) { add(row(it, now)) } }.toString()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    when (request.requestUrl!!.encodedPath) {
                        "/tradingview" -> MockResponse().setResponseCode(503)
                        "/biquote" -> MockResponse().setBody(capped)
                        else -> MockResponse().setBody("""[{"title":"CPI m/m","country":"USD","date":"2026-10-28T12:30:00Z"}]""")
                    }
            }
            val result = client(server).fetch(day, day)
            assertEquals("forex_factory", result.events.single().provider)
            assertTrue(result.warning!!.detail!!.contains("row cap"))
            assertTrue(server.requestCount < 30)
        }
    }

    @Test fun tradingViewPromotionKeepsSavedIdentityAndCannotBeDemotedByBiquote() {
        val biquote = parseBiquoteCalendar(biquoteRows(fixture()), now).first { it.providerId == "mql5:302115" }.asEntity()
        val fresh = biquote.copy(provider = "trading_view", providerId = "tv")
        val old = biquote.copy(id = 77, eventZhCn = "消费者物价指数")
        val merged = mergeCalendarRows(listOf(fresh), listOf(old)).single()
        assertEquals(77L, merged.id)
        assertEquals("trading_view", merged.provider)
        assertEquals(old.eventZhCn, merged.eventZhCn)
        assertEquals(merged, mergeCalendarRows(listOf(old.copy(actual = null)), listOf(merged)).single())
        val missing = merged.copy(actual = null)
        val filled = mergeCalendarRows(listOf(old.copy(actual = "2")), listOf(missing)).single()
        assertEquals("trading_view", filled.provider)
        assertEquals("2", filled.actual)
        assertEquals(77L, filled.id)
    }

    @Test fun cachedTradingViewValuesTakePrecedenceOverBiquoteForWeeklyHydration() {
        val base = parseBiquoteCalendar(biquoteRows(fixture()), now)
            .first { it.providerId == "mql5:302115" }.asEntity()
        val weekly = base.copy(id = 1, provider = "forex_factory", providerId = "weekly", actual = null)
        val biquote = base.copy(id = 2, actual = "9")
        val tradingView = base.copy(id = 3, provider = "trading_view", providerId = "tv", actual = "2")
        val hydrated = fillMissingValues(listOf(weekly, biquote, tradingView)).single()
        assertEquals(weekly.id, hydrated.id)
        assertEquals("2", hydrated.actual)
    }

    private fun row(id: Int, time: Instant) = JsonObject().apply {
        addProperty("id", "mql5:$id")
        addProperty("eventId", "mql5:series")
        addProperty("time", time.toString())
        addProperty("name", "CPI m/m")
        addProperty("countryCode", "US")
        addProperty("timeMode", "exact")
        addProperty("actual", 0)
    }
}
