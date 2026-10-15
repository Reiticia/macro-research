package com.macroresearch.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macroresearch.R
import com.macroresearch.data.BackendPreferences
import com.macroresearch.data.DataSourceMode
import com.macroresearch.data.MacroRepository
import com.macroresearch.data.remote.BackendAuthenticationDisabledException
import com.macroresearch.data.remote.BackendProtocolException
import com.macroresearch.data.remote.BackendUnauthorizedException
import com.macroresearch.ui.theme.ResearchLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun DataSourceSettings(repository: MacroRepository) {
    val settings by repository.dataSourceSettings.collectAsStateWithLifecycle()
    val translationError by repository.translationError.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var baseUrl by rememberSaveable(settings.baseUrl) { mutableStateOf(settings.baseUrl) }
    var token by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pendingMode by remember { mutableStateOf<DataSourceMode?>(null) }
    var showBackendConfig by rememberSaveable {
        mutableStateOf(settings.mode == DataSourceMode.BACKEND)
    }
    val verifiedLabel = stringResource(R.string.backend_verified)
    val backendAiEnabledLabel = stringResource(R.string.backend_ai_enabled)
    val backendAiDisabledLabel = stringResource(R.string.backend_ai_disabled)
    val unauthorizedLabel = stringResource(R.string.backend_unauthorized)
    val authenticationDisabledLabel = stringResource(R.string.backend_authentication_disabled)
    val protocolMismatchLabel = stringResource(R.string.backend_protocol_mismatch)
    val unreachableLabel = stringResource(R.string.backend_unreachable)
    val incompleteLabel = stringResource(R.string.data_source_incomplete)
    val botUnavailableLabel = stringResource(R.string.backend_key_bot_unavailable)
    val botOpenFailedLabel = stringResource(R.string.backend_key_bot_open_failed)
    val addressInvalidLabel = stringResource(R.string.backend_key_address_invalid)
    val tokenLabel = stringResource(R.string.backend_token)

    Card(
        shape = RoundedCornerShape(ResearchLayout.cardCorner),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(ResearchLayout.cardPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsSectionHeader(stringResource(R.string.data_source))
            Text(
                stringResource(R.string.data_source_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val isDirect = settings.mode == DataSourceMode.DIRECT
                FilterChip(
                    enabled = !busy,
                    selected = isDirect,
                    onClick = { pendingMode = DataSourceMode.DIRECT },
                    shape = RoundedCornerShape(ResearchLayout.innerCorner),
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (isDirect) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        else MaterialTheme.colorScheme.outlineVariant,
                    ),
                    label = {
                        Text(
                            stringResource(R.string.data_source_direct),
                            fontWeight = if (isDirect) FontWeight.Bold else FontWeight.Medium,
                        )
                    },
                )
                val isBackend = settings.mode == DataSourceMode.BACKEND
                FilterChip(
                    enabled = !busy,
                    selected = isBackend,
                    onClick = {
                        showBackendConfig = true
                        val incomplete = baseUrl.isBlank() ||
                            (token.isBlank() && !settings.tokenConfigured)
                        if (incomplete) {
                            error = incompleteLabel
                            status = null
                        } else {
                            pendingMode = DataSourceMode.BACKEND
                        }
                    },
                    shape = RoundedCornerShape(ResearchLayout.innerCorner),
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (isBackend) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        else MaterialTheme.colorScheme.outlineVariant,
                    ),
                    label = {
                        Text(
                            stringResource(R.string.data_source_backend),
                            fontWeight = if (isBackend) FontWeight.Bold else FontWeight.Medium,
                        )
                    },
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

            if (showBackendConfig && settings.mode != DataSourceMode.BACKEND) {
                Text(
                    stringResource(R.string.backend_setup_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (showBackendConfig) {
                OutlinedTextField(
                    enabled = !busy,
                    value = baseUrl,
                    onValueChange = { baseUrl = it; error = null; status = null },
                    label = { Text(stringResource(R.string.backend_address)) },
                    placeholder = { Text("https://example.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (settings.allowCleartext || BackendPreferences.usesPlainHttp(baseUrl)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Checkbox(
                            enabled = !busy,
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
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(tokenLabel, modifier = Modifier.weight(1f))
                    TextButton(
                        enabled = !busy && baseUrl.isNotBlank(),
                        onClick = {
                            busy = true
                            error = null
                            status = null
                            scope.launch {
                                try {
                                    val url = repository.backendKeyRequestBotUrl(baseUrl)
                                    if (url == null) {
                                        error = botUnavailableLabel
                                    } else {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: ActivityNotFoundException) {
                                    error = botOpenFailedLabel
                                } catch (_: IllegalArgumentException) {
                                    error = addressInvalidLabel
                                } catch (_: Exception) {
                                    error = botUnavailableLabel
                                } finally {
                                    busy = false
                                }
                            }
                        },
                    ) { Text(stringResource(R.string.backend_get_api_key)) }
                }
                OutlinedTextField(
                    enabled = !busy,
                    value = token,
                    onValueChange = { token = it; error = null; status = null },
                    supportingText = { Text(stringResource(R.string.backend_token_note)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = tokenLabel },
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
                if (settings.baseUrl.isNotBlank()) {
                    translationError?.let {
                        Text(
                            stringResource(R.string.backend_translation_failed, it),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            scope.launch {
                                repository.clearBackendSettings()
                                baseUrl = ""
                                token = ""
                                status = null
                                error = null
                            }
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
                                    val result = repository.verifyBackend()
                                    val aiLabel = if (result.aiEnabled) backendAiEnabledLabel else backendAiDisabledLabel
                                    status = "$verifiedLabel · $aiLabel"
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    error = when (failure) {
                                        is BackendUnauthorizedException -> unauthorizedLabel
                                        is BackendAuthenticationDisabledException -> authenticationDisabledLabel
                                        is BackendProtocolException -> protocolMismatchLabel
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
                    if (target == DataSourceMode.DIRECT) showBackendConfig = false
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
