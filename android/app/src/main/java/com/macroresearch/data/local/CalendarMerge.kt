package com.macroresearch.data.local

import java.time.Instant
import java.util.Locale

/** Keep saved IDs/follows when a weekly fallback event is later hydrated from the primary. */
internal fun mergeCalendarRows(
    incoming: List<CachedEventEntity>,
    cached: List<CachedEventEntity>,
): List<CachedEventEntity> {
    val sources = cached.groupBy { it.provider to it.providerId }
    val occurrences = cached.groupBy { it.occurrenceKey() }
    val incomingOccurrences = incoming.groupingBy { it.occurrenceKey() }.eachCount()
    return incoming.map { fresh ->
        val key = fresh.occurrenceKey()
        val previous = sources[fresh.provider to fresh.providerId]?.singleOrNull()
            ?: if (key != null && incomingOccurrences[key] == 1) {
                occurrences[key]?.singleOrNull()?.takeIf {
                    it.timeExact && fresh.timeExact && it.provider != fresh.provider &&
                        providerPriority(it.provider) > 0 && providerPriority(fresh.provider) > 0
                }
            } else null
        if (previous == null) return@map fresh
        // Never demote primary observations. A fallback can still fill a missing release.
        if (providerPriority(previous.provider) > providerPriority(fresh.provider)) {
            return@map if (previous.actual != null || fresh.actual == null) previous else previous.copy(
                actual = fresh.actual, previous = fresh.previous, consensus = fresh.consensus,
                forecast = fresh.forecast, unit = fresh.unit, status = fresh.status,
            )
        }
        val merged = fresh.copy(
            id = previous.id,
            eventZhCn = previous.eventZhCn ?: fresh.eventZhCn,
            eventZhTw = previous.eventZhTw ?: fresh.eventZhTw,
        )
        if (fresh.actual == null && previous.actual != null) {
            merged.copy(actual = previous.actual, previous = previous.previous,
                consensus = previous.consensus, forecast = previous.forecast,
                unit = previous.unit, status = previous.status)
        } else merged
    }
}

/**
 * Copies published values from a primary occurrence onto the weekly-schedule row of the same
 * occurrence. The fallback feed uses different titles for the same indicator, so without this
 * step those rows stay permanently empty even though the value is available.
 *
 * Only rows that need a value are returned, and only when exactly one primary row matches
 * (country + exact instant + canonical title) and that row actually carries a value. Names,
 * IDs and corrected translations are preserved, and no row is ever deleted.
 */
internal fun fillMissingValues(rows: List<CachedEventEntity>): List<CachedEventEntity> {
    // Callers pass the freshly merged rows plus the cached rows, so the same row can appear
    // twice. Without de-duplicating by id, every primary row looks ambiguous and nothing is
    // ever filled.
    val unique = rows.distinctBy { it.id }
    val byKey = unique.groupBy { it.occurrenceKey() }
    return unique.mapNotNull { target ->
        if (target.provider != FOREX_FACTORY || !target.actual.isNullOrBlank()) return@mapNotNull null
        val key = target.occurrenceKey() ?: return@mapNotNull null
        // Exactly one primary row may match; ambiguity leaves the row untouched.
        val candidates = byKey[key].orEmpty().filter { providerPriority(it.provider) > 1 && !it.actual.isNullOrBlank() }
        val priority = candidates.maxOfOrNull { providerPriority(it.provider) }
        val source = candidates.filter { providerPriority(it.provider) == priority }.singleOrNull()
            ?: return@mapNotNull null
        target.copy(
            actual = source.actual,
            previous = source.previous,
            consensus = source.consensus,
            forecast = source.forecast,
            unit = source.unit,
            status = source.status,
        )
    }
}

internal fun CachedEventEntity.occurrenceKey(): Triple<String, Instant, String>? {
    if (!timeExact) return null
    val time = runCatching { Instant.parse(eventTime) }.getOrNull() ?: return null
    val name = event.lowercase(Locale.ROOT)
        .replace("m/m", "mom").replace("y/y", "yoy").replace("q/q", "qoq")
        .replace(Regex("[^a-z0-9]"), "")
    return Triple(country, time, TITLE_ALIASES[name] ?: name)
}

private fun providerPriority(provider: String): Int = when (provider) {
    "biquote" -> 2
    "trading_view" -> 3
    "forex_factory" -> 1
    else -> 0
}
private const val FOREX_FACTORY = "forex_factory"

/**
 * Titles the weekly fallback feed and the primary source use for the same indicator. Every
 * entry was verified against both feeds for the same country and exact release instant; the
 * value must match on exactly one primary row, otherwise nothing is filled. Never add a
 * guess here: a wrong alias would attach another indicator's value to the row.
 */
private val TITLE_ALIASES = mapOf(
    "corecpimom" to "coreinflationratemom",
    "cpimom" to "inflationratemom",
    "corecpiyoy" to "coreinflationrateyoy",
    "cpiyoy" to "inflationrateyoy",
    "crudeoilinventories" to "eiacrudeoilstockschange",
    "naturalgasstorage" to "eianaturalgasstockschange",
    "finalwholesaleinventoriesmom" to "wholesaleinventoriesmom",
    "prelimuomconsumersentiment" to "michiganconsumersentimentprel",
    "prelimuominflationexpectations" to "michiganinflationexpectationsprel",
    "nfibsmallbusinessindex" to "nfibbusinessoptimismindex",
    "consumercreditmom" to "consumercreditchange",
    "adpweeklyemploymentchange" to "adpemploymentchangeweekly",
    "unemploymentclaims" to "initialjoblessclaims",
    // Same release: ISM renamed its Non-Manufacturing report to Services PMI, and the impact
    // rule table already treats both titles as one indicator.
    "ismnonmanufacturingpmi" to "ismservicespmi",
)
