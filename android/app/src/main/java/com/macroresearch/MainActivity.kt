package com.macroresearch

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.macroresearch.ui.MacroApp
import com.macroresearch.ui.settings.AvailableUpdateDialog
import com.macroresearch.ui.settings.WhatsNewDialog
import com.macroresearch.ui.theme.MacroTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private var notificationPermissionAsked = false
    private var releaseRefreshJob: Job? = null
    private var notificationEventId by mutableStateOf<Long?>(null)
    private var systemDialogBusy by mutableStateOf(false)
    private var manualNotes by mutableStateOf(false)
    private var acknowledgementFailed by mutableStateOf(false)
    private var pendingInstall = false
    private var startupPermissionsReady by mutableStateOf(false)
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { systemDialogBusy = false }
    private val unknownSources = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        systemDialogBusy = false
        if (pendingInstall) { pendingInstall = false; installUpdate(requestPermission = false) }
    }
    private val updateInstaller = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { systemDialogBusy = false }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manualNotes = savedInstanceState?.getBoolean("manualNotes") ?: false
        notificationPermissionAsked = savedInstanceState?.getBoolean("notificationAsked") ?: false
        pendingInstall = savedInstanceState?.getBoolean("pendingInstall") ?: false
        systemDialogBusy = savedInstanceState?.getBoolean("systemDialogBusy") ?: false
        notificationEventId = intent.notificationEventId()
        enableEdgeToEdge()
        val app = application as MacroApplication
        setContent {
            MacroTheme {
                MacroApp(
                    repository = app.repository,
                    notificationEventId = notificationEventId,
                    onNotificationHandled = { notificationEventId = null },
                    updates = app.updates,
                    onShowWhatsNew = { manualNotes = true },
                    onInstallUpdate = { installUpdate() },
                )
                val pending by app.whatsNew.pending.collectAsStateWithLifecycle()
                val updateState by app.updates.state.collectAsStateWithLifecycle()
                LaunchedEffect(pending, updateState.initialized) {
                    if (!pending && updateState.initialized) requestNotificationPermission()
                }
                if (updateState.initialized && (pending || manualNotes)) {
                    WhatsNewDialog(app.whatsNew.notes, pending, acknowledgementFailed,
                        onConfirm = {
                            if (!pending || app.whatsNew.acknowledge()) {
                                manualNotes = false; acknowledgementFailed = false
                            } else acknowledgementFailed = true
                        }, onDismiss = { manualNotes = false })
                } else if (updateState.initialized && startupPermissionsReady && !systemDialogBusy) {
                    updateState.prompt?.let { AvailableUpdateDialog(app.updates, it) }
                }
            }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notificationEventId = intent.notificationEventId()
    }
    private fun Intent.notificationEventId(): Long? =
        getLongExtra(NotificationCenter.EXTRA_EVENT_ID, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }

    override fun onStart() {
        super.onStart()
        val app = application as MacroApplication
        app.startBackendPush()
        releaseRefreshJob?.cancel()
        releaseRefreshJob = lifecycleScope.launch(Dispatchers.IO) { app.repository.runForegroundReleaseRefresh() }
        app.updates.onForeground()
        if (!app.whatsNew.pending.value) requestNotificationPermission()
    }
    override fun onStop() {
        super.onStop()
        releaseRefreshJob?.cancel()
        releaseRefreshJob = null
        val app = application as MacroApplication
        app.stopBackendPush()
        app.updates.onBackground()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("manualNotes", manualNotes)
        outState.putBoolean("notificationAsked", notificationPermissionAsked)
        outState.putBoolean("pendingInstall", pendingInstall)
        outState.putBoolean("systemDialogBusy", systemDialogBusy)
        super.onSaveInstanceState(outState)
    }
    private fun installUpdate(requestPermission: Boolean = true) {
        if (systemDialogBusy) return
        systemDialogBusy = true // Includes asynchronous revalidation: repeated clicks cannot queue installers.
        val app = application as MacroApplication
        lifecycleScope.launch {
            try {
                val file = app.updates.fileForInstallation()
                if (!packageManager.canRequestPackageInstalls()) {
                    if (!requestPermission) { systemDialogBusy = false; app.updates.installationFailed(); return@launch }
                    pendingInstall = true
                    unknownSources.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                    return@launch
                }
                val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.update-files", file)
                updateInstaller.launch(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("APK", uri) })
            } catch (cancelled: CancellationException) {
                systemDialogBusy = false
                throw cancelled
            } catch (_: Exception) {
                pendingInstall = false; systemDialogBusy = false
                app.updates.installationFailed()
            }
        }
    }
    private fun requestNotificationPermission() {
        startupPermissionsReady = true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val app = application as MacroApplication
        if (!app.usesBackend() || notificationPermissionAsked || systemDialogBusy) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        notificationPermissionAsked = true
        systemDialogBusy = true
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
