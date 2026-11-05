package com.macroresearch.ui.earnings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.macroresearch.R
import com.macroresearch.data.*
import com.macroresearch.data.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class EarningsUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun repository(logos: CompanyLogoLoader? = null) = EarningsRepository(
        settings = MutableStateFlow(BackendSettings()),
        store = object : EarningsStore {
            override suspend fun read(source: String, date: String): String? = null
            override suspend fun write(source: String, date: String, payload: String) = Unit
            override suspend fun prune(before: String) = Unit
        },
        direct = { date, _ -> EarningsCalendar(date.toString(), updatedAt = Instant.now().toString(), events = listOf(
            EarningsEvent("AAPL", "Apple Inc.", "nasdaq", "after_hours", "Sep/2026", "1.98", 7,
                tradingView = TradingViewForecast("NASDAQ:AAPL", date.toString(), "1.99", "113401213676")),
            EarningsEvent("MAR", "Marriott", "nasdaq", "pre_market"),
        )) },
        backend = { _, _ -> error("No backend or network in UI fixtures") },
        logos = logos,
    )

    @Test fun searchAndSessionFiltersKeepTheMarketDateVisible() {
        val repository = repository()
        compose.setContent { MaterialTheme { EarningsScreen(repository) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("AAPL").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(context.getString(R.string.earnings_basis)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.earnings_search)).performTextInput("Apple")
        compose.onNodeWithText("AAPL").assertIsDisplayed()
        compose.onNodeWithTag("earnings-logo-AAPL").assertIsDisplayed()
        compose.onNodeWithText("AA").assertIsDisplayed() // missing logo is a stable ticker placeholder
        compose.onNodeWithText(context.getString(R.string.earnings_pre_market)).performClick()
        compose.onNodeWithText(context.getString(R.string.earnings_empty)).assertIsDisplayed()
    }

    @Test fun availableLogoReplacesThePlaceholderWithoutChangingFinancialData() {
        val bytes = java.io.ByteArrayOutputStream().use { output ->
            val bitmap = android.graphics.Bitmap.createBitmap(4,4,android.graphics.Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.BLUE)
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output)
            bitmap.recycle()
            output.toByteArray()
        }
        val logos = CompanyLogoLoader(MutableStateFlow(BackendSettings()), {bytes}, {error("No public fallback")})
        val repository = repository(logos)
        compose.setContent { MaterialTheme { EarningsScreen(repository) } }
        val label = context.getString(R.string.earnings_company_logo,"Apple Inc.")
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(label).assertIsDisplayed()
        compose.onNodeWithText("AAPL").assertIsDisplayed()
    }

    @Test fun opensTheStockDetailAndPreservesSearchWhenReturning() {
        val repository = repository()
        compose.setContent { MaterialTheme { EarningsScreen(repository) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("AAPL").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(context.getString(R.string.earnings_search)).performTextInput("Apple")
        compose.onNodeWithText(context.getString(R.string.earnings_stock_open)).performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("earnings-stock-detail").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(context.getString(R.string.earnings_quote_backend_required)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        compose.onNodeWithTag("earnings-stock-detail").assertDoesNotExist()
        compose.onNodeWithText("Apple", substring = false).assertExists()
        compose.onNodeWithText("MAR").assertDoesNotExist()
    }

    @Test fun tradingViewForecastsAreExplicitlyUnaligned() {
        val repository = repository()
        compose.setContent { MaterialTheme { EarningsScreen(repository) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("AAPL").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(context.getString(R.string.earnings_show_tv)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.earnings_alignment_warning)).performScrollTo().assertIsDisplayed()
    }
}
