package com.macroresearch.ui.settings

import com.macroresearch.R
import com.macroresearch.data.AnalysisMethod
import com.macroresearch.data.BackendSettings
import com.macroresearch.data.DataSourceMode
import com.macroresearch.data.DisplayMode
import com.macroresearch.data.MarketPreferences
import com.macroresearch.update.DownloadPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSectionsTest {
    @Test fun routesRoundTripAndRejectUnknown() {
        SettingsSection.entries.forEach { assertEquals(it, SettingsSection.fromRoute(it.route)) }
        assertNull(SettingsSection.fromRoute("unknown"))
        assertNull(SettingsSection.fromRoute(null))
    }

    @Test fun dataSourceSummaryReflectsModeAndCompleteness() {
        assertEquals(R.string.data_source_direct, dataSourceSummary(BackendSettings()).text)
        val incomplete = BackendSettings(mode = DataSourceMode.BACKEND, baseUrl = "https://x.example")
        assertEquals(R.string.settings_summary_backend_incomplete, dataSourceSummary(incomplete).text)
        val configured = incomplete.copy(tokenConfigured = true)
        assertEquals(R.string.settings_summary_backend_configured, dataSourceSummary(configured).text)
    }

    @Test fun countsUseRatioWithLimits() {
        assertEquals(SettingsSummary(R.string.settings_summary_ratio, listOf(6, 9)), countriesSummary(6, 9))
        assertEquals(
            SettingsSummary(R.string.settings_summary_ratio, listOf(3, MarketPreferences.MAX_SELECTIONS)),
            marketsSummary(3),
        )
    }

    @Test fun simpleSummariesMapToExistingLabels() {
        assertEquals(R.string.api_key_configured, aiApiSummary(true).text)
        assertEquals(R.string.api_key_not_configured, aiApiSummary(false).text)
        assertEquals(R.string.ai_method_two_stage, analysisMethodSummary(AnalysisMethod.EX_ANTE_THEN_COMPARE).text)
        assertEquals(R.string.settings_summary_on, newsSummary(true).text)
        assertEquals(R.string.settings_summary_off, newsSummary(false).text)
        assertEquals(R.string.display_compact, displaySummary(DisplayMode.COMPACT).text)
        assertEquals("繁體中文", languageSummary(AppLanguage.TraditionalChinese).literal)
    }

    @Test fun updateSummaryPrioritisesDownloadThenAvailability() {
        assertEquals(SettingsSummary(literal = "1.0.0"), updateSummary("1.0.0", null, DownloadPhase.IDLE))
        val available = updateSummary("1.0.0", "1.1.0", DownloadPhase.IDLE)
        assertEquals(R.string.update_available, available.text)
        assertEquals(listOf("1.1.0"), available.args)
        assertTrue(available.highlight)
        assertEquals(R.string.update_downloading, updateSummary("1.0.0", "1.1.0", DownloadPhase.PAUSED).text)
        assertEquals(R.string.settings_summary_update_ready, updateSummary("1.0.0", "1.1.0", DownloadPhase.READY).text)
        assertEquals(SettingsSummary(literal = "1.0.0"), updateSummary("1.0.0", null, DownloadPhase.FAILED))
    }

    @Test fun marketTogglesRespectMinAndMax() {
        assertFalse(marketToggleEnabled(checked = true, selectedCount = MarketPreferences.MIN_SELECTIONS))
        assertTrue(marketToggleEnabled(checked = true, selectedCount = MarketPreferences.MIN_SELECTIONS + 1))
        assertFalse(marketToggleEnabled(checked = false, selectedCount = MarketPreferences.MAX_SELECTIONS))
        assertTrue(marketToggleEnabled(checked = false, selectedCount = MarketPreferences.MAX_SELECTIONS - 1))
    }
}
