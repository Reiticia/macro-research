package com.macroresearch.ui.common

import com.macroresearch.R
import com.macroresearch.data.model.EconomicEvent
import com.macroresearch.ui.settings.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

class LabelsTest {
    @Test
    fun aliasesResolveToTheSameResourceWithoutChangingApiKeys() {
        assertEquals(R.string.country_us, countryLabelResource("United States"))
        assertEquals(countryLabelResource("United States"), countryLabelResource("US"))
        assertEquals(R.string.country_japan, countryLabelResource(" japan "))
        assertEquals(R.string.country_turkey, countryLabelResource("Turkey"))
        assertEquals(R.string.asset_nasdaq, assetLabelResource("nasdaq100"))
        assertEquals(assetLabelResource("nasdaq100"), assetLabelResource("NASDAQ"))
        assertEquals(assetLabelResource("us10y"), assetLabelResource("US 10Y"))
        assertEquals(assetLabelResource("bitcoin"), assetLabelResource("BTC"))
        assertEquals(R.string.asset_eurusd, assetLabelResource("eur_usd"))
        assertEquals(R.string.category_inflation, categoryLabelResource("Inflation"))
    }

    @Test
    fun signalsAndStatusesKeepTheirSemantics() {
        assertEquals(R.string.signal_strong_hawkish, macroSignalResource("strong_hawkish"))
        assertEquals(R.string.signal_hawkish, macroSignalResource("hawkish"))
        assertEquals(R.string.signal_neutral, macroSignalResource("neutral"))
        assertEquals(R.string.signal_dovish, macroSignalResource("dovish"))
        assertEquals(R.string.signal_strong_dovish, macroSignalResource("strong_dovish"))
        assertEquals(R.string.status_collecting, statusLabelResource("collecting_market_data"))
        assertEquals(R.string.status_completed, statusLabelResource("completed"))
        assertEquals(R.string.status_historical, statusLabelResource("historical"))
        assertEquals(R.string.importance_high, importanceLabelResource(3))
        assertEquals(R.string.importance_medium, importanceLabelResource(2))
        assertEquals(R.string.importance_low, importanceLabelResource(1))
        assertEquals(R.string.importance_unrated, importanceLabelResource(0))
        assertEquals(R.string.rationale_tighter_baseline, expectedRationaleResource("tighter_policy_baseline"))
        assertEquals(R.string.rationale_easier_baseline, expectedRationaleResource("easier_policy_baseline"))
        assertEquals(R.string.rationale_no_directional_signal, expectedRationaleResource("no_directional_signal"))
        assertEquals(R.string.analysis_rule_summary, analysisSummaryResource("rule_engine_summary"))
    }

    @Test
    fun unknownAndAlreadyTranslatedValuesFallBackToProviderText() {
        assertNull(countryLabelResource("自定义地区"))
        assertNull(assetLabelResource("custom_asset"))
        assertNull(macroSignalResource("custom_signal"))
        assertNull(statusLabelResource("custom_status"))
        assertNull(categoryLabelResource("provider category"))
        // Legacy prose (or any unexpected value) is shown verbatim instead of being dropped.
        assertNull(expectedRationaleResource("A tighter policy path is the rule-based baseline."))
        assertNull(analysisSummaryResource("free text summary"))
    }

    @Test
    fun allLanguagesHaveCompleteMatchingResourcesAndFormatArguments() {
        val english = strings("values")
        val placeholders = Regex("%[0-9]+\\$[sd]")
        listOf("values-zh-rCN", "values-zh-rTW").forEach { folder ->
            val translated = strings(folder)
            assertEquals("Missing or extra translations in $folder", english.keys - "app_name", translated.keys)
            translated.forEach { (key, value) ->
                assertTrue("Empty translation: $folder/$key", value.isNotBlank())
                assertEquals(
                    "Format arguments differ: $folder/$key",
                    placeholders.findAll(english.getValue(key)).map { it.value }.sorted().toList(),
                    placeholders.findAll(value).map { it.value }.sorted().toList(),
                )
            }
        }
        assertEquals("Settings", english["nav_settings"])
        assertEquals("设置", strings("values-zh-rCN")["nav_settings"])
        assertEquals("設定", strings("values-zh-rTW")["nav_settings"])
        assertEquals("已完成", strings("values-zh-rTW")["status_completed"])
    }

    @Test
    fun supportedLocalesCoverEnglishAndBothChineseScripts() {
        assertEquals(AppLanguage.English, AppLanguage.fromLocale(Locale.US))
        assertEquals(AppLanguage.English, AppLanguage.fromLocale(Locale.FRENCH))
        assertEquals(AppLanguage.SimplifiedChinese, AppLanguage.fromLocale(Locale.SIMPLIFIED_CHINESE))
        assertEquals(AppLanguage.SimplifiedChinese, AppLanguage.fromLocale(Locale.forLanguageTag("zh-Hans-SG")))
        assertEquals(AppLanguage.TraditionalChinese, AppLanguage.fromLocale(Locale.TRADITIONAL_CHINESE))
        assertEquals(AppLanguage.TraditionalChinese, AppLanguage.fromLocale(Locale.forLanguageTag("zh-Hant-HK")))
        assertEquals(listOf("en", "zh-CN", "zh-TW"), AppLanguage.entries.map { it.tag })
    }

    @Test
    fun localizedNumberUnitsPreserveMagnitudeAndSign() {
        val event = EconomicEvent(
            id = 1, provider = "test", providerId = "test", releaseGroupId = null,
            country = "United States", currency = "USD", category = "employment",
            event = "Test", eventTime = "2026-09-01T12:00:00Z", importance = 3,
            actual = null, previous = null, consensus = null, forecast = null,
            unit = "count", status = "scheduled",
        )
        val zh = Locale.SIMPLIFIED_CHINESE
        val tw = Locale.TRADITIONAL_CHINESE
        assertEquals("--", event.value(null, zh))
        assertEquals("9999", event.value("9999", zh))
        assertEquals("1万", event.value("10000", zh))
        assertEquals("-12.34万", event.value("-123400", zh))
        assertEquals("1亿", event.value("100000000", zh))
        assertEquals("1万亿", event.value("1000000000000", zh))
        assertEquals("1万", event.copy(unit = "currency").value("10000", zh))
        assertEquals("3.5%", event.copy(unit = "%").value("3.50", zh))
        assertEquals("1萬", event.value("10000", tw))
        assertEquals("1億", event.value("100000000", tw))
        assertEquals("1萬億", event.value("1000000000000", tw))
        assertEquals("10K", event.value("10000", Locale.ENGLISH))
        assertEquals("1B", event.value("1000000000", Locale.ENGLISH))
        assertEquals("+2.50bp", formatChange(2.5, "basis_points", Locale.ENGLISH))
        assertEquals("-2.50基點", formatChange(-2.5, "basis_points", tw, "基點"))
    }

    private fun strings(folder: String): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/$folder/strings.xml"))
        val nodes = document.getElementsByTagName("string")
        val pairs = (0 until nodes.length).map { index ->
            val node = nodes.item(index)
            node.attributes.getNamedItem("name").nodeValue to node.textContent
        }
        assertEquals("Duplicate resource keys in $folder", pairs.size, pairs.toMap().size)
        return pairs.toMap()
    }
}
