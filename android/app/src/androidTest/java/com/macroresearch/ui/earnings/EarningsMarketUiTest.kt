package com.macroresearch.ui.earnings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.macroresearch.R
import com.macroresearch.data.model.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class EarningsMarketUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val start = Instant.parse("2026-11-03T15:00:00Z")
    private val date = LocalDate.of(2026, 11, 3)
    private val event = EarningsEvent("AAPL", "Apple Inc.", "nasdaq", "after_hours")
    private fun market() = EarningsMarket(
        date.toString(), "AAPL", "first_detected_actual_eps", false, "tracking",
        EarningsStockTracking(start.toString(), start.plusSeconds(3600).toString(), "completed", "USD", start.plusSeconds(50).toString(), null),
        listOf(EarningsStockSnapshot(start.toString(), start.plusSeconds(5).toString(), start.plusSeconds(45).toString(),
            102.0, 100.0, 102.0, 100.0, 102.0, "yahoo", "provider_quote")),
        EarningsStockCoverage(1, 60, 59, true),
    )

    @Test fun showsCorrespondingStockPriceAndTruthfulIncompleteCoverage() {
        compose.setContent { MaterialTheme {
            EarningsMarketContent(date, event, EarningsMarketState(market()), start.plusSeconds(4000), {}, {})
        } }
        compose.onNodeWithText("Apple Inc.").assertIsDisplayed()
        compose.onNodeWithTag("earnings-market-latest-price").assertTextEquals("102.00")
        compose.onNodeWithText("USD").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.earnings_market_ended)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.earnings_market_coverage, 1, 60, 59)).performScrollTo().assertIsDisplayed()
    }

    @Test fun timelineNeverFillsAMissingTimeWithTheLatestPrice() {
        compose.setContent { MaterialTheme {
            EarningsMarketContent(date, event, EarningsMarketState(market()), start.plusSeconds(4000), {}, {})
        } }
        compose.onNodeWithTag("earnings-market-timeline").performScrollTo()
        compose.onNodeWithTag("earnings-market-timeline-price").assertTextEquals("—") // default +5 min has no valid sample
        compose.onNodeWithTag("earnings-market-timeline").performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        compose.onNodeWithTag("earnings-market-timeline-price").assertTextEquals("102.00")
    }

    @Test fun anAlreadyReportedReleaseDoesNotPretendToHaveAPriceHistory() {
        val empty = market().copy(tracking = null, snapshots = emptyList(), observationStatus = "already_reported",
            coverage = EarningsStockCoverage(0, 0, 0, false))
        compose.setContent { MaterialTheme {
            EarningsMarketContent(date, event, EarningsMarketState(empty), start.plusSeconds(4000), {}, {})
        } }
        compose.onNodeWithTag("earnings-market-latest-price").assertTextEquals("—")
        compose.onNodeWithText(context.getString(R.string.earnings_market_already_reported)).assertIsDisplayed()
        compose.onNodeWithTag("earnings-market-timeline").assertDoesNotExist()
    }

    @Test fun failureRetainsOnlyExplicitlyStaleSamplesAndHasARetry() {
        var retries = 0
        compose.setContent { MaterialTheme {
            EarningsMarketContent(date, event, EarningsMarketState(market(), error = "unavailable"), start.plusSeconds(4000), { retries++ }, {})
        } }
        compose.onNodeWithText(context.getString(R.string.earnings_market_stale)).assertIsDisplayed()
        compose.onNodeWithTag("earnings-market-refresh").performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(1, retries) }
    }
}
