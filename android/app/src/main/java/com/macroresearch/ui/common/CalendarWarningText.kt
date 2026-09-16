package com.macroresearch.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.macroresearch.R
import com.macroresearch.data.CalendarWarning

/**
 * Localized explanation for a degraded calendar.
 *
 * Only [CalendarWarning.reason] is rendered; the raw provider error stays in the log so socket
 * wording and internal addresses never appear on screen.
 */
@Composable
fun calendarWarningMessage(warning: CalendarWarning): String {
    val minutes = warning.retryAfterSeconds?.takeIf { it > 0 }?.let { (it + 59) / 60 }
    return when (warning.reason) {
        CalendarWarning.Reason.PRIMARY_UNAVAILABLE -> stringResource(R.string.calendar_warning_primary)

        CalendarWarning.Reason.ALL_SOURCES_UNAVAILABLE -> stringResource(R.string.calendar_warning_offline)

        CalendarWarning.Reason.BACKEND_UNAVAILABLE -> stringResource(R.string.calendar_warning_backend)

        CalendarWarning.Reason.FALLBACK_RATE_LIMITED -> if (minutes != null) {
            stringResource(R.string.calendar_warning_rate_limited, minutes)
        } else {
            stringResource(R.string.calendar_warning_rate_limited_unknown)
        }
    }
}
