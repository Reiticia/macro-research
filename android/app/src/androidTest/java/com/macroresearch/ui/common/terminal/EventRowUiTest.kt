package com.macroresearch.ui.common.terminal

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.unit.Density
import com.macroresearch.R
import com.macroresearch.data.model.EconomicEvent
import com.macroresearch.ui.theme.AdaptiveLayout
import com.macroresearch.ui.theme.LocalAdaptiveLayout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EventRowUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val event = EconomicEvent(
        1, "trading_view", "1", null, "United States", "USD", "inflation", "Core CPI MoM",
        eventTime = "2026-09-10T12:30:00Z", importance = 3, actual = "0.4", previous = "0.2",
        consensus = "0.3", forecast = "0.3", unit = "%", status = "released",
    )

    @Test fun stackedLayoutKeepsAllValuesAndSurpriseDescription() {
        var clicks = 0
        compose.setContent {
            CompositionLocalProvider(LocalAdaptiveLayout provides AdaptiveLayout(stackEventRows = true)) {
                MaterialTheme { EventRow(event, { clicks++ }, showDate = true) }
            }
        }
        for (value in listOf("0.4%", "0.3%", "0.2%")) {
            compose.onNodeWithText(value, substring = true).assertIsDisplayed()
        }
        compose.onNodeWithText(context.getString(R.string.table_time)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.above_consensus), substring = true, useUnmergedTree = true)
            .assertExists()
        compose.onNodeWithText("Core CPI MoM").performClick()
        assertEquals(1, clicks)
    }

    @Test fun wideRowValuesCarryColumnLabelsForScreenReaders() {
        compose.setContent {
            CompositionLocalProvider(LocalAdaptiveLayout provides AdaptiveLayout(stackEventRows = false)) {
                MaterialTheme { EventRow(event, {}) }
            }
        }
        for ((label, value) in listOf(
            R.string.table_previous to "0.2%",
            R.string.table_expectation to "0.3%",
            R.string.table_actual to "0.4%",
        )) {
            compose.onNodeWithContentDescription("${context.getString(label)} $value", substring = true, useUnmergedTree = true)
                .assertExists()
        }
    }

    @Test fun wideRowKeepsSignedActualOnOneLineAtLargeFontScale() {
        val signed = event.copy(actual = "-0.31", consensus = "0.3")
        val density = context.resources.displayMetrics.density
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density, 1.3f),
                LocalAdaptiveLayout provides AdaptiveLayout(stackEventRows = false),
            ) {
                MaterialTheme { EventRow(signed, {}) }
            }
        }
        val actual = compose.onNodeWithText("-0.31", substring = true, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val previous = compose.onNodeWithText("0.2%", substring = true, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        // 公布值未折行：高度与单行的前值一致
        assertEquals(previous.height, actual.height, 1f)
    }

    @Test fun wideHeaderShowsColumnLabels() {
        compose.setContent { MaterialTheme { EventTableHeader() } }
        compose.onNodeWithText(context.getString(R.string.table_previous)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.table_actual)).assertIsDisplayed()
    }
}
