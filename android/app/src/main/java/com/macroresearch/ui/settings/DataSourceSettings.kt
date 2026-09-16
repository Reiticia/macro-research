package com.macroresearch.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macroresearch.R
import com.macroresearch.data.BackendPreferences
import com.macroresearch.data.DataSourceMode
import com.macroresearch.data.MacroRepository
import com.macroresearch.data.remote.BackendUnauthorizedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Data-source selection.
 *
 * Switching modes must be confirmed because the two id spaces cannot be mixed: the local event
 * cache and the follow list are dropped, and the user has to re-follow events on the other side.
 */
@Composable
fun DataSourceSettings(repository: MacroRepository) {
    val settings by repository.dataSourceSettings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var baseUrl by rememberSaveable(settings.baseUrl) { mutableStateOf(settings.baseUrl) }
    var token by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var status by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pendingMode by remember { mutableStateOf<DataSourceMode?>(null) }
    // Strings must be resolved in composition; the click handler runs in a coroutine.
    val verifiedLabel = stringResource(R.string.backend_verified)
    val unauthorizedLabel = stringResource(R.string.backend_unauthorized)
    val unreachableLabel = stringResource(R.string.backend_unreachable)
    val incompleteLabel = stringResource(R.string.data_source_incomplete)

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.data_source), fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.data_source_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.mode == DataSourceMode.DIRECT,
                    onClick = { pendingMode = DataSourceMode.DIRECT },
                    label = { Text(stringResource(R.string.data_source_direct)) },
                )
                FilterChip(
                    selected = settings.mode == DataSourceMode.BACKEND,
                    onClick = {
                        // Switching wipes the local cache, so require a usable endpoint first;
                        // otherwise the user would lose data and land on an error screen.
                        val incomplete = baseUrl.isBlank() ||
                            (token.isBlank() && !settings.tokenConfigured)
                        if (incomplete) {
                            error = incompleteLabel
                            status = null
                        } else {
                            pendingMode = DataSourceMode.BACKEND
                        }
                    },
                    label = { Text(stringResource(R.string.data_source_backend)) },
                )
            }
            Text(
                stringResource(
                    if (settings.mode == DataSourceMode.BACKEND) R.string.data_source_backend_note
                    else R.string.data_source_direct_note,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (settings.mode == DataSourceMode.BACKEND) {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it; error = null; status = null },
                    label = { Text(stringResource(R.string.backend_address)) },
                    placeholder = { Text("https://example.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (settings.allowCleartext || BackendPreferences.usesPlainHttp(baseUrl)) {
                    // Escape hatch for an IP:port tunnel with no certificate. Off by default and
                    // limited to private addresses, because the token travels in the clear.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Checkbox(
                            checked = settings.allowCleartext,
                            onCheckedChange = {
                                repository.setAllowBackendCleartext(it)
                                error = null
                                status = null
                            },
                        )
                        Text(
                            stringResource(R.string.backend_allow_cleartext),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text(
                        stringResource(R.string.backend_cleartext_warning),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it; error = null; status = null },
                    label = { Text(stringResource(R.string.backend_token)) },
                    supportingText = { Text(stringResource(R.string.backend_token_note)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!settings.tokenConfigured && token.isBlank()) {
                    Text(
                        stringResource(R.string.backend_token_missing),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                status?.let {
                    Text(it, color = MaterialTheme.colorScheme.primary)
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            repository.clearBackendSettings()
                            baseUrl = ""
                            token = ""
                            status = null
                            error = null
                        },
                    ) { Text(stringResource(R.string.backend_clear)) }
                    Button(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            status = null
                            error = null
                            scope.launch {
                                try {
                                    repository.saveBackendSettings(baseUrl, token)
                                    val meta = repository.verifyBackend()
                                    status = if (meta.version.isNullOrBlank()) {
                                        verifiedLabel
                                    } else {
                                        "$verifiedLabel v${meta.version} · AI ${if (meta.aiEnabled) "on" else "off"}"
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    error = when (failure) {
                                        is BackendUnauthorizedException -> unauthorizedLabel
                                        else -> failure.message ?: unreachableLabel
                                    }
                                } finally {
                                    busy = false
                                }
                            }
                        },
                    ) { Text(stringResource(R.string.backend_test)) }
                }
            }
        }
    }

    pendingMode?.let { mode ->
        AlertDialog(
            onDismissRequest = { pendingMode = null },
            title = { Text(stringResource(R.string.data_source_switch_title)) },
            text = { Text(stringResource(R.string.data_source_switch_body)) },
            confirmButton = {
                Button(onClick = {
                    val target = mode
                    pendingMode = null
                    scope.launch { repository.setDataSourceMode(target) }
                }) { Text(stringResource(R.string.data_source_switch_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingMode = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
