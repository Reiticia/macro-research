package com.macroresearch.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macroresearch.BuildConfig
import com.macroresearch.R
import com.macroresearch.ui.theme.ResearchLayout
import com.macroresearch.ui.theme.TabularStyle
import com.macroresearch.update.*
import java.text.DateFormat
import java.util.Date

@Composable
fun AppUpdateSettings(updates: UpdateCoordinator, onShowNotes: () -> Unit, onInstall: () -> Unit) {
    val state by updates.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val automaticLabel = stringResource(R.string.update_auto_check)
    Card(
        shape = RoundedCornerShape(ResearchLayout.cardCorner),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(ResearchLayout.cardPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsSectionHeader(stringResource(R.string.app_update))
            Text(
                stringResource(R.string.update_current_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                style = MaterialTheme.typography.bodyMedium.merge(TabularStyle),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(automaticLabel, modifier = Modifier.weight(1f))
                Switch(
                    checked = state.automatic,
                    onCheckedChange = updates::setAutomatic,
                    modifier = Modifier.semantics { contentDescription = automaticLabel },
                )
            }
            if (BuildConfig.DEBUG) {
                Text(
                    stringResource(R.string.update_debug_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.lastSuccess > 0) {
                Text(
                    stringResource(
                        R.string.update_last_checked,
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(state.lastSuccess)),
                    ),
                    style = MaterialTheme.typography.bodySmall.merge(TabularStyle),
                )
            }
            when (state.check.phase) {
                CheckPhase.IDLE -> Unit
                CheckPhase.CHECKING -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.update_checking))
                }
                CheckPhase.CURRENT -> Text(stringResource(R.string.update_current))
                CheckPhase.NO_RELEASE -> Text(stringResource(R.string.update_no_release))
                CheckPhase.AVAILABLE -> Text(
                    stringResource(R.string.update_available, state.check.release!!.manifest.versionName),
                    color = MaterialTheme.colorScheme.primary,
                )
                CheckPhase.FAILED -> UpdateErrorText(state.check.error, state.check.retryAt)
            }
            val download = state.download
            if (download.phase != DownloadPhase.IDLE) {
                Text(
                    stringResource(
                        when (download.phase) {
                            DownloadPhase.DOWNLOADING -> R.string.update_downloading
                            DownloadPhase.PAUSED -> R.string.update_paused
                            DownloadPhase.VERIFYING -> R.string.update_verifying
                            DownloadPhase.READY -> R.string.update_ready
                            else -> R.string.update_download_failed
                        },
                    ),
                )
                if (download.phase in setOf(DownloadPhase.DOWNLOADING, DownloadPhase.PAUSED)) {
                    if (download.total > 0) {
                        LinearProgressIndicator(
                            progress = { (download.bytes.toFloat() / download.total).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            stringResource(R.string.update_download_progress, download.bytes / 1024, download.total / 1024),
                            style = MaterialTheme.typography.bodySmall.merge(TabularStyle),
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    if (download.systemReason != 0) {
                        Text(
                            stringResource(R.string.update_system_reason, download.systemReason),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                UpdateErrorText(download.error)
            }
            Button(enabled = state.check.phase != CheckPhase.CHECKING, onClick = { updates.check() }) {
                Text(stringResource(R.string.update_check))
            }
            val target = state.downloadRelease ?: state.check.release
            when (download.phase) {
                DownloadPhase.DOWNLOADING, DownloadPhase.PAUSED, DownloadPhase.VERIFYING -> {
                    TextButton(onClick = updates::cancelDownload) {
                        Text(stringResource(R.string.update_cancel_download))
                    }
                }
                DownloadPhase.READY -> {
                    Button(onClick = onInstall) { Text(stringResource(R.string.update_install)) }
                    TextButton(onClick = updates::cancelDownload) { Text(stringResource(R.string.update_cancel_download)) }
                }
                else -> if (target != null && target.manifest.versionCode > BuildConfig.VERSION_CODE && state.check.error != UpdateError.UNSUPPORTED_ANDROID) {
                    Button(onClick = { updates.download(target) }) {
                        Text(
                            stringResource(
                                if (download.phase == DownloadPhase.FAILED) R.string.update_retry_download
                                else R.string.update_download,
                            ),
                        )
                    }
                }
            }
            TextButton(onClick = onShowNotes) { Text(stringResource(R.string.whats_new)) }
            TextButton(onClick = { openReleasePage(context) }) { Text(stringResource(R.string.update_release_page)) }
        }
    }
}

@Composable
fun ReleaseNotesContent(notes: ReleaseNotes, limit: Int = Int.MAX_VALUE) {
    val language = LocalConfiguration.current.locales[0].toLanguageTag()
    val localized = notes.localized(language)
    var budget = limit
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(R.string.update_client_notes to localized.client, R.string.update_backend_notes to localized.backend).forEach { (label, items) ->
            if (items.isNotEmpty() && budget > 0) {
                Text(stringResource(label), fontWeight = FontWeight.Bold)
                items.forEach { item ->
                    if (budget > 0) {
                        val text = item.take(budget)
                        budget -= text.length
                        Text("• $text", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
fun WhatsNewDialog(
    notes: ReleaseNotes,
    mandatory: Boolean,
    acknowledgeFailed: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!mandatory) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !mandatory, dismissOnClickOutside = !mandatory),
        title = { Text("${stringResource(R.string.whats_new)} · ${notes.versionName}") },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ReleaseNotesContent(notes)
                if (acknowledgeFailed) UpdateErrorText(UpdateError.STATE_STORAGE)
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.whats_new_acknowledge)) } },
    )
}

@Composable
fun AvailableUpdateDialog(updates: UpdateCoordinator, release: UpdateRelease) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = updates::defer,
        title = { Text(stringResource(R.string.update_available, release.manifest.versionName)) },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (BuildConfig.DEBUG) Text(stringResource(R.string.update_debug_note))
                ReleaseNotesContent(release.manifest.releaseNotes, 4000)
                TextButton(onClick = { openReleasePage(context, release.pageUrl) }) {
                    Text(stringResource(R.string.update_release_page))
                }
            }
        },
        confirmButton = { TextButton(onClick = { updates.download(release) }) { Text(stringResource(R.string.update_download)) } },
        dismissButton = {
            Column {
                TextButton(onClick = updates::defer) { Text(stringResource(R.string.update_later)) }
                TextButton(onClick = updates::ignore) { Text(stringResource(R.string.update_ignore)) }
            }
        },
    )
}

private fun openReleasePage(context: android.content.Context, url: String = RELEASES_PAGE) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        android.widget.Toast.makeText(context, R.string.backend_key_bot_open_failed, android.widget.Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun UpdateErrorText(error: UpdateError?, retryAt: Long = 0) {
    if (error == null) return
    val label = when (error) {
        UpdateError.NETWORK -> R.string.update_error_network
        UpdateError.RATE_LIMIT -> R.string.update_error_rate_limit
        UpdateError.INVALID_RELEASE -> R.string.update_error_release
        UpdateError.UNSUPPORTED_ANDROID -> R.string.update_error_android
        UpdateError.STORAGE -> R.string.update_error_storage
        UpdateError.MISSING_FILE -> R.string.update_error_missing
        UpdateError.VERIFICATION -> R.string.update_error_verification
        UpdateError.SIGNATURE -> R.string.update_error_signature
        UpdateError.INSTALL -> R.string.update_error_install
        UpdateError.STATE_STORAGE -> R.string.update_error_state
        UpdateError.DOWNLOAD -> R.string.update_download_failed
    }
    Text(stringResource(label), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    if (retryAt > 0) {
        Text(
            stringResource(R.string.update_retry_at, DateFormat.getDateTimeInstance().format(Date(retryAt))),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
