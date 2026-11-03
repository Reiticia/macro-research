package com.macroresearch.ui.earnings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.macroresearch.R
import com.macroresearch.data.*
import com.macroresearch.data.local.MacroDatabase
import com.macroresearch.data.local.RoomEarningsFavoritesStore
import com.macroresearch.data.model.EarningsEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class EarningsFavoriteUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun starSavesTheExactOccurrenceAndCanRemoveItWithoutAnyNetworkRequest() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, MacroDatabase::class.java).build()
        val date = LocalDate.of(2026,11,3)
        val event = EarningsEvent("AAPL", "Apple", "nasdaq")
        val store = RoomEarningsFavoritesStore(db.earningsDao())
        val repo = EarningsRepository(MutableStateFlow(BackendSettings()), object : EarningsStore {
            override suspend fun read(source: String, date: String): String? = null
            override suspend fun write(source: String, date: String, payload: String) = Unit
            override suspend fun prune(before: String) = Unit
        }, direct = { _, _ -> error("No network") }, backend = { _, _ -> error("No network") }, favoritesStore = store)
        try {
            compose.setContent { MaterialTheme { EarningsFavoriteButton(date, event, repo) } }
            compose.onNodeWithContentDescription(context.getString(R.string.earnings_favorite)).performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription(context.getString(R.string.earnings_unfavorite)).fetchSemanticsNodes().isNotEmpty() }
            runBlocking { assertEquals(listOf(FavoriteEarnings(date,event)), store.observe("direct").first()) }
            compose.onNodeWithContentDescription(context.getString(R.string.earnings_unfavorite)).performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription(context.getString(R.string.earnings_favorite)).fetchSemanticsNodes().isNotEmpty() }
            runBlocking { assertTrue(store.observe("direct").first().isEmpty()) }
        } finally { db.close() }
    }
}
