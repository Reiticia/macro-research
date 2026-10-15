package com.macroresearch.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Functions
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macroresearch.BuildConfig
import com.macroresearch.R
import com.macroresearch.data.CountryPreferences
import com.macroresearch.data.MacroRepository
import com.macroresearch.ui.common.appLocale
import com.macroresearch.ui.common.terminal.Hairline
import com.macroresearch.ui.common.terminal.PageHeader
import com.macroresearch.ui.common.terminal.SectionHeader
import com.macroresearch.ui.common.terminal.SettingsRow
import com.macroresearch.ui.theme.LocalDisplaySettings
import com.macroresearch.ui.theme.ResearchLayout
import com.macroresearch.update.CheckPhase
import com.macroresearch.update.UpdateCoordinator

data class SettingsEntry(val section: SettingsSection, val icon: ImageVector, val summary: SettingsSummary?)

data class SettingsGroup(@StringRes val title: Int, val entries: List<SettingsEntry>)

@Composable
fun SettingsScreen(
    repository: MacroRepository,
    padding: PaddingValues,
    updates: UpdateCoordinator?,
    onShowWhatsNew: () -> Unit,
    onOpen: (SettingsSection) -> Unit,
) {
    val dataSource by repository.dataSourceSettings.collectAsStateWithLifecycle()
    val countries by repository.selectedCountries.collectAsStateWithLifecycle()
    val markets by repository.selectedMarkets.collectAsStateWithLifecycle()
    val translation by repository.translationSettings.collectAsStateWithLifecycle()
    val method by repository.analysisMethod.collectAsStateWithLifecycle()
    val news by repository.newsSettings.collectAsStateWithLifecycle()
    val display = LocalDisplaySettings.current
    val updateState = updates?.let { it.state.collectAsStateWithLifecycle().value }
    val groups = listOf(
        SettingsGroup(
            R.string.settings_group_data,
            listOf(
                SettingsEntry(SettingsSection.DataSource, Icons.Outlined.SwapHoriz, dataSourceSummary(dataSource)),
                SettingsEntry(
                    SettingsSection.Countries,
                    Icons.Outlined.Public,
                    countriesSummary(countries.size, CountryPreferences.SUPPORTED_COUNTRIES.size),
                ),
                SettingsEntry(SettingsSection.Markets, Icons.Outlined.QueryStats, marketsSummary(markets.size)),
            ),
        ),
        SettingsGroup(
            R.string.settings_group_ai,
            listOf(
                SettingsEntry(SettingsSection.AiApi, Icons.Outlined.Key, aiApiSummary(translation.configured)),
                SettingsEntry(SettingsSection.AnalysisMethod, Icons.Outlined.Functions, analysisMethodSummary(method)),
                SettingsEntry(SettingsSection.News, Icons.Outlined.Newspaper, newsSummary(news.enabled)),
            ),
        ),
        SettingsGroup(
            R.string.settings_group_interface,
            listOf(
                SettingsEntry(SettingsSection.Display, Icons.Outlined.TextFields, displaySummary(display.mode)),
                SettingsEntry(SettingsSection.Language, Icons.Outlined.Translate, languageSummary(AppLanguage.fromLocale(appLocale()))),
            ),
        ),
        SettingsGroup(
            R.string.settings_group_about,
            listOfNotNull(
                updateState?.let { state ->
                    val available = state.check.release?.manifest?.versionName
                        ?.takeIf { state.check.phase == CheckPhase.AVAILABLE }
                    SettingsEntry(
                        SettingsSection.Updates,
                        Icons.Outlined.SystemUpdate,
                        updateSummary(BuildConfig.VERSION_NAME, available, state.download.phase),
                    )
                },
            ),
        ),
    )
    Column(Modifier.fillMaxSize().padding(padding)) {
        PageHeader(title = stringResource(R.string.nav_settings), subtitle = stringResource(R.string.settings_subtitle))
        // "本次更新内容"与应用更新入口同在：没有更新模块时不显示。
        SettingsIndex(groups, onOpen, onShowWhatsNew.takeIf { updates != null }, Modifier.weight(1f))
    }
}

@Composable
fun SettingsIndex(
    groups: List<SettingsGroup>,
    onOpen: (SettingsSection) -> Unit,
    onShowWhatsNew: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = ResearchLayout.gap)) {
        groups.forEach { group ->
            val about = group.title == R.string.settings_group_about
            if (group.entries.isEmpty() && !(about && onShowWhatsNew != null)) return@forEach
            item(key = group.title) {
                Column {
                    SectionHeader(stringResource(group.title))
                    Hairline()
                    group.entries.forEach { entry ->
                        val summary = entry.summary
                        SettingsRow(
                            icon = entry.icon,
                            title = stringResource(entry.section.title),
                            summary = summary?.resolve(),
                            onClick = { onOpen(entry.section) },
                            summaryColor = if (summary?.highlight == true) MaterialTheme.colorScheme.primary else Color.Unspecified,
                        )
                    }
                    if (about && onShowWhatsNew != null) {
                        SettingsRow(Icons.Outlined.NewReleases, stringResource(R.string.whats_new), null, onClick = onShowWhatsNew)
                    }
                }
            }
        }
    }
}
