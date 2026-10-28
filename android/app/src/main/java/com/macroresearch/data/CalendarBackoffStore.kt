package com.macroresearch.data

import android.content.Context
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Calendar providers whose retry windows are tracked independently. */
enum class CalendarSource {
    PRIMARY,
    TRADING_VIEW,
    FALLBACK,
}

/**
 * Remembers provider retry windows.
 *
 * Windows are persisted on Android: a cold start would otherwise repeat a known timeout or 429.
 * Keeping providers separate means a failing primary never prevents the fallback from running.
 */
interface CalendarBackoffStore {
    /** Instant before which [source] must not be called again, or null when it may. */
    fun blockedUntil(source: CalendarSource): Instant?

    fun block(source: CalendarSource, until: Instant)

    fun clear(source: CalendarSource)

    companion object {
        /** Isolated store for tests and callers that do not need process-restart persistence. */
        fun inMemory(): CalendarBackoffStore = object : CalendarBackoffStore {
            private val deadlines = ConcurrentHashMap<CalendarSource, Instant>()

            override fun blockedUntil(source: CalendarSource): Instant? = deadlines[source]

            override fun block(source: CalendarSource, until: Instant) {
                deadlines[source] = until
            }

            override fun clear(source: CalendarSource) {
                deadlines.remove(source)
            }
        }
    }
}

/** Process-restart-safe per-provider back-off, independent of the event cache. */
class PreferencesBackoffStore(context: Context) : CalendarBackoffStore {
    private val preferences = context.getSharedPreferences("calendar_backoff", Context.MODE_PRIVATE)

    override fun blockedUntil(source: CalendarSource): Instant? = preferences.getLong(source.key, 0L)
        .takeIf { it > 0L }
        ?.let(Instant::ofEpochMilli)

    override fun block(source: CalendarSource, until: Instant) {
        preferences.edit().putLong(source.key, until.toEpochMilli()).apply()
    }

    override fun clear(source: CalendarSource) {
        preferences.edit().remove(source.key).apply()
    }

    private val CalendarSource.key: String
        get() = when (this) {
            CalendarSource.PRIMARY -> "biquote_blocked_until"
            // The former primary was TradingView; preserve its deadline on upgrade.
            CalendarSource.TRADING_VIEW -> "primary_blocked_until"
            // Keep the existing key so upgrades preserve an active Retry-After window.
            CalendarSource.FALLBACK -> "fallback_blocked_until"
        }
}
