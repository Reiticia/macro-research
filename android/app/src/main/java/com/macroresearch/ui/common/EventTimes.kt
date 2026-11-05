package com.macroresearch.ui.common

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.testTag
import com.macroresearch.data.EventTimeDisplay
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.macroresearch.R
import com.macroresearch.data.model.EconomicEvent

val LocalEventTimeDisplay = staticCompositionLocalOf { EventTimeDisplay.BOTH }

/** Shared by every macro event card, including day/month sheets, history and followed events. */
@Composable
fun EventTimes(
    event: EconomicEvent,
    modifier: Modifier = Modifier,
    showDate: Boolean = false,
    explainReference: Boolean = false,
    display: EventTimeDisplay = LocalEventTimeDisplay.current,
) {
    val locale = appLocale()
    val times = event.displayTimes()
    val unavailable = stringResource(if (event.timeExact) R.string.event_time_unavailable else R.string.event_time_pending)
    val deviceText = times.deviceTime?.let { eventClockText(it, showDate, locale) } ?: unavailable
    val regionText = times.regionClockText(display, showDate, locale) ?: unavailable
    val regionName = when (times.regionZone?.id) {
        "America/New_York" -> stringResource(R.string.event_zone_new_york)
        "Europe/Berlin" -> stringResource(R.string.event_zone_central_europe)
        "Asia/Shanghai" -> stringResource(R.string.event_zone_beijing)
        "Asia/Tokyo" -> stringResource(R.string.event_zone_tokyo)
        "Europe/London" -> stringResource(R.string.event_zone_london)
        "Australia/Sydney" -> stringResource(R.string.event_zone_sydney)
        "America/Toronto" -> stringResource(R.string.event_zone_toronto)
        else -> times.regionZone?.id.orEmpty()
    }
    val localLabel = stringResource(R.string.event_device_time_inline, deviceText)
    val regionLabel = if (times.regionZone == null) stringResource(R.string.event_region_unknown)
        else stringResource(R.string.event_region_time_inline, regionName, regionText)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(eventTimeDisplayText(display, localLabel, regionLabel),
            modifier = Modifier.testTag("event-times-${event.id}").horizontalScroll(rememberScrollState()),
            maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (explainReference && display != EventTimeDisplay.DEVICE) {
            Text(stringResource(R.string.event_time_reference_note), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
