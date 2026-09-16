package com.macroresearch.data

/**
 * Explains why calendar data is degraded.
 *
 * The UI localizes [reason] instead of printing an exception, so socket wording and internal
 * addresses never reach the user. [detail] keeps the raw provider error for the log only.
 */
data class CalendarWarning(
    val reason: Reason,
    val detail: String? = null,
    /** Seconds the provider asked us to wait before retrying, when it reported one. */
    val retryAfterSeconds: Long? = null,
) {
    enum class Reason {
        /** The primary provider failed or returned nothing; rows come from the weekly fallback. */
        PRIMARY_UNAVAILABLE,

        /** The fallback provider is rate-limiting this device, so retrying right now cannot help. */
        FALLBACK_RATE_LIMITED,

        /** No provider answered; the list is served from the local cache. */
        ALL_SOURCES_UNAVAILABLE,

        /** Backend mode, and the configured backend cannot be reached or rejected the token. */
        BACKEND_UNAVAILABLE,
    }
}

/** Raised when no calendar provider could be reached. [warning] carries the localizable reason. */
class CalendarUnavailableException(val warning: CalendarWarning) :
    Exception("Calendar sources unavailable: ${warning.reason}")
