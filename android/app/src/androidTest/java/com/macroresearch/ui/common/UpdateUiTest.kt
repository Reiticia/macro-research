package com.macroresearch.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.macroresearch.R
import com.macroresearch.BuildConfig
import com.macroresearch.ui.settings.WhatsNewDialog
import com.macroresearch.update.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class UpdateUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun notes() = context.assets.open("release-notes.json").use { UpdateContract.parseNotes(UpdateContract.json(it.readBytes())) }

    @Test fun bundledNotesAreOfflineAndExplicitConfirmationSurvivesRecreation() {
        val directory = File(context.noBackupFilesDir, "update-ui-test").apply { deleteRecursively(); mkdirs() }
        val store = AtomicWhatsNewStore(directory)
        val coordinator = WhatsNewCoordinator(notes(), store)
        try {
            assertTrue(WhatsNewCoordinator(notes(), store).pending.value)
            compose.setContent {
                val pending by coordinator.pending.collectAsState()
                CompositionLocalProvider(LocalDensity provides Density(1f, 1.7f)) {
                    MaterialTheme(colorScheme = darkColorScheme()) {
                        if (pending) WhatsNewDialog(coordinator.notes, true, false, { coordinator.acknowledge() }, {})
                    }
                }
            }
            compose.onNodeWithText(context.getString(R.string.whats_new_acknowledge)).assertIsDisplayed().performClick()
            compose.waitForIdle()
            assertFalse(WhatsNewCoordinator(notes(), store).pending.value)
            assertEquals(BuildConfig.VERSION_CODE.toLong(), store.acknowledgedCode())
        } finally { directory.deleteRecursively() }
    }
    @Test fun fileProviderExposesOnlyTheDedicatedVerifiedCacheDirectory() {
        val file = File(context.cacheDir, "not-an-update.txt").apply { writeText("test") }
        try {
            assertThrows(IllegalArgumentException::class.java) {
                androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.update-files", file)
            }
            val directory = File(context.cacheDir, "updates").apply { mkdirs() }
            val verified = File(directory, "provider-test.apk").apply { writeText("fixture") }
            try {
                val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.update-files", verified)
                assertEquals("content", uri.scheme)
                assertEquals("${context.packageName}.update-files", uri.authority)
            } finally { verified.delete() }
        } finally { file.delete() }
    }
}
