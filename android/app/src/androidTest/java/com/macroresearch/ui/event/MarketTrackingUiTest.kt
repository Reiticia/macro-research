package com.macroresearch.ui.event

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.macroresearch.R
import com.macroresearch.data.model.EconomicEvent
import com.macroresearch.data.model.MarketResponse
import com.macroresearch.data.model.MarketSnapshot
import java.io.File
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Compose rendering, using fixtures without changing the device's backend settings. */
@RunWith(AndroidJUnit4::class)
class MarketTrackingUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun event(minutesAgo: Long) = EconomicEvent(
        id = 7L, provider = "fixture", providerId = "missing-release", releaseGroupId = null,
        country = "United States", currency = "USD", category = "inflation", event = "CPI",
        eventTime = Instant.now().minusSeconds(minutesAgo * 60).toString(), importance = 3,
        actual = null, previous = null, consensus = null, forecast = null, unit = "%", status = "timeout",
    )

    private fun render(minutesAgo: Long, market: MarketResponse = MarketResponse(emptyList(), emptyList())) {
        val event = event(minutesAgo)
        compose.setContent { MaterialTheme { MarketTrackingCard(event, market) } }
        compose.onNodeWithText(context.getString(R.string.status_data_unavailable), useUnmergedTree = true)
            .assertIsDisplayed()
    }

    private fun screenshot(name: String) {
        val directory = File(context.cacheDir, "market-tracking-test").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun fortyMinutesWithoutActualStillShowsAllMonitoringRows() {
        render(40)
        for (label in listOf(
            R.string.asset_gold, R.string.asset_dxy, R.string.asset_us2y, R.string.asset_us10y,
            R.string.asset_nasdaq, R.string.asset_bitcoin, R.string.asset_wti, R.string.asset_natural_gas,
        )) {
            compose.onNodeWithText(context.getString(label), useUnmergedTree = true).assertIsDisplayed()
        }
        compose.onNodeWithText(context.getString(R.string.market_data_unavailable), useUnmergedTree = true)
            .assertDoesNotExist()
        screenshot("missing-actual-40-minutes")
    }

    @Test
    fun afterSixtyMinutesAnEmptyMarketShowsAnExplicitMissingMessage() {
        render(61)
        compose.onNodeWithText(context.getString(R.string.market_data_unavailable), useUnmergedTree = true)
            .assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.asset_gold), useUnmergedTree = true)
            .assertDoesNotExist()
        screenshot("empty-market-61-minutes")
    }

    @Test
    fun collectedPricesRemainVisibleAfterTheWindowEnds() {
        val snapshot = MarketSnapshot(
            id = 1L, eventId = 7L, symbol = "gold", timestamp = Instant.now().toString(),
            price = 100.0, open = null, high = null, low = null, close = 100.0, volume = null,
        )
        render(61, MarketResponse(listOf(snapshot), emptyList()))
        compose.onNodeWithText(context.getString(R.string.asset_gold), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("100.00", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.asset_dxy), useUnmergedTree = true).assertDoesNotExist()
        screenshot("collected-price-61-minutes")
    }
}
