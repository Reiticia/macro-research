package com.macroresearch.ui.history

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryRefreshUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun headerRefreshIsVisibleAndCannotBeRepeatedWhileLoading() {
        var loading by mutableStateOf(false)
        var refreshes = 0
        compose.setContent {
            MaterialTheme {
                HistoryHeader(loading) { refreshes++; loading = true }
            }
        }
        compose.onNodeWithTag("history-refresh").assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithTag("history-refresh").assertIsNotEnabled()
        compose.onNodeWithTag("history-refresh-loading", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, refreshes); loading = false }
        compose.onNodeWithTag("history-refresh").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(2, refreshes) }
    }
}
