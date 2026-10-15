package com.macroresearch.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.macroresearch.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsIndexUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun rowsShowSummariesAndOpenTheirSection() {
        val opened = mutableListOf<SettingsSection>()
        var whatsNew = false
        compose.setContent {
            MaterialTheme {
                SettingsIndex(
                    groups = listOf(
                        SettingsGroup(R.string.settings_group_data, listOf(
                            SettingsEntry(SettingsSection.DataSource, Icons.Outlined.SwapHoriz, SettingsSummary(R.string.data_source_direct)),
                        )),
                        SettingsGroup(R.string.settings_group_ai, listOf(
                            SettingsEntry(SettingsSection.AiApi, Icons.Outlined.Key, SettingsSummary(R.string.api_key_not_configured)),
                        )),
                        SettingsGroup(R.string.settings_group_about, emptyList()),
                    ),
                    onOpen = { opened += it },
                    onShowWhatsNew = { whatsNew = true },
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.data_source_direct), substring = true).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.data_source)).performClick()
        compose.onNodeWithText(context.getString(R.string.translation_api)).performClick()
        assertEquals(listOf(SettingsSection.DataSource, SettingsSection.AiApi), opened)
        compose.onNodeWithText(context.getString(R.string.whats_new)).performClick()
        assertTrue(whatsNew)
    }
}
