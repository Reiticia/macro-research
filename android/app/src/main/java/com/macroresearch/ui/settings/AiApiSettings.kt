package com.macroresearch.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macroresearch.R
import com.macroresearch.data.MacroRepository
import com.macroresearch.ui.common.terminal.TerminalButton
import com.macroresearch.ui.common.terminal.TerminalOutlinedButton
import com.macroresearch.ui.theme.ResearchLayout
import kotlinx.coroutines.launch

@Composable
fun AiApiSettings(repository: MacroRepository) {
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

    Column(
        Modifier.fillMaxWidth().padding(horizontal = ResearchLayout.pagePadding, vertical = ResearchLayout.gap),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(if (settings.configured) R.string.api_key_configured else R.string.api_key_not_configured),
            style = MaterialTheme.typography.labelMedium,
            color = if (settings.configured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it; saved = false; localError = null },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.api_key)) },
            placeholder = {
                Text(stringResource(if (settings.configured) R.string.api_key_replace_hint else R.string.api_key_hint))
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
            TerminalOutlinedButton(
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
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.CloudDownload, contentDescription = null, modifier = Modifier.padding(end = 10.dp))
                }
                Text(stringResource(if (fetchingModels) R.string.fetching_models else R.string.fetch_models))
            }
        }
        (localError ?: remoteError)?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (saved) {
            Text(stringResource(R.string.api_settings_saved), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            if (settings.configured) {
                TextButton(
                    onClick = {
                        repository.clearTranslationSettings()
                        apiKey = ""
                        localError = null
                        saved = false
                    },
                ) { Text(stringResource(R.string.remove_api_key)) }
            }
            TerminalButton(
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
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp).selectableGroup()) {
                items(models, key = { it }) { model ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(selected = model == selected, role = Role.RadioButton, onClick = { onSelect(model) }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = model == selected, onClick = null)
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
