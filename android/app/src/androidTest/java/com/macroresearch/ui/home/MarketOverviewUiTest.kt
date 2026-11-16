package com.macroresearch.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.macroresearch.R
import com.macroresearch.data.model.LiveMarketQuote
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MarketOverviewUiTest {
    @get:Rule val compose = createComposeRule()
    private fun quote(symbol: String) = LiveMarketQuote(symbol, "2026-11-13T10:00:00Z",
        100.0, "fixture", 1.0, null, null, "open", false)

    @Test fun everyCardOpensMarketIncludingMissingQuotesWithoutScrolling() {
        var opened = 0
        val symbols = listOf("gold", "dxy", "us2y", "bitcoin")
        compose.setContent { MaterialTheme {
            Box(Modifier.width(320.dp)) {
                MarketOverview(symbols.take(3).map(::quote), symbols, { opened++ })
            }
        } }
        symbols.forEach { symbol ->
            compose.onNodeWithTag("home-market-$symbol").assertIsDisplayed().assertHasClickAction().performClick()
        }
        compose.runOnIdle { assertEquals(4, opened) }
        compose.onNodeWithText("2026-11-13T10:00:00Z", substring = true).assertDoesNotExist()
        compose.onNodeWithText("fixture", substring = true).assertDoesNotExist()
        compose.onNodeWithText(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.live_snapshot)).assertDoesNotExist()
    }

    @Test fun staleQuotesKeepTheirWarningAndNavigation() {
        var opened = false
        compose.setContent { MaterialTheme {
            MarketOverview(listOf(quote("gold").copy(stale = true)), listOf("gold"), { opened = true })
        } }
        compose.onNodeWithText(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.market_stale)).assertIsDisplayed()
        compose.onNodeWithTag("home-market-gold").performClick()
        compose.runOnIdle { assertEquals(true, opened) }
    }
}
