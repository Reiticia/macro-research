package com.macroresearch.data

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.macroresearch.data.model.*
import com.macroresearch.data.remote.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

class EarningsTest {
    private val day = LocalDate.parse("2026-11-02")
    private val now = Instant.parse("2026-11-01T12:00:00Z")
    private fun fixture(name: String) = javaClass.getResourceAsStream("/earnings/$name.json")!!.bufferedReader().use { it.readText() }
    private fun dateScanFixture(date: LocalDate = day): String {
        val root = JsonParser.parseString(fixture("tradingview")).asJsonObject
        val rows = root.getAsJsonArray("data").filter { Instant.ofEpochSecond(it.asJsonObject.getAsJsonArray("d")[2].asLong).atZone(EARNINGS_ZONE).toLocalDate() == date }
        root.add("data", com.google.gson.JsonArray().apply { rows.forEach { add(it) } })
        root.addProperty("totalCount", rows.size)
        return root.toString()
    }
    private fun calendar(symbol: String = "AAPL") = EarningsCalendar(day.toString(), updatedAt = now.toString(), nasdaqUpdatedAt = now.toString(), events = listOf(EarningsEvent(symbol, "Apple", "nasdaq")))
    private fun client(server: MockWebServer) = EarningsClient(OkHttpClient(), nasdaqUrl = server.url("/nasdaq").toString(), tradingViewUrl = server.url("/tv").toString(), clock = { now })

    @Test fun sharedFixturesPreserveZeroNegativesAndSeparateForecasts() {
        val events = mergeEarnings(parseNasdaqEarnings(fixture("nasdaq"), day), parseTradingViewEarnings(fixture("tradingview"), day)).associateBy { it.symbol }
        assertEquals("0", events.getValue("LOSS").actualEps)
        assertEquals("-0.25", events.getValue("LOSS").epsForecast)
        assertEquals("unknown", events.getValue("LOSS").session)
        assertNull(events.getValue("LOSS").tradingView) // already reported; next-period snapshot is unrelated
        assertTrue(parseTradingViewEarnings(fixture("tradingview"), day).first { it.symbol == "LOSS" }.tradingView!!.dateConflict)
        assertEquals("1.98", events.getValue("AAPL").epsForecast)
        assertEquals("1.989719", events.getValue("AAPL").tradingView!!.nextEpsForecast)
        assertEquals("113401213676", events.getValue("AAPL").tradingView!!.nextRevenueForecast)
        assertNull(events.getValue("MAR").epsForecast)
        assertEquals(0, events.getValue("MAR").analystCount)
    }

    @Test fun dateMismatchMalformedAndTruncatedResponsesFailClosed() {
        for (raw in listOf("{}", "[]", fixture("nasdaq").replace("Nov 2", "Nov 3"), fixture("nasdaq").replace("\"rows\":[", "\"rows\":{} ,\"ignored\":["))) {
            assertTrue(runCatching { parseNasdaqEarnings(raw, day) }.isFailure)
        }
        assertTrue(runCatching { parseTradingViewEarnings(fixture("tradingview").replace("\"totalCount\":2", "\"totalCount\":3"),day) }.isFailure)
        val duplicate = parseTradingViewEarnings(fixture("tradingview"), day).first()
        assertNull(mergeEarnings(parseNasdaqEarnings(fixture("nasdaq"),day),listOf(duplicate, duplicate)).first { it.symbol=="AAPL" }.tradingView)
    }

    @Test fun datesUseNewYorkIncludingDaylightSaving() {
        for ((instant, date) in listOf("2026-07-03T01:00:00Z" to "2026-07-02", "2026-11-03T01:00:00Z" to "2026-11-02")) {
            val ts = Instant.parse(instant).epochSecond
            val raw = """{"totalCount":1,"data":[{"s":"NASDAQ:AAPL","d":["AAPL","Apple",$ts,null,null,"USD"]}]}"""
            val event = parseTradingViewEarnings(raw,LocalDate.parse(date)).single()
            assertEquals(date,event.tradingView!!.nextDate)
            assertEquals("unknown",event.session)
            assertFalse(event.tradingView.dateConflict)
        }
        val spring = LocalDate.parse("2026-03-08")
        val autumn = LocalDate.parse("2026-11-01")
        assertEquals(23, Duration.between(spring.atStartOfDay(EARNINGS_ZONE),spring.plusDays(1).atStartOfDay(EARNINGS_ZONE)).toHours())
        assertEquals(25, Duration.between(autumn.atStartOfDay(EARNINGS_ZONE),autumn.plusDays(1).atStartOfDay(EARNINGS_ZONE)).toHours())
    }

