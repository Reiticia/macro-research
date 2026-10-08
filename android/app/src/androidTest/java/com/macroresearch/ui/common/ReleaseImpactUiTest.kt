package com.macroresearch.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.macroresearch.R
import com.macroresearch.data.local.MacroDatabase
import com.macroresearch.data.local.asEntity
import com.macroresearch.data.local.asExternalModel
import com.macroresearch.data.model.EconomicEvent
import com.macroresearch.ui.event.ReleaseDataCard
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReleaseImpactUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun event(actual: String? = "0.31") = EconomicEvent(
        1, "trading_view", "1", null, "United States", "USD", "inflation", "Core CPI MoM",
        eventTime = "2026-09-10T12:30:00Z", importance = 3, actual = actual, previous = "0.2",
        consensus = "0.3", forecast = "0.3", unit = "%", status = "released",
    )
    private fun asset(symbol: Int, direction: Int) =
        hasText(context.getString(symbol)) and hasText(context.getString(direction))

    @Test fun listsContainOnlyEventMetadataEvenAfterPublicationAndStillOpenDetail() {
        var clicks = 0
        compose.setContent { MaterialTheme { EventCard(event(), { clicks++ }, showDate = true) } }
        compose.onNodeWithText("Core CPI MoM").assertIsDisplayed().performClick()
        assertEquals(1, clicks)
        for (value in listOf("0.31%", "0.3%", "0.2%")) {
            compose.onNodeWithText(value, substring = true).assertDoesNotExist()
        }
        compose.onNodeWithText(context.getString(R.string.release_impact_title)).assertDoesNotExist()
        compose.onNode(asset(R.string.asset_gold, R.string.release_impact_weak_down)).assertDoesNotExist()
    }

    @Test fun upcomingListsDoNotShowPredictionOrPreviousPlaceholders() {
        compose.setContent { MaterialTheme { EventCard(event(null), {}) } }
        compose.onNodeWithText("Core CPI MoM").assertIsDisplayed()
        compose.onNodeWithText("0.3%", substring = true).assertDoesNotExist()
        compose.onNodeWithText("0.2%", substring = true).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.release_impact_waiting)).assertDoesNotExist()
    }

    @Test fun detailValuesShowActualAndOneComparisonWithoutDuplicateForecast() {
        compose.setContent { MaterialTheme { ReleaseDataCard(event()) } }
        compose.onNodeWithText(context.getString(R.string.release_data_title)).assertIsDisplayed()
        compose.onNodeWithText("0.31%").assertIsDisplayed()
        compose.onNodeWithText("0.3%").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.forecast)).assertDoesNotExist()
    }

    @Test fun weakImpactChangesToMaterialWithoutAnyReportOrMarket() {
        val row = mutableStateOf(event())
        compose.setContent { MaterialTheme { ReleaseImpactLabels(row.value) } }
        compose.onNode(asset(R.string.asset_gold, R.string.release_impact_weak_down)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.release_impact_limited)).assertIsDisplayed()
        compose.runOnIdle { row.value = event("0.4") }
        compose.onNode(asset(R.string.asset_gold, R.string.release_impact_down)).assertIsDisplayed()
        compose.onNode(asset(R.string.asset_us2y, R.string.release_impact_yield_up)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.release_impact_limited)).assertDoesNotExist()
    }

    @Test fun unknownMissingForecastAndEqualAreDistinctAndNeverShowBearishTiles() {
        val row = mutableStateOf(event("0.3"))
        compose.setContent { MaterialTheme { ReleaseImpactLabels(row.value) } }
        compose.onNodeWithText(context.getString(R.string.release_impact_equal)).assertIsDisplayed()
        compose.runOnIdle { row.value = event().copy(consensus = null, forecast = null) }
        compose.onNodeWithText(context.getString(R.string.release_impact_missing_expectation)).assertIsDisplayed()
        compose.runOnIdle { row.value = event().copy(country = "Canada") }
        compose.onNodeWithText(context.getString(R.string.release_impact_unknown_rule)).assertIsDisplayed()
        compose.onNode(asset(R.string.asset_gold, R.string.release_impact_down)).assertDoesNotExist()
    }

    @Test fun narrowDarkLargeFontLayoutRetainsDirectionsAndExpandableEvidence() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                        ReleaseImpactLabels(event(), detailed = true)
                    }
                }
            }
        }
        compose.onNode(asset(R.string.asset_gold, R.string.release_impact_weak_down)).assertExists()
        compose.onNode(asset(R.string.asset_us10y, R.string.release_impact_yield_weak_up)).assertExists()
        compose.onNodeWithText(context.getString(R.string.release_impact_disclaimer)).assertDoesNotExist()
        compose.onNodeWithTag("release-impact-evidence").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.release_impact_disclaimer)).assertExists()
    }

    @Test fun roomPublishedValueEmissionImmediatelyUpdatesDetailTiles() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, MacroDatabase::class.java).build()
        try {
            val dao = database.eventDao()
            dao.upsert(listOf(event(null).asEntity()))
            compose.setContent {
                val stored by dao.observeEvent(1).collectAsState(initial = null)
                MaterialTheme { stored?.let { ReleaseImpactLabels(it.asExternalModel(), detailed = true) } }
            }
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasText(context.getString(R.string.release_impact_waiting)))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            dao.mergeCalendar(listOf(event("0.4").asEntity()))
            compose.waitUntil(5_000) {
                compose.onAllNodes(asset(R.string.asset_gold, R.string.release_impact_down))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(asset(R.string.asset_gold, R.string.release_impact_down)).assertIsDisplayed()
            dao.mergeCalendar(emptyList())
            dao.mergeCalendar(listOf(event(null).asEntity()))
            assertEquals("0.4", dao.event(1)!!.actual)
        } finally { database.close() }
    }
}
