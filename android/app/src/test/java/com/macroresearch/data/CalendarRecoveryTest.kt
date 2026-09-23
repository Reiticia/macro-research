package com.macroresearch.data

import com.macroresearch.data.local.CachedEventEntity
import com.macroresearch.data.local.asExternalModel
import com.macroresearch.data.local.fillMissingValues
import com.macroresearch.data.local.mergeCalendarRows
import com.macroresearch.data.model.currentStatus
import com.macroresearch.data.model.hasEventTimeArrived
import com.macroresearch.data.model.releaseStatus
import com.macroresearch.data.remote.EconomicCalendarClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class CalendarRecoveryTest {
    private val time = Instant.parse("2026-09-10T12:30:00Z")
    private val now = Instant.parse("2026-09-11T12:21:00Z") // Less than 24h, but already elapsed.

    @Test fun analysisEligibilityBeginsAtTheScheduledEventTimeEvenWithoutAnActual() {
        val eventTime = time.toString()
        assertFalse(hasEventTimeArrived(eventTime, time.minusSeconds(1)))
        assertTrue(hasEventTimeArrived(eventTime, time))
        assertTrue(hasEventTimeArrived(eventTime, time.plusSeconds(1)))
        assertFalse(hasEventTimeArrived("invalid-time", time))
    }

    @Test fun elapsedWithoutActualIsNotScheduledOrReleased() {
        assertEquals("data_unavailable", releaseStatus(null, time, now))
        assertEquals("data_unavailable", releaseStatus(null, time, time))
        assertEquals("data_unavailable", releaseStatus(null, time, now.plusSeconds(86_400)))
        assertEquals("scheduled", releaseStatus(null, now.plusSeconds(1), now))
        assertEquals("released", releaseStatus("0", time, now))
        assertEquals("historical", releaseStatus("0.2", time, now.plusSeconds(86_400)))
    }

    @Test fun oldCachedStatusIsRecomputedWithoutRemovingTheEvent() {
        val cached = row().asExternalModel()
        assertEquals("data_unavailable", cached.currentStatus(now))
        assertEquals(1L, cached.id)
        assertEquals("released", cached.copy(actual = "0.2").currentStatus(now))
    }

    @Test fun primaryHydratesFallbackKeepingIdsAndUserTranslation() {
        val fallback = row().copy(eventZhCn = "核心PPI月率")
        val primary = row().copy(id = 2, provider = "trading_view", providerId = "398283",
            event = "Core PPI MoM", actual = "0.2", status = "released")
        val merged = mergeCalendarRows(listOf(primary), listOf(fallback)).single()
        assertEquals(1L, merged.id)
        assertEquals("trading_view", merged.provider)
        assertEquals("0.2", merged.actual)
        assertEquals("核心PPI月率", merged.eventZhCn)
        // Later primary refreshes retain the migrated id, including value revisions.
        val revised = mergeCalendarRows(listOf(primary.copy(actual = "0.3")), listOf(merged)).single()
        assertEquals(1L, revised.id)
        assertEquals("0.3", revised.actual)
        // A weekly fallback or an incomplete response must not erase published values.
        assertEquals(merged, mergeCalendarRows(listOf(fallback), listOf(merged)).single())
        assertEquals("0.2", mergeCalendarRows(listOf(primary.copy(actual = null)), listOf(merged)).single().actual)
    }

    @Test fun matchingDoesNotConfuseCountriesPeriodsOrAmbiguousOccurrences() {
        val fallback = row()
        val primary = fallback.copy(id = 2, provider = "trading_view", providerId = "398283", event = "Core PPI MoM", actual = "0.2")
        for (other in listOf(fallback.copy(country = "China"), fallback.copy(event = "Core PPI y/y"),
            fallback.copy(eventTime = time.plusSeconds(60).toString()))) {
            assertEquals(2L, mergeCalendarRows(listOf(primary), listOf(other)).single().id)
        }
        assertEquals(2L, mergeCalendarRows(listOf(primary), listOf(fallback, fallback.copy(id = 3))).single().id)
        assertEquals("0.2", primary.actual)
    }

    @Test fun fallbackRowsAreFilledFromTheAliasedPrimaryOccurrence() {
        // Regression: the weekly feed calls the same release "Core CPI m/m" while the primary
        // source calls it "Core Inflation Rate MoM". Title-only matching left the fallback row
        // permanently empty even though the value was published.
        val fallback = row().copy(event = "Core CPI m/m", eventZhCn = "核心CPI月率", actual = null)
        val primary = row().copy(id = 2, provider = "trading_view", providerId = "398283",
            event = "Core Inflation Rate MoM", actual = "0.3", consensus = "0.2", forecast = "0.2",
            status = "released")

        val filled = fillMissingValues(listOf(fallback, primary)).single()

        assertEquals(1L, filled.id)
        assertEquals("forex_factory", filled.provider)
        assertEquals("Core CPI m/m", filled.event)
        assertEquals("核心CPI月率", filled.eventZhCn)
        assertEquals("0.3", filled.actual)
        assertEquals("0.2", filled.consensus)
        assertEquals("released", filled.status)
        // Rows that already hold a value, and the primary row itself, are left alone.
        assertTrue(fillMissingValues(listOf(filled, primary)).isEmpty())
        assertTrue(fillMissingValues(listOf(row().copy(actual = "9"), primary)).isEmpty())
    }

    @Test fun fillingToleratesRowsRepeatedByTheMergeCaller() {
        // Regression: mergeCalendar passes `merged + cached`, so the primary row appears twice.
        // Treating that as ambiguity silently disabled every fill on the device.
        val fallback = row().copy(event = "Core CPI m/m", actual = null)
        val primary = row().copy(id = 2, provider = "trading_view", providerId = "398283",
            event = "Core Inflation Rate MoM", actual = "0.3")

        val filled = fillMissingValues(listOf(primary, fallback, primary)).single()
        assertEquals(1L, filled.id)
        assertEquals("0.3", filled.actual)
        // Genuinely different primary rows for one occurrence must still block the fill.
        assertTrue(
            fillMissingValues(listOf(fallback, primary, primary.copy(id = 3, providerId = "b", actual = "0.9")))
                .isEmpty(),
        )
    }

    @Test fun fillingRequiresOneUnambiguousPrimaryMatch() {
        val fallback = row().copy(event = "Core CPI m/m", actual = null)
        val primary = row().copy(id = 2, provider = "trading_view", providerId = "a",
            event = "Core Inflation Rate MoM", actual = "0.3")

        // Two primary rows for the same occurrence: ambiguous, so nothing is attached.
        assertTrue(fillMissingValues(listOf(fallback, primary, primary.copy(id = 3, providerId = "b"))).isEmpty())
        // A primary row without a value cannot fill anything.
        assertTrue(fillMissingValues(listOf(fallback, primary.copy(actual = null))).isEmpty())
        // Unrelated titles never match, even at the same instant.
        assertTrue(fillMissingValues(listOf(fallback, primary.copy(event = "Core PPI MoM"))).isEmpty())
        // A different release time is a different occurrence.
        assertTrue(fillMissingValues(listOf(fallback, primary.copy(eventTime = time.plusSeconds(60).toString()))).isEmpty())
        // A different country is a different occurrence.
        assertTrue(fillMissingValues(listOf(fallback, primary.copy(country = "China"))).isEmpty())
        // The primary row is never filled from the weekly schedule.
        val missingPrimary = primary.copy(actual = null)
        assertTrue(fillMissingValues(listOf(missingPrimary, row().copy(actual = "0.9"))).isEmpty())
    }

    @Test fun successfulUpcomingSyncUsesABoundedFreshnessWindow() {
        assertFalse(isFresh(savedAtMs = 0, nowMs = 1_000, maxAgeMs = 300_000))
        assertTrue(isFresh(savedAtMs = 1_000, nowMs = 1_001, maxAgeMs = 300_000))
        assertFalse(isFresh(savedAtMs = 1_000, nowMs = 301_000, maxAgeMs = 300_000))
        // A clock change must not make a future timestamp fresh forever.
        assertFalse(isFresh(savedAtMs = 2_000, nowMs = 1_000, maxAgeMs = 300_000))
    }

    @Test fun proxyIsOptionalScopedAndValidated() {
        assertNull(httpProxy(""))
        assertEquals("http://127.0.0.1:17890", normalizeProxyAddress(" 127.0.0.1:17890 "))
        assertEquals(Proxy.Type.HTTP, httpProxy("localhost:8080")!!.type())
        for (invalid in listOf("host", "host:0", "host:65536", "https://host:443", "user:pass@host:8080", "host:8080/path")) {
            assertTrue(invalid, runCatching { normalizeProxyAddress(invalid) }.isFailure)
        }
    }

    @Test fun configuredProxyIsUsedWithoutChangingTheSharedClient() = runBlocking {
        MockWebServer().use { proxy ->
            proxy.enqueue(MockResponse().setBody("""{"status":"ok","result":[{"id":"398283","title":"Core PPI MoM","country":"US","date":"2026-09-10T12:30:00Z","actual":0.2}]}"""))
            proxy.start()
            val shared = OkHttpClient()
            val calendar = EconomicCalendarClient(shared, ZoneOffset.UTC,
                proxy = { Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxy.port)) },
                primaryUrl = "http://calendar.invalid/events")
            val day = LocalDate.of(2026, 9, 10)
            assertEquals("0.2", calendar.fetch(day, day).events.single().actual)
            assertTrue(proxy.takeRequest().requestLine.startsWith("GET http://calendar.invalid/events?"))
            assertNull(shared.proxy)
        }
    }

    @Test fun scheduleFallbackReportsDegradationAndRetryGetsActuals() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setBody("""[{"title":"Core PPI m/m","country":"USD","date":"2026-09-10T08:30:00-04:00","forecast":"0.3%","previous":"0.2%"}]"""))
            server.enqueue(MockResponse().setBody("""{"status":"ok","result":[{"id":"398283","title":"Core PPI MoM","country":"US","date":"2026-09-10T12:30:00Z","actual":0.2,"forecast":0.3,"unit":"%"}]}"""))
            server.start()
            val client = EconomicCalendarClient(OkHttpClient(), ZoneOffset.UTC,
                primaryUrl = server.url("/primary").toString(), fallbackUrl = server.url("/fallback").toString())
            val day = LocalDate.of(2026, 9, 10)
            val degraded = client.fetch(day, day)
            assertEquals(1, degraded.events.size)
            assertNull(degraded.events.single().actual)
            assertNotNull(degraded.warning)
            val recovered = client.fetch(day, day)
            assertNull(recovered.warning)
            assertEquals("0.2", recovered.events.single().actual)
            assertEquals("no-cache", server.takeRequest().getHeader("Cache-Control"))
            assertEquals("/fallback", server.takeRequest().path)
            assertTrue(server.takeRequest().path!!.startsWith("/primary"))
        }
    }

    @Test fun rateLimitedFallbackIsReportedAndNotRetriedWhileTheWaitLasts() = runBlocking {
        var primaryHits = 0
        var fallbackHits = 0
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path.orEmpty().startsWith("/primary") -> {
                        primaryHits++
                        MockResponse().setResponseCode(503)
                    }

                    else -> {
                        fallbackHits++
                        MockResponse().setResponseCode(429).setHeader("Retry-After", "300")
                    }
                }
            }
            server.start()
            val client = EconomicCalendarClient(
                OkHttpClient(),
                ZoneOffset.UTC,
                primaryUrl = server.url("/primary").toString(),
                fallbackUrl = server.url("/fallback").toString(),
            )
            val day = LocalDate.of(2026, 9, 10)

            val first = runCatching { client.fetch(day, day) }.exceptionOrNull()
            assertTrue("expected a typed calendar failure, got $first", first is CalendarUnavailableException)
            val warning = (first as CalendarUnavailableException).warning
            assertEquals(CalendarWarning.Reason.FALLBACK_RATE_LIMITED, warning.reason)
            assertEquals(300L, warning.retryAfterSeconds)
            assertEquals(1, fallbackHits)

            // The provider asked for a five minute wait. The next refresh must not spend a
            // request on a guaranteed 429, but must still explain the wait to the user.
            val second = runCatching { client.fetch(day, day) }.exceptionOrNull()
            assertTrue(second is CalendarUnavailableException)
            val repeat = (second as CalendarUnavailableException).warning
            assertEquals(CalendarWarning.Reason.FALLBACK_RATE_LIMITED, repeat.reason)
            assertTrue("remaining wait should still be positive", (repeat.retryAfterSeconds ?: 0L) > 0L)
            assertEquals(1, fallbackHits)
            assertEquals(2, primaryHits)
        }
    }

    @Test fun exhaustedSourcesKeepTheFallbackReasonInsteadOfThePrimaryTimeout() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setResponseCode(500))
            server.start()
            val client = EconomicCalendarClient(
                OkHttpClient(),
                ZoneOffset.UTC,
                primaryUrl = server.url("/primary").toString(),
                fallbackUrl = server.url("/fallback").toString(),
            )

            val failure = runCatching {
                client.fetch(LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 10))
            }.exceptionOrNull()
            assertTrue(failure is CalendarUnavailableException)
            val warning = (failure as CalendarUnavailableException).warning
            assertEquals(CalendarWarning.Reason.ALL_SOURCES_UNAVAILABLE, warning.reason)
            // Both providers are named so the log explains what actually blocked the refresh.
            assertTrue(warning.detail.orEmpty().contains("primary="))
            assertTrue(warning.detail.orEmpty().contains("fallback="))
        }
    }

    @Test fun anActivePrimaryBackoffSkipsTheBrokenRouteAndUsesFallback() = runBlocking {
        var primaryHits = 0
        var fallbackHits = 0
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path.orEmpty().startsWith("/primary") -> {
                        primaryHits++
                        MockResponse().setResponseCode(503)
                    }

                    else -> {
                        fallbackHits++
                        MockResponse().setBody(
                            """[{"title":"Core PPI m/m","country":"USD","date":"2026-09-10T08:30:00-04:00","forecast":"0.3%"}]""",
                        )
                    }
                }
            }
            server.start()
            val backoff = CalendarBackoffStore.inMemory()
                .apply { block(CalendarSource.PRIMARY, Instant.now().plusSeconds(120)) }
            val client = EconomicCalendarClient(
                OkHttpClient(),
                ZoneOffset.UTC,
                primaryUrl = server.url("/primary").toString(),
                fallbackUrl = server.url("/fallback").toString(),
                backoff = backoff,
            )

            val result = client.fetch(LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 10))
            assertEquals(0, primaryHits)
            assertEquals(1, fallbackHits)
            assertEquals(1, result.events.size)
            assertEquals(CalendarWarning.Reason.PRIMARY_UNAVAILABLE, result.warning?.reason)
        }
    }

    @Test fun anActiveBackoffSkipsTheFallbackWithoutSpendingARequest() = runBlocking {
        var fallbackHits = 0
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path.orEmpty().startsWith("/primary") -> MockResponse().setResponseCode(503)

                    else -> {
                        fallbackHits++
                        MockResponse().setBody("[]")
                    }
                }
            }
            server.start()
            // A previous run was told to wait. A cold start must honour that instead of spending
            // another request on a guaranteed 429.
            val backoff = CalendarBackoffStore.inMemory()
                .apply { block(CalendarSource.FALLBACK, Instant.now().plusSeconds(120)) }
            val client = EconomicCalendarClient(
                OkHttpClient(),
                ZoneOffset.UTC,
                primaryUrl = server.url("/primary").toString(),
                fallbackUrl = server.url("/fallback").toString(),
                backoff = backoff,
            )

            val failure = runCatching {
                client.fetch(LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 10))
            }.exceptionOrNull()
            assertTrue(failure is CalendarUnavailableException)
            val warning = (failure as CalendarUnavailableException).warning
            assertEquals(CalendarWarning.Reason.FALLBACK_RATE_LIMITED, warning.reason)
            assertTrue("the remaining wait should be reported", (warning.retryAfterSeconds ?: 0L) > 0L)
            assertEquals("the fallback must not be called while the back-off holds", 0, fallbackHits)
        }
    }

    private fun row() = CachedEventEntity(
        id = 1, provider = "forex_factory", providerId = "ff-core-ppi",
        releaseGroupId = null, country = "United States", currency = "USD", category = "inflation",
        event = "Core PPI m/m", eventZhCn = null, eventZhTw = null, eventTime = time.toString(),
        importance = 3, actual = null, previous = "0.2", consensus = "0.3", forecast = "0.3",
        unit = "%", status = "scheduled",
    )
}
