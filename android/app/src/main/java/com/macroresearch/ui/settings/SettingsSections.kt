package com.macroresearch.ui.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.macroresearch.R
import com.macroresearch.data.AnalysisMethod
import com.macroresearch.data.BackendSettings
import com.macroresearch.data.DataSourceMode
import com.macroresearch.data.DisplayMode
import com.macroresearch.data.MarketPreferences
import com.macroresearch.update.DownloadPhase

enum class SettingsSection(val route: String, @StringRes val title: Int) {
    DataSource("data_source", R.string.data_source),
    Countries("countries", R.string.countries_regions),
    Markets("markets", R.string.settings_markets),
    AiApi("ai_api", R.string.translation_api),
    AnalysisMethod("analysis_method", R.string.ai_analysis_method),
    News("news", R.string.ai_news_context),
    Display("display", R.string.display_size),
    Language("language", R.string.language),
    Updates("updates", R.string.app_update);

    companion object {
        fun fromRoute(route: String?): SettingsSection? = entries.firstOrNull { it.route == route }
    }
}

/** 摘要只保存字符串资源与参数，在组合阶段解析，便于在 JVM 单测中校验映射。 */
data class SettingsSummary(
    @StringRes val text: Int? = null,
    val args: List<Any> = emptyList(),
    val literal: String? = null,
    val highlight: Boolean = false,
)

@Composable
fun SettingsSummary.resolve(): String =
    literal ?: text?.let { stringResource(it, *args.toTypedArray()) }.orEmpty()

internal fun dataSourceSummary(settings: BackendSettings): SettingsSummary = when {
    settings.mode == DataSourceMode.DIRECT -> SettingsSummary(R.string.data_source_direct)
    settings.configured -> SettingsSummary(R.string.settings_summary_backend_configured)
    else -> SettingsSummary(R.string.settings_summary_backend_incomplete)
}

internal fun countriesSummary(selected: Int, total: Int) =
    SettingsSummary(R.string.settings_summary_ratio, listOf(selected, total))

internal fun marketsSummary(selected: Int) =
    SettingsSummary(R.string.settings_summary_ratio, listOf(selected, MarketPreferences.MAX_SELECTIONS))

internal fun aiApiSummary(configured: Boolean) =
    SettingsSummary(if (configured) R.string.api_key_configured else R.string.api_key_not_configured)

internal fun analysisMethodSummary(method: AnalysisMethod) = SettingsSummary(
    when (method) {
        AnalysisMethod.NUMBERS_ONLY -> R.string.ai_method_numbers_only
        AnalysisMethod.EX_ANTE_THEN_COMPARE -> R.string.ai_method_two_stage
        AnalysisMethod.SINGLE_PASS -> R.string.ai_method_single_pass
    },
)

internal fun newsSummary(enabled: Boolean) =
    SettingsSummary(if (enabled) R.string.settings_summary_on else R.string.settings_summary_off)

internal fun displaySummary(mode: DisplayMode) = SettingsSummary(
    when (mode) {
        DisplayMode.AUTO -> R.string.display_auto
        DisplayMode.COMPACT -> R.string.display_compact
        DisplayMode.SYSTEM -> R.string.display_system
    },
)

internal fun languageSummary(language: AppLanguage) = SettingsSummary(literal = language.nativeName)

/** 下载进行中或待安装优先于"有新版本"，最后才显示当前版本号。 */
internal fun updateSummary(versionName: String, availableVersion: String?, download: DownloadPhase): SettingsSummary =
    when (download) {
        DownloadPhase.READY -> SettingsSummary(R.string.settings_summary_update_ready, highlight = true)
        DownloadPhase.DOWNLOADING, DownloadPhase.PAUSED, DownloadPhase.VERIFYING ->
            SettingsSummary(R.string.update_downloading, highlight = true)
        else -> availableVersion
            ?.let { SettingsSummary(R.string.update_available, listOf(it), highlight = true) }
            ?: SettingsSummary(literal = versionName)
    }

/** 已选项在达到下限时不可取消，未选项在达到上限时不可勾选。 */
internal fun marketToggleEnabled(checked: Boolean, selectedCount: Int): Boolean =
    if (checked) selectedCount > MarketPreferences.MIN_SELECTIONS else selectedCount < MarketPreferences.MAX_SELECTIONS
