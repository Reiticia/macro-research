package com.macroresearch.ui.earnings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.macroresearch.R
import com.macroresearch.data.model.EarningsEvent
import com.macroresearch.data.model.EarningsQuote
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class EarningsStockUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val now = Instant.parse("2026-11-04T15:00:00Z")
    private val date = LocalDate.of(2026, 11, 3)
    private val event = EarningsEvent("AAPL", "Apple Inc.", "nasdaq", "after_hours")
    private val quote = EarningsQuote("AAPL", "123.45", "USD", now.toString(), now.toString(), "yahoo", "provider_quote", false)

    @Test fun stockDetailRetainsCurrentPriceAndHasNeitherTrackingPhaseNorTimeline() {
        compose.setContent { MaterialTheme {
            EarningsStockContent(date, event, EarningsQuoteState(quote), now, {}, {})
        } }
        compose.onNodeWithText("Apple Inc.").assertIsDisplayed()
        compose.onNodeWithTag("earnings-current-price-value").assertTextEquals("123.45")
        compose.onNodeWithText(context.getString(R.string.earnings_stock_date, date.toString())).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.earnings_quote_note)).performScrollTo().assertIsDisplayed()
        for (tag in listOf("earnings-phase-release", "earnings-phase-opening", "earnings-market-timeline", "earnings-market-latest-price")) {
            compose.onNodeWithTag(tag).assertDoesNotExist()
        }
    }

    @Test fun refreshAndBackRemainAvailableWithoutHistoricalSampling() {
        var refreshes = 0
        var backs = 0
        compose.setContent { MaterialTheme {
            EarningsStockContent(date, event, EarningsQuoteState(quote), now, { refreshes++ }, { backs++ })
        } }
        compose.onNodeWithTag("earnings-stock-refresh").performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        compose.runOnIdle { assertEquals(1, refreshes); assertEquals(1, backs) }
    }

    @Test fun directModeShowsOnlyTheCurrentQuoteRequirementAndNoFabricatedHistory() {
        compose.setContent { MaterialTheme {
            EarningsStockContent(date, event, EarningsQuoteState(error = "backend_required"), now, {}, {})
        } }
        compose.onNodeWithTag("earnings-current-price-value").assertTextEquals("—")
        compose.onNodeWithText(context.getString(R.string.earnings_quote_backend_required)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("earnings-market-timeline").assertDoesNotExist()
    }

    @Test fun anOldMinuteBarQuoteRemainsClearlyMarkedInsteadOfBecomingAReleaseSample() {
        compose.setContent { MaterialTheme {
            CurrentStockPriceCard(EarningsQuoteState(quote.copy(stale = true, timestampBasis = "minute_bar_close"), error = "unavailable"), now, java.util.Locale.US)
        } }
        compose.onNodeWithText(context.getString(R.string.earnings_quote_stale)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.earnings_quote_bar)).assertIsDisplayed()
        compose.onNodeWithTag("earnings-market-timeline").assertDoesNotExist()
    }
}
