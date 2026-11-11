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

    @Test fun ordinaryCacheCommitsRejectOldSourcesIncludingEmptyResponseFreshnessMarkers() = runBlocking {
        for (rows in listOf(emptyList(), listOf("old event"))) {
            val gate = ReleaseRefreshGate()
            val entered = CompletableDeferred<Unit>()
            val response = CompletableDeferred<List<String>>()
            var cache = listOf("old cache")
            var synced = false
            val request = async {
                val generation = gate.generation.value
                entered.complete(Unit)
                val fetched = response.await()
                runCatching { gate.commit(generation) { cache = fetched; synced = true } }
            }
            entered.await()
            gate.commitMutex.withLock { gate.invalidate(); cache = emptyList(); synced = false }
            response.complete(rows)
            assertTrue(request.await().exceptionOrNull() is CancellationException)
            assertTrue(cache.isEmpty())
            assertFalse(synced)
            gate.commit(gate.generation.value) { cache = listOf("new event"); synced = true }
            assertEquals(listOf("new event"), cache)
            assertTrue(synced)
        }
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
