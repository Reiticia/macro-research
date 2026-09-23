package com.macroresearch.data.model

import java.time.Instant

/** Recompute time-sensitive state on cache reads as well as fresh provider responses. */
fun hasEventTimeArrived(eventTime: String, now: Instant = Instant.now()): Boolean =
    runCatching { !Instant.parse(eventTime).isAfter(now) }.getOrDefault(false)

fun releaseStatus(actual: String?, eventTime: Instant, now: Instant = Instant.now()): String = when {
    actual.isNullOrBlank() && !eventTime.isAfter(now) -> "data_unavailable"
    actual.isNullOrBlank() -> "scheduled"
    eventTime.isBefore(now.minusSeconds(86_400)) -> "historical"
    else -> "released"
}

fun EconomicEvent.currentStatus(now: Instant = Instant.now()): String {
    val time = runCatching { Instant.parse(eventTime) }.getOrNull() ?: return status
    if (!actual.isNullOrBlank() && status in setOf("completed", "collecting_market_data", "analyzing")) return status
    return releaseStatus(actual, time, now)
}
