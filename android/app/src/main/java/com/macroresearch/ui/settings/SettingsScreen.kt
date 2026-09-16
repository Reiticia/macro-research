package com.macroresearch.ui.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macroresearch.R
import com.macroresearch.data.CountryPreferences
import com.macroresearch.data.DataSourceMode
import com.macroresearch.data.MacroRepository
import com.macroresearch.data.MarketPreferences
import com.macroresearch.ui.common.assetLabel
import com.macroresearch.ui.common.countryLabel
import com.macroresearch.ui.theme.ResearchLayout
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(repository: MacroRepository, padding: PaddingValues) {
    val selectedCountries by repository.selectedCountries.collectAsStateWithLifecycle()
    val countries = CountryPreferences.SUPPORTED_COUNTRIES.associateWith { it in selectedCountries }
    val selectedMarkets by repository.selectedMarkets.collectAsStateWithLifecycle()
    val markets = MarketPreferences.SUPPORTED_MARKETS.associateWith { it in selectedMarkets }
    val translation by repository.translationSettings.collectAsStateWithLifecycle()
    val dataSource by repository.dataSourceSettings.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = ResearchLayout.pagePadding, vertical = ResearchLayout.gap),
        verticalArrangement = Arrangement.spacedBy(ResearchLayout.gap),
    ) {
        Column {
            Text(
                stringResource(R.string.nav_settings),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.settings_subtitle),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(ResearchLayout.gap),
        ) {
            item { DataSourceSettings(repository) }
            item { DisplaySettingsCard() }
            if (dataSource.mode == DataSourceMode.DIRECT) {
                item { DataNetworkSettings(repository) }
                item { TranslationApiSettings(repository) }
            }
            item { AnalysisMethodSettings(repository) }
            item {
                // In backend mode the server supplies Chinese event names, so the language is
                // not gated behind a personal API key there.
                LanguageSettings(
                    chineseEnabled = translation.configured ||
                        dataSource.mode == DataSourceMode.BACKEND,
                )
            }
            item {
                SettingsGroup(
                    stringResource(R.string.countries_regions),
                    countries,
                    { countryLabel(it) },
                ) { key, checked -> repository.setCountryEnabled(key, checked) }
            }
            item {
                MarketSettingsGroup(markets) { key, checked ->
                    repository.setMarketEnabled(key, checked)
                }
            }
        }
    }
}

@Composable
private fun DataNetworkSettings(repository: MacroRepository) {
    val configured by repository.proxyAddress.collectAsStateWithLifecycle()
    var address by rememberSaveable(configured) { mutableStateOf(configured) }
    var invalid by rememberSaveable { mutableStateOf(false) }
    var saved by rememberSaveable { mutableStateOf(false) }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.data_network), fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.data_proxy_note), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = address,
                onValueChange = { address = it; invalid = false; saved = false },
                label = { Text(stringResource(R.string.data_proxy)) },
                placeholder = { Text("host:port") },
                singleLine = true,
                isError = invalid,
                modifier = Modifier.fillMaxWidth(),
            )
            if (invalid) Text(stringResource(R.string.data_proxy_invalid), color = MaterialTheme.colorScheme.error)
            if (saved) Text(stringResource(R.string.data_proxy_saved), color = MaterialTheme.colorScheme.primary)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = {
                    repository.saveCalendarProxy("")
                    address = ""
                    invalid = false
                    saved = true
                }) { Text(stringResource(R.string.data_proxy_clear)) }
                Button(onClick = {
                    runCatching { repository.saveCalendarProxy(address) }
                        .onSuccess { invalid = false; saved = true }
                        .onFailure { invalid = true }
                }) { Text(stringResource(R.string.save)) }
            }
        }
    }
}

