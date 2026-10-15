package com.macroresearch.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macroresearch.R
import com.macroresearch.data.DataSourceMode
import com.macroresearch.data.MacroRepository
import com.macroresearch.data.MarketPreferences
import com.macroresearch.ui.common.terminal.DetailTopBar
import com.macroresearch.ui.common.terminal.SectionHeader
import com.macroresearch.ui.theme.ResearchLayout
import com.macroresearch.ui.theme.ResearchType
import com.macroresearch.update.UpdateCoordinator

@Composable
fun SettingsDetailScreen(
    section: SettingsSection,
    repository: MacroRepository,
    padding: PaddingValues,
    updates: UpdateCoordinator?,
    onShowWhatsNew: () -> Unit,
    onInstallUpdate: () -> Unit,
    onBack: () -> Unit,
) {
    val dataSource by repository.dataSourceSettings.collectAsStateWithLifecycle()
    val markets by repository.selectedMarkets.collectAsStateWithLifecycle()
    // TopAppBar 自行处理状态栏 inset，只保留外层底部 padding。
    Column(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
        DetailTopBar(
            title = stringResource(section.title),
            onBack = onBack,
            actions = {
                if (section == SettingsSection.Markets) {
                    Text(
                        stringResource(R.string.settings_summary_ratio, markets.size, MarketPreferences.MAX_SELECTIONS),
                        Modifier.padding(end = ResearchLayout.pagePadding),
                        style = ResearchType.small,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = ResearchLayout.gap).imePadding()) {
            when (section) {
                SettingsSection.DataSource -> {
                    DataSourceSettings(repository)
                    // 网络代理只作用于直连模式的公开数据源。
                    if (dataSource.mode == DataSourceMode.DIRECT) {
                        SectionHeader(stringResource(R.string.data_network))
                        DataNetworkSettings(repository)
                    }
                }
                SettingsSection.Countries -> CountrySettings(repository)
                SettingsSection.Markets -> MarketSettings(repository)
                SettingsSection.AiApi -> AiApiSettings(repository)
                SettingsSection.AnalysisMethod -> AnalysisMethodSettings(repository)
                SettingsSection.News -> NewsContextSettings(repository)
                SettingsSection.Display -> DisplaySettingsCard()
                SettingsSection.Language -> LanguageSettings()
                SettingsSection.Updates -> updates?.let { AppUpdateSettings(it, onShowWhatsNew, onInstallUpdate) }
            }
        }
    }
}
