package com.macroresearch.data

import com.macroresearch.data.remote.CalendarFetchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class ReleaseRefreshGateTest {
    private val day = LocalDate.parse("2026-09-10")

    @Test fun concurrentManualAndAutomaticRefreshesShareOneRequestAndFifteenSecondCooldown() = runBlocking {
        var now = Instant.parse("2026-09-10T12:30:00Z")
        val gate = ReleaseRefreshGate { now }
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        var requests = 0
        var commits = 0
        suspend fun refresh() = gate.refresh(0, day, {
            requests++
            entered.complete(Unit)
            response.await()
            CalendarFetchResult(emptyList())
        }, { commits++ })
        val automatic = async { refresh() }
        entered.await()
        val manual = async { refresh() }
        response.complete(Unit)
        automatic.await()
        manual.await()
        assertEquals(1, requests)
        assertEquals(1, commits)
        now = now.plusSeconds(15)
        refresh()
        assertEquals(2, requests)
        assertEquals(2, commits)
    }

    @Test fun delayedOldPlaneResponseCannotRepopulateClearedCache() = runBlocking {
        val gate = ReleaseRefreshGate()
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        var cached = "old"
        val old = async {
            runCatching { gate.refresh(0, day, {
                entered.complete(Unit)
                response.await()
                CalendarFetchResult(emptyList())
            }, { cached = "late old result" }) }
        }
        entered.await()
        gate.commitMutex.withLock {
            gate.invalidate()
            cached = "cleared"
        }
        response.complete(Unit)
        assertTrue(old.await().exceptionOrNull() is CancellationException)
        assertEquals("cleared", cached)
        gate.refresh(gate.generation.value, day, { CalendarFetchResult(emptyList()) }, { cached = "new" })
        assertEquals("new", cached)
    }

    @Test fun failedAndCancelledResponsesNeitherCommitNorBecomeSuccessfulCooldowns() = runBlocking {
        val gate = ReleaseRefreshGate()
        var commits = 0
        for (cancelled in listOf(false, true)) {
            val result = runCatching { gate.refresh(0, day, {
                if (cancelled) throw CancellationException("paused") else error("network failure")
            }, { commits++ }) }
            assertTrue(result.isFailure)
        }
        assertEquals(0, commits)
        gate.refresh(0, day, { CalendarFetchResult(emptyList()) }, { commits++ })
        assertEquals(1, commits)
    }
}
