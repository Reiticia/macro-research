package com.macroresearch

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.macroresearch.data.model.SocketEvent

/** Receives backend release data and renders it through the existing notification channel. */
class FcmMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (data["type"] != "economic_event_released") return
        val eventId = data["eventId"]?.toLongOrNull() ?: return
        val event = SocketEvent(
            type = data["type"].orEmpty(),
            eventId = eventId,
            event = data["event"],
            eventZhCn = data["eventZhCn"]?.takeIf { it.isNotEmpty() },
            eventZhTw = data["eventZhTw"]?.takeIf { it.isNotEmpty() },
            actual = data["actual"]?.takeIf { it.isNotEmpty() },
            consensus = data["consensus"]?.takeIf { it.isNotEmpty() },
        )
        val app = application as? MacroApplication ?: return
        if (!app.usesBackend()) return
        app.showPushEventIfFollowed(event)
    }
}