    @Test fun directRequestsAreKeylessAndValidateSupplementalSymbols() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(fixture("nasdaq")))
            server.enqueue(MockResponse().setBody(fixture("tradingview")))
            val result = client(server).fetch(day)
            assertEquals(3,result.events.size)
            val primary = server.takeRequest()
            assertEquals(day.toString(),primary.requestUrl!!.queryParameter("date"))
            assertEquals("https://www.nasdaq.com",primary.getHeader("Origin"))
            assertNull(primary.getHeader("Authorization")); assertNull(primary.getHeader("X-Installation-Id"))
            val secondary = server.takeRequest()
            assertEquals("POST",secondary.method)
            assertNull(secondary.getHeader("Authorization"))
            val body = JsonParser.parseString(secondary.body.readUtf8()).asJsonObject
            assertEquals("name",body.getAsJsonArray("filter")[1].asJsonObject.get("left").asString)
            assertEquals(earningsColumns.size,body.getAsJsonArray("columns").size())
        }
    }

    @Test fun primaryOutagePreservesCacheAndBothOutagesDoNotInventEmptySuccess() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setResponseCode(503))
            val result = client(server).fetch(day,calendar())
            assertTrue(result.stale); assertEquals(now.toString(),result.updatedAt)
            assertEquals(1,result.events.size)
            server.enqueue(MockResponse().setResponseCode(503)); server.enqueue(MockResponse().setResponseCode(503))
            assertTrue(runCatching { client(server).fetch(day) }.isFailure)
        }
    }

    @Test fun fallbackHasNoInventedSessionQuarterOrAlignedEps() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setBody(dateScanFixture()))
            val result = client(server).fetch(day)
            val event = result.events.single()
            assertEquals("tradingview",event.source); assertEquals("unknown",event.session)
            assertNull(event.fiscalQuarterEnding); assertNull(event.epsForecast)
            assertNull(result.nasdaqUpdatedAt)
        }
    }

    @Test fun wrongDateFallbackCannotBecomeAnEmptySuccess() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setBody(dateScanFixture(day.plusDays(1))))
            assertTrue(runCatching { client(server).fetch(day) }.isFailure)
        }
    }

    @Test fun fallbackOnlyCacheCanRefreshWithoutNasdaq() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setBody(dateScanFixture()))
            val old = calendar().copy(nasdaqUpdatedAt = null, events = listOf(EarningsEvent("OLD", "Old", "tradingview")))
            val updated = client(server).fetch(day, old)
            assertFalse(updated.stale)
            assertEquals("AAPL", updated.events.single().symbol)
        }
    }

    @Test fun historicalAndReportedOccurrencesDoNotQueryNextPeriodForecasts() = runBlocking {
        for (reported in listOf(false, true)) MockWebServer().use { server ->
            val root = JsonParser.parseString(fixture("nasdaq")).asJsonObject
            val rows = root.getAsJsonObject("data").getAsJsonArray("rows")
            if (reported) rows.forEach { it.asJsonObject.addProperty("eps", "$1.50") }
            server.enqueue(MockResponse().setBody(root.toString()))
            val instant = if (reported) now else Instant.parse("2026-11-04T12:00:00Z")
            val client = EarningsClient(OkHttpClient(), nasdaqUrl=server.url("/nasdaq").toString(), tradingViewUrl=server.url("/tv").toString(), clock={instant})
            val result = client.fetch(day)
            assertEquals(1, server.requestCount)
            assertNull(result.tradingViewUpdatedAt)
            assertTrue(result.events.all { it.tradingView == null })
        }
    }

    @Test fun backendUsesExistingAuthenticationWithoutLeakingItToProviders() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Gson().toJson(calendar())))
            val backend = BackendClient(OkHttpClient(), Gson(), {server.url("/").toString().trimEnd('/')}, {"private-token"}, {BackendDeviceIdentity("installation", "android-hash")})
            assertEquals(day.toString(),backend.earnings(day,true).date)
            val request = server.takeRequest()
            assertEquals("/api/v1/earnings",request.requestUrl!!.encodedPath)
            assertEquals("true",request.requestUrl!!.queryParameter("refresh"))
            assertEquals("Bearer private-token",request.getHeader("Authorization"))
            assertEquals("installation",request.getHeader("X-Installation-Id"))
            assertEquals("android-hash",request.getHeader("X-Android-Id-Hash"))
        }
    }

    @Test fun providerRateLimitAppliesAcrossDates() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After","900"))
            server.enqueue(MockResponse().setBody(dateScanFixture()))
            val client = client(server)
            client.fetch(day)
            server.enqueue(MockResponse().setBody(dateScanFixture(day.plusDays(1))))
            client.fetch(day.plusDays(1))
            assertEquals(3,server.requestCount)
            assertEquals("/nasdaq",server.takeRequest().requestUrl!!.encodedPath)
            assertEquals("/tv",server.takeRequest().requestUrl!!.encodedPath)
            assertEquals("/tv",server.takeRequest().requestUrl!!.encodedPath)
        }
    }

    private class Store : EarningsStore {
        val rows = mutableMapOf<Pair<String,String>,String>()
        override suspend fun read(source: String,date: String) = rows[source to date]
        override suspend fun write(source: String,date: String,payload: String) { rows[source to date] = payload }
        override suspend fun prune(before: String) { rows.keys.removeAll { it.second < before } }
    }

    @Test fun repositorySeparatesModesAndBackendHostsWithoutSilentFallback() = runBlocking {
        val settings = MutableStateFlow(BackendSettings())
        val store = Store()
        var directCalls = 0; var backendCalls = 0
        val repo = EarningsRepository(settings,store,direct = { _,_ -> directCalls++; calendar("DIRECT") },backend = { _,_ -> backendCalls++; calendar("BACKEND") },clock={now})
        assertEquals("DIRECT",repo.load(day).events.single().symbol)
        repo.load(day); assertEquals(1,directCalls)
        settings.value = BackendSettings(mode=DataSourceMode.BACKEND,baseUrl="https://one.example",tokenConfigured=true)
        assertNull(repo.cached(day))
        assertEquals("BACKEND",repo.load(day).events.single().symbol)
        settings.value = settings.value.copy(baseUrl="https://two.example")
        assertNull(repo.cached(day)); repo.load(day)
        assertEquals(2,backendCalls); assertEquals(1,directCalls)
        assertEquals(3,store.rows.size)
        val failing = EarningsRepository(settings,Store(),direct={_,_->error("Must never fall back")},backend={_,_->throw IOException("offline")},clock={now})
        assertTrue(runCatching { failing.load(day) }.exceptionOrNull() is IOException)
    }

    @Test fun outageDoesNotOverwriteSuccessfulSnapshotAndManualRefreshIsBounded() = runBlocking {
        val settings = MutableStateFlow(BackendSettings())
        val store = Store(); var instant = now; var calls=0
        val repo = EarningsRepository(settings,store,direct={_,_->calls++;if(calls>1)throw IOException("offline") else calendar()},backend={_,_->error("unused")},clock={instant})
        repo.load(day)
        val original = store.rows.values.single()
        assertTrue(repo.load(day,true).warnings.contains("refresh_cooldown")); assertEquals(1,calls)
        instant=now.plusSeconds(61)
        val failed = repo.load(day,true)
        assertTrue(failed.stale); assertEquals(now.toString(),failed.updatedAt)
        assertEquals(original,store.rows.values.single())
    }

    @Test fun repeatedColdFailuresAreAlsoBounded() = runBlocking {
        var calls = 0
        val repo = EarningsRepository(MutableStateFlow(BackendSettings()),Store(),direct={_,_->calls++;throw IOException("offline")},backend={_,_->error("unused")},clock={now})
        assertTrue(runCatching { repo.load(day) }.isFailure)
        assertTrue(runCatching { repo.load(day,true) }.isFailure)
        assertEquals(1,calls)
    }

    @Test fun sourceChangeDiscardsAnInflightResponse() = runBlocking {
        val settings = MutableStateFlow(BackendSettings())
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>(); val store=Store()
        val repo = EarningsRepository(settings,store,direct={_,_->started.complete(Unit);finish.await();calendar()},backend={_,_->calendar()},clock={now})
        val pending = async { runCatching { repo.load(day) } }
        started.await(); settings.value=BackendSettings(mode=DataSourceMode.BACKEND,baseUrl="https://other.example")
        finish.complete(Unit)
        assertTrue(pending.await().exceptionOrNull() is CancellationException)
        assertTrue(store.rows.isEmpty())
    }

    @Test fun filtersAndBackendWireContractMatch() {
        val events = parseNasdaqEarnings(fixture("nasdaq"),day)
        assertEquals("AAPL",filterEarnings(events,"apple", "after_hours").single().symbol)
        assertTrue(filterEarnings(events,"apple","pre_market").isEmpty())
        assertEquals(1,filterEarnings(events,"","unknown").size)
        val root = Gson().toJson(calendar())
        assertEquals(calendar(),decodeEarningsCalendar(root,day))
        assertTrue(runCatching { decodeEarningsCalendar(root,day.plusDays(1)) }.isFailure)
        assertEquals(6,earningsTtl(day,now).toHours())
        assertEquals(24,earningsTtl(day.plusDays(30),now).toHours())
    }
}
