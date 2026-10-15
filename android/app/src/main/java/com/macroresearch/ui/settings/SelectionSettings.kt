package com.macroresearch.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macroresearch.R
import com.macroresearch.data.CountryPreferences
import com.macroresearch.data.MacroRepository
import com.macroresearch.data.MarketPreferences
import com.macroresearch.ui.common.assetLabel
import com.macroresearch.ui.common.countryLabel
import com.macroresearch.ui.common.terminal.ToggleRow
import com.macroresearch.ui.theme.ResearchLayout

@Composable
fun CountrySettings(repository: MacroRepository) {
    val selected by repository.selectedCountries.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().padding(vertical = ResearchLayout.smallGap)) {
        CountryPreferences.SUPPORTED_COUNTRIES.forEach { country ->
            ToggleRow(countryLabel(country), country in selected, onToggle = { repository.setCountryEnabled(country, it) })
        }
    }
}

@Composable
fun MarketSettings(repository: MacroRepository) {
    val selected by repository.selectedMarkets.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().padding(vertical = ResearchLayout.smallGap)) {
        MarketPreferences.SUPPORTED_MARKETS.forEach { market ->
            val checked = market in selected
            ToggleRow(
                assetLabel(market),
                checked,
                onToggle = { repository.setMarketEnabled(market, it) },
                enabled = marketToggleEnabled(checked, selected.size),
            )
        }
        Text(
            stringResource(R.string.market_selection_note, selected.size),
            Modifier.padding(horizontal = ResearchLayout.pagePadding, vertical = ResearchLayout.smallGap),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
