package com.macroresearch.data

import com.macroresearch.data.model.EconomicEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal data class ReleaseRefreshOutcome(val successful: Boolean, val retryAt: Instant? = null)

/** One front-of-app poller, not one poller per card. Injected clock/transport make scheduling testable. */
internal class ForegroundReleaseCoordinator(
    private val candidates: suspend (Instant) -> List<EconomicEvent>,
    private val refresh: suspend (List<EconomicEvent>) -> ReleaseRefreshOutcome,
    private val groupingZone: () -> ZoneId,
    private val now: () -> Instant = Instant::now,
) {
    private data class Attempt(val next: Instant, val failures: Int)
    private val attempts = mutableMapOf<LocalDate, Attempt>()

    fun reset() = attempts.clear()

    suspend fun run() {
        while (currentCoroutineContext().isActive) {
            tick()
            delay(5_000)
        }
    }

    suspend fun tick() {
        val instant = now()
        val rows = try {
            candidates(instant)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return // A transient cache read failure must not permanently stop the foreground loop.
        }
        val groups = rows.filter { event ->
            val time = runCatching { Instant.parse(event.eventTime) }.getOrNull()
            event.actual.isNullOrBlank() && time != null && !time.isAfter(instant) &&
                Duration.between(time, instant) < Duration.ofMinutes(30)
        }.groupBy { Instant.parse(it.eventTime).atZone(groupingZone()).toLocalDate() }
        attempts.keys.retainAll(groups.keys)
        for ((day, events) in groups) {
            val previous = attempts[day]
            if (previous != null && now().isBefore(previous.next)) continue
            val outcome = try {
                refresh(events)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ReleaseRefreshOutcome(false)
            }
            val failures = if (outcome.successful) 0 else ((previous?.failures ?: 0) + 1).coerceAtMost(3)
            val seconds = if (failures == 0) 15L else 30L * (1L shl (failures - 1))
            val next = maxOf(now().plusSeconds(seconds), outcome.retryAt ?: Instant.MIN)
            attempts[day] = Attempt(next, failures)
        }
    }
}
