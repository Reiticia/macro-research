package com.macroresearch.ui.common

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.macroresearch.R
import com.macroresearch.data.CalendarPreferences
import com.macroresearch.data.EventTimeDisplay
import com.macroresearch.data.model.EconomicEvent
import com.macroresearch.ui.settings.EventTimeDisplaySettings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.TimeZone

@RunWith(AndroidJUnit4::class)
class EventTimeDisplayUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun event(country: String = "United States") = EconomicEvent(7, "test", "cpi", null, country, "USD", "inflation", "CPI",
        eventTime = "2026-11-04T13:30:00Z", importance = 3, actual = null, previous = "2", consensus = "3",
        forecast = null, unit = "%", status = "scheduled")

    @Test fun settingPersistsAndImmediatelyUpdatesSharedEventClocks() {
        val storage = context.getSharedPreferences("calendar_display", Context.MODE_PRIVATE)
        val original = storage.getString("event_time_display", null)
        val previousZone = TimeZone.getDefault()
        try {
            storage.edit().remove("event_time_display").commit()
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            val preferences = CalendarPreferences(context)
            assertEquals(EventTimeDisplay.BOTH, preferences.eventTimeDisplay.value)
            compose.setContent {
                val mode by preferences.eventTimeDisplay.collectAsState()
                MaterialTheme {
                    CompositionLocalProvider(LocalEventTimeDisplay provides mode) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            EventTimeDisplaySettings(mode, preferences::setEventTimeDisplay)
                            EventTimes(event(), showDate = true, explainReference = true)
                        }
                    }
                }
            }
            compose.onNodeWithTag("event-times-7").performScrollTo().assertTextContains("21:30").assertTextContains("08:30")
            compose.onNodeWithTag("event-time-display-DEVICE").performScrollTo().performClick().assertIsSelected()
            compose.onNodeWithTag("event-times-7").performScrollTo().assertTextContains("21:30")
                .assert(hasText("08:30", substring = true).not())
            compose.onNodeWithTag("event-time-display-REGION").performScrollTo().performClick().assertIsSelected()
            compose.onNodeWithTag("event-times-7").performScrollTo().assertTextContains("2026-11-04 08:30")
                .assert(hasText("21:30", substring = true).not())
            compose.runOnIdle { assertEquals(EventTimeDisplay.REGION, CalendarPreferences(context).eventTimeDisplay.value) }
            compose.onNodeWithTag("event-time-display-BOTH").performScrollTo().performClick().assertIsSelected()
            compose.onNodeWithTag("event-times-7").performScrollTo().assertTextContains("21:30").assertTextContains("08:30")
            compose.runOnIdle { assertEquals(EventTimeDisplay.BOTH, CalendarPreferences(context).eventTimeDisplay.value) }
        } finally {
            TimeZone.setDefault(previousZone)
            storage.edit().apply { if (original == null) remove("event_time_display") else putString("event_time_display", original) }.commit()
        }
    }

    @Test fun regionOnlyDoesNotSilentlySubstituteDeviceTimeForUnknownRegions() {
        compose.setContent { MaterialTheme { EventTimes(event("World"), display = EventTimeDisplay.REGION) } }
        compose.onNodeWithTag("event-times-7").assertTextEquals(context.getString(R.string.event_region_unknown))
    }

    @Test fun dateOnlyEventStaysPendingInEveryDisplayMode() {
        var mode by mutableStateOf(EventTimeDisplay.BOTH)
        val pending = context.getString(R.string.event_time_pending)
        compose.setContent { MaterialTheme { EventTimes(event().copy(timeExact = false), display = mode) } }
        for (value in EventTimeDisplay.entries) {
            compose.runOnIdle { mode = value }
            compose.onNodeWithTag("event-times-7").assertTextContains(pending)
                .assert(hasText("00:00", substring = true).not())
        }
    }
}
