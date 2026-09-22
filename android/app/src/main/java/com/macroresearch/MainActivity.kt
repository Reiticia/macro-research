package com.macroresearch

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.macroresearch.ui.MacroApp
import com.macroresearch.ui.theme.MacroTheme

class MainActivity : AppCompatActivity() {
    private var notificationPermissionAsked = false
    private var notificationEventId by mutableStateOf<Long?>(null)
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationEventId = intent.notificationEventId()
        enableEdgeToEdge()
        val repository = (application as MacroApplication).repository
        setContent {
            MacroTheme {
                MacroApp(
                    repository = repository,
                    notificationEventId = notificationEventId,
                    onNotificationHandled = { notificationEventId = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notificationEventId = intent.notificationEventId()
    }

    private fun android.content.Intent.notificationEventId(): Long? =
        getLongExtra(NotificationCenter.EXTRA_EVENT_ID, Long.MIN_VALUE)
            .takeIf { it != Long.MIN_VALUE }

    override fun onStart() {
        super.onStart()
        val app = application as MacroApplication
        // The push channel only exists in backend mode; direct mode has no server to listen to.
        app.startBackendPush()
        requestNotificationPermission()
    }

    override fun onStop() {
        super.onStop()
        (application as MacroApplication).stopBackendPush()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val app = application as MacroApplication
        if (!app.usesBackend()) return
        if (notificationPermissionAsked) return
        notificationPermissionAsked = true
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
