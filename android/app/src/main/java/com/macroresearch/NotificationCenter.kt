package com.macroresearch

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.macroresearch.data.model.SocketEvent
import java.util.Locale

/**
 * Local notifications for followed events that are pushed after release.
 *
 * The caller filters out unstarred events. The FCM messaging service may call this while the
 * app process is in the background.
 */
class NotificationCenter(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    init {
        ensureChannel()
    }

    private fun ensureChannel() {
        val localized = ContextCompat.getContextForLanguage(context)
        val channel = NotificationChannel(
            RELEASE_CHANNEL,
            localized.getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = localized.getString(R.string.notification_channel_description) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun show(event: SocketEvent) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val localized = ContextCompat.getContextForLanguage(context)
        val title: String
        val body: String
        when (event.type) {
            "economic_event_released" -> {
                val traditional = localized.resources.configuration.locales[0]
                    .language == Locale.CHINESE.language &&
                    localized.resources.configuration.locales[0].country in setOf("TW", "HK", "MO")
                val name = event.localizedName(traditional)
                    ?: localized.getString(R.string.economic_data)
                title = localized.getString(R.string.notification_released, name)
                body = localized.getString(
                    R.string.actual_consensus,
                    event.actual ?: "--",
                    event.consensus ?: "--",
                )
            }
            else -> return
        }
        val id = event.eventId?.hashCode() ?: event.hashCode()
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            event.eventId?.let { putExtra(EXTRA_EVENT_ID, it) }
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            id,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            id,
            NotificationCompat.Builder(context, RELEASE_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build(),
        )
    }

    companion object {
        const val EXTRA_EVENT_ID = "com.macroresearch.EXTRA_EVENT_ID"
        private const val RELEASE_CHANNEL = "economic_release"
    }
}
