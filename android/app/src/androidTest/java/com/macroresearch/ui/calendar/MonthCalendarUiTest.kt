package com.macroresearch.ui.calendar

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.macroresearch.R
import com.macroresearch.data.BackendSettings
import com.macroresearch.data.model.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.*

@RunWith(AndroidJUnit4::class)
class MonthCalendarUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val reference = LocalDate.of(2026, 11, 3)

    private fun state(date: LocalDate = reference): MonthCalendarState = MonthCalendarState(
        month = YearMonth.from(date), selectedDate = date, countries = setOf("united states"), economicLoaded = true,
        economic = listOf(EconomicEvent(
            7, "tradingview", "cpi", null, "united states", "USD", "inflation", "CPI",
            eventTime = date.atTime(8, 30).atZone(ZoneId.systemDefault()).toInstant().toString(),
            importance = 3, actual = null, previous = "2.5", consensus = "2.6", forecast = null,
            unit = "%", status = "scheduled",
        )),
        earnings = mapOf(date to MonthEarningsDay(EarningsCalendar(
            date.toString(), updatedAt = Instant.now().toString(), events = listOf(
                EarningsEvent("AAPL", "Apple Inc.", "nasdaq", "after_hours", epsForecast = "1.98"),
            ),
        ), loaded = true)),
    )

    @Test fun monthGridFiltersMarkersAndOpensTheExactDate() {
        var opened: LocalDate? = null
        compose.setContent {
            var value by remember { mutableStateOf(state()) }
            MaterialTheme(colorScheme = darkColorScheme()) {
                CalendarMonthContent(value, BackendSettings(), null,
                    onMonth = { value = value.copy(month = it) }, onToday = {}, onPicker = {}, onRefresh = {},
                    onContent = { value = value.copy(content = it) }, onDate = { opened = it },
                    modifier = Modifier.widthIn(max = 380.dp))
            }
        }
        compose.onNodeWithTag("earnings-logo-AAPL", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("calendar-economic-count-$reference", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("calendar-filter-EARNINGS").performClick()
        compose.onNodeWithTag("calendar-economic-count-$reference", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("calendar-filter-ECONOMIC").performClick()
        compose.onNodeWithTag("earnings-logo-AAPL", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("calendar-filter-ALL").performClick()
        compose.onNodeWithTag("calendar-day-$reference").performClick()
        compose.runOnIdle { assertEquals(reference, opened) }
        compose.onNodeWithTag("calendar-month-weeks").performScrollToIndex(5)
        compose.onNodeWithTag("calendar-today").performClick()
        compose.onNodeWithTag("calendar-day-$reference").assertIsDisplayed()
        compose.onNodeWithTag("calendar-next-month").performClick()
        compose.onNodeWithTag("calendar-day-2026-12-01").assertExists()
    }

    @Test fun dayAgendaKeepsEventNavigationAndEarningsSessionFiltering() {
        var openedEvent: Long? = null
        val today = LocalDate.now(EARNINGS_ZONE)
        compose.setContent {
            var value by remember { mutableStateOf(state(today)) }
            MaterialTheme {
                CalendarDayContent(value, BackendSettings(), null, null,
                    onDismiss = {}, onContent = { value = value.copy(content = it) },
                    onImportance = {}, onRefresh = {}, onEvent = { openedEvent = it },
                    modifier = Modifier.fillMaxSize())
            }
        }
        compose.onNodeWithText("CPI").performClick()
        compose.runOnIdle { assertEquals(7L, openedEvent) }
        compose.onNodeWithTag("calendar-filter-EARNINGS").performClick()
        compose.onNodeWithText("AAPL").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.earnings_pre_market)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.earnings_empty)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("calendar-selected-date").assertIsDisplayed()
    }

    @Test fun failedEarningsDateShowsAuthorizationErrorNotAConfirmedEmptyDay() {
        val today = LocalDate.now(EARNINGS_ZONE)
        val value = state(today).copy(content = CalendarContent.EARNINGS,
            earnings = mapOf(today to MonthEarningsDay(error = "unauthorized", loaded = true)))
        compose.setContent {
            MaterialTheme {
                CalendarDayContent(value, BackendSettings(), null, null,
                    onDismiss = {}, onContent = {}, onImportance = {}, onRefresh = {}, onEvent = {},
                    modifier = Modifier.fillMaxSize())
            }
        }
        compose.onNodeWithText(context.getString(R.string.earnings_unauthorized)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.earnings_empty)).assertDoesNotExist()
    }
}
