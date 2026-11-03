package com.macroresearch.ui.earnings

import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.macroresearch.R
import com.macroresearch.data.BackendSettings
import com.macroresearch.data.model.EarningsEvent
import com.macroresearch.data.model.earningsPage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EarningsPageLinkTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val event = EarningsEvent("AAPL", "Apple", "nasdaq")

    @Test fun cardLaunchesOnlyOnClickWithNoHeadersOrCredentials() {
        val intents = mutableListOf<Intent>()
        val browser = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) { intents += intent }
        }
        compose.setContent {
            CompositionLocalProvider(LocalContext provides browser) {
                MaterialTheme { EarningsCard(event, null, BackendSettings()) }
            }
        }
        compose.runOnIdle { assertTrue(intents.isEmpty()) }
        compose.onNodeWithTag("earnings-page-open-AAPL").performClick()
        compose.runOnIdle {
            assertEquals(1, intents.size)
            val intent = intents.single()
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals(event.earningsPage()!!.url, intent.dataString)
            assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
            assertTrue(intent.extras == null || intent.extras!!.isEmpty)
            assertNull(intent.component)
        }
    }

    @Test fun malformedSymbolsDisableTheLinkAndNeverDispatchAnIntent() {
        var launches = 0
        val browser = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) { launches++ }
        }
        val bad = event.copy(symbol = "../AAPL")
        compose.setContent {
            CompositionLocalProvider(LocalContext provides browser) {
                MaterialTheme { EarningsPageLink(bad) }
            }
        }
        compose.onNodeWithText(context.getString(R.string.earnings_page_unavailable)).assertIsDisplayed()
        compose.onNodeWithTag("earnings-page-open-${bad.symbol}").assertIsNotEnabled()
        compose.runOnIdle {
            assertFalse(openEarningsPage(browser, bad))
            assertEquals(0, launches)
        }
    }

    @Test fun missingBrowserShowsAnErrorWithoutLeavingTheCard() {
        val browser = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) { throw ActivityNotFoundException() }
        }
        compose.setContent {
            CompositionLocalProvider(LocalContext provides browser) {
                MaterialTheme { Column { EarningsPageLink(event) } }
            }
        }
        compose.onNodeWithTag("earnings-page-open-AAPL").performClick()
        compose.onNodeWithText(context.getString(R.string.earnings_page_open_failed)).assertIsDisplayed()
        compose.onNodeWithTag("earnings-page-open-AAPL").assertIsEnabled()
    }
}
