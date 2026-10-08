package com.macroresearch.data

import com.macroresearch.data.model.EconomicEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class ForegroundReleaseCoordinatorTest {
    private val release = Instant.parse("2026-09-10T12:30:00Z")
    private fun event(id: Long = 1, time: Instant = release, actual: String? = null) = EconomicEvent(
        id, "trading_view", "$id", null, "United States", "USD", "inflation", "CPI MoM",
        eventTime = time.toString(), importance = 3, actual = actual, previous = "0.2",
        consensus = "0.3", forecast = "0.3", unit = "%", status = "scheduled",
    )

    @Test fun startsAtReleaseBatchesSameDayAndWaitsFifteenSeconds() = runBlocking {
        var clock = release.minusSeconds(1)
        val requests = mutableListOf<List<EconomicEvent>>()
        val coordinator = ForegroundReleaseCoordinator({ listOf(event(), event(2)) }, {
            requests += it; ReleaseRefreshOutcome(true)
        }, { ZoneOffset.UTC }, { clock })
        coordinator.tick()
        assertTrue(requests.isEmpty())
        clock = release
        coordinator.tick()
        assertEquals(listOf(1L, 2L), requests.single().map { it.id })
        clock = clock.plusSeconds(14)
        coordinator.tick()
        assertEquals(1, requests.size)
        clock = clock.plusSeconds(1)
        coordinator.tick()
        assertEquals(2, requests.size)
    }

    @Test fun stopsWhenActualArrivesAndAtThirtyMinuteDeadline() = runBlocking {
        var clock = release
        var rows = listOf(event())
        var requests = 0
        val coordinator = ForegroundReleaseCoordinator({ rows }, {
            requests++; ReleaseRefreshOutcome(true)
        }, { ZoneOffset.UTC }, { clock })
        coordinator.tick()
        rows = listOf(event(actual = "0.4"))
        clock = clock.plusSeconds(15)
        coordinator.tick()
        assertEquals(1, requests)
        rows = listOf(event())
        clock = release.plusSeconds(1800)
        coordinator.tick()
        assertEquals(1, requests)
    }

    @Test fun failuresBackOffThirtySixtyOneHundredTwentyAndSuccessResets() = runBlocking {
        var clock = release
        var requests = 0
        var fail = true
        val coordinator = ForegroundReleaseCoordinator({ listOf(event()) }, {
            requests++
            if (fail) error("offline")
            ReleaseRefreshOutcome(true)
        }, { ZoneOffset.UTC }, { clock })
        coordinator.tick()
        for ((delay, count) in listOf(30L to 1, 60L to 2, 120L to 3, 120L to 4)) {
            clock = clock.plusSeconds(delay - 1)
            coordinator.tick()
            assertEquals(count, requests)
            clock = clock.plusSeconds(1)
            coordinator.tick()
            assertEquals(count + 1, requests)
        }
        fail = false
        clock = clock.plusSeconds(120)
        coordinator.tick()
        val successful = requests
        clock = clock.plusSeconds(15)
        coordinator.tick()
        assertEquals(successful + 1, requests)
    }

    @Test fun providerRetryAfterWinsOverPollCadence() = runBlocking {
        var clock = release
        var requests = 0
        val coordinator = ForegroundReleaseCoordinator({ listOf(event()) }, {
            requests++; ReleaseRefreshOutcome(false, release.plusSeconds(300))
        }, { ZoneOffset.UTC }, { clock })
        coordinator.tick()
        clock = release.plusSeconds(299)
        coordinator.tick()
        assertEquals(1, requests)
        clock = release.plusSeconds(300)
        coordinator.tick()
        assertEquals(2, requests)
    }

    @Test fun utcAndDeviceDayGroupingHandleMidnightDifferently() = runBlocking {
        val times = listOf(Instant.parse("2026-09-10T23:59:00Z"), Instant.parse("2026-09-11T00:00:00Z"))
        for ((zone, groups) in listOf(ZoneOffset.UTC to 2, ZoneId.of("Asia/Shanghai") to 1)) {
            var requests = 0
            val coordinator = ForegroundReleaseCoordinator({ times.mapIndexed { index, time -> event(index.toLong(), time) } }, {
                requests++; ReleaseRefreshOutcome(true)
            }, { zone }, { times.last() })
            coordinator.tick()
            assertEquals(groups, requests)
        }
    }

    @Test fun cancellationPropagatesAndResumeChecksImmediatelyWithoutOverlapping() = runBlocking {
        var clock = release
        var requests = 0
        val entered = CompletableDeferred<Unit>()
        var suspendRequest = true
        val coordinator = ForegroundReleaseCoordinator({ listOf(event()) }, {
            requests++
            if (suspendRequest) { entered.complete(Unit); awaitCancellation() }
            ReleaseRefreshOutcome(true)
        }, { ZoneOffset.UTC }, { clock })
        val job = launch { coordinator.run() }
        entered.await()
        job.cancelAndJoin()
        assertEquals(1, requests)
        suspendRequest = false
        coordinator.tick() // Resume: a cancelled request did not become a failure/backoff.
        assertEquals(2, requests)
        coordinator.tick()
        assertEquals(2, requests)
        clock = clock.plusSeconds(1)
        coordinator.reset() // Source generation changed, discarding the old plane's cooldown.
        coordinator.tick()
        assertEquals(3, requests)
    }
}