@Composable
private fun TranslationApiSettings(repository: MacroRepository) {
    val settings by repository.translationSettings.collectAsStateWithLifecycle()
    val remoteError by repository.translationError.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var apiKey by rememberSaveable { mutableStateOf("") }
    var baseUrl by rememberSaveable { mutableStateOf(settings.baseUrl) }
    var model by rememberSaveable { mutableStateOf(settings.model) }
    var revealKey by rememberSaveable { mutableStateOf(false) }
    var localError by rememberSaveable { mutableStateOf<String?>(null) }
    var saved by rememberSaveable { mutableStateOf(false) }
    var fetchingModels by rememberSaveable { mutableStateOf(false) }
    var showModelPicker by rememberSaveable { mutableStateOf(false) }
    var availableModels by remember { mutableStateOf(emptyList<String>()) }

    LaunchedEffect(settings.baseUrl, settings.model) {
        baseUrl = settings.baseUrl
        model = settings.model
    }

    Card {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column {
                        Text(stringResource(R.string.translation_api), fontWeight = FontWeight.Bold)
                        Text(
                            stringResource(
                                if (settings.configured) R.string.api_key_configured
                                else R.string.api_key_not_configured,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (settings.configured) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
            HorizontalDivider()
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it; saved = false; localError = null },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.api_key)) },
                placeholder = {
                    Text(
                        stringResource(
                            if (settings.configured) R.string.api_key_replace_hint
                            else R.string.api_key_hint,
                        ),
                    )
                },
                singleLine = true,
                visualTransformation = if (revealKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { revealKey = !revealKey }) {
                        Icon(
                            if (revealKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            stringResource(if (revealKey) R.string.hide_api_key else R.string.show_api_key),
                        )
                    }
                },
            )
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it; saved = false; localError = null },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.api_endpoint)) },
                supportingText = { Text(stringResource(R.string.api_endpoint_note)) },
                singleLine = true,
            )
            OutlinedTextField(
                value = model,
                onValueChange = { model = it; saved = false; localError = null },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.api_model)) },
                singleLine = true,
            )
            if (settings.configured) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            fetchingModels = true
                            localError = null
                            runCatching { repository.translationModels(baseUrl) }
                                .onSuccess { models ->
                                    availableModels = models
                                    showModelPicker = true
                                }
                                .onFailure { localError = it.message }
                            fetchingModels = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = baseUrl.isNotBlank() && !fetchingModels,
                ) {
                    if (fetchingModels) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            Icons.Outlined.CloudDownload,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 10.dp),
                        )
                    }
                    Text(
                        stringResource(
                            if (fetchingModels) R.string.fetching_models else R.string.fetch_models,
                        ),
                    )
                }
            }
            (localError ?: remoteError)?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (saved) {
                Text(
                    stringResource(R.string.api_settings_saved),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                if (settings.configured) {
                    TextButton(
                        onClick = {
                            repository.clearTranslationSettings()
                            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
                            apiKey = ""
                            localError = null
                            saved = false
                        },
                    ) { Text(stringResource(R.string.remove_api_key)) }
                }
                Button(
                    onClick = {
                        runCatching {
                            if (apiKey.isBlank() && settings.configured) {
                                repository.updateTranslationProvider(baseUrl, model)
                            } else {
                                repository.saveTranslationSettings(apiKey, baseUrl, model)
                                apiKey = ""
                            }
                        }.onSuccess {
                            localError = null
                            saved = true
                        }.onFailure { localError = it.message }
                    },
                    enabled = apiKey.isNotBlank() || settings.configured,
                ) { Text(stringResource(R.string.save)) }
            }
            Text(
                stringResource(R.string.api_key_security_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showModelPicker) {
        ModelPickerDialog(
            models = availableModels,
            selected = model,
            onSelect = { selected ->
                model = selected
                saved = false
                localError = null
                showModelPicker = false
            },
            onDismiss = { showModelPicker = false },
        )
    }
}

@Composable
private fun ModelPickerDialog(
    models: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.available_models)) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(models, key = { it }) { model ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(model) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = model == selected,
                            onClick = null,
                        )
                        Text(model, modifier = Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun SettingsGroup(
    title: String,
    values: Map<String, Boolean>,
    labelFor: @Composable (String) -> String,
    onChange: (String, Boolean) -> Unit,
) {
    Card {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            values.forEach { (label, checked) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked, { onChange(label, it) })
                    Text(labelFor(label))
                }
            }
        }
    }
}

@Composable
private fun MarketSettingsGroup(
    values: Map<String, Boolean>,
    onChange: (String, Boolean) -> Unit,
) {
    val selectedCount = values.count { it.value }
    Card {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.market_tracking), fontWeight = FontWeight.Bold)
            values.forEach { (market, checked) ->
                val enabled = if (checked) {
                    selectedCount > MarketPreferences.MIN_SELECTIONS
                } else {
                    selectedCount < MarketPreferences.MAX_SELECTIONS
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { onChange(market, it) },
                        enabled = enabled,
                    )
                    Text(assetLabel(market))
                }
            }
            Text(
                stringResource(R.string.market_selection_note, selectedCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
