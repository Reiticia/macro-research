package com.macroresearch.data

import com.macroresearch.data.remote.CalendarFetchResult

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate

/** Shared automatic/manual gate. Late results cannot cross a source-generation boundary. */
internal class ReleaseRefreshGate(private val now: () -> Instant = Instant::now) {
    private val mutableGeneration = MutableStateFlow(0L)
    val generation = mutableGeneration.asStateFlow()
    val commitMutex = Mutex()
    private val requestMutex = Mutex()
    private val fetchedAt = mutableMapOf<Pair<Long, LocalDate>, Instant>()

    fun invalidate() { mutableGeneration.update { it + 1 } }

    suspend fun refresh(
        generation: Long,
        day: LocalDate,
        fetch: suspend () -> CalendarFetchResult,
        commit: suspend (CalendarFetchResult) -> Unit,
    ): ReleaseRefreshOutcome = requestMutex.withLock {
        currentCoroutineContext().ensureActive()
        checkGeneration(generation)
        val key = generation to day
        val instant = now()
        fetchedAt.entries.removeAll { it.key.first != generation || !it.value.isAfter(instant.minusSeconds(15)) }
        if (fetchedAt.containsKey(key)) return@withLock ReleaseRefreshOutcome(true)
        val result = fetch()
        currentCoroutineContext().ensureActive()
        commitMutex.withLock {
            checkGeneration(generation)
            commit(result)
        }
        fetchedAt[key] = now()
        ReleaseRefreshOutcome(true)
    }

    private fun checkGeneration(expected: Long) {
        if (expected != mutableGeneration.value) throw CancellationException("Data source changed")
    }
}
