package com.macroresearch.data.remote

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Owns the device's FCM topic subscriptions for starred backend events. */
class PushTopicManager {
    suspend fun subscribe(eventId: Long) {
        val messaging = runCatching { messaging() }.getOrNull() ?: return
        try {
            await(messaging.subscribeToTopic(topicName(eventId)))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Unable to subscribe to event topic", error)
        }
    }

    suspend fun unsubscribe(eventId: Long) {
        val messaging = runCatching { messaging() }.getOrNull() ?: return
        try {
            await(messaging.unsubscribeFromTopic(topicName(eventId)))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Unable to unsubscribe from event topic", error)
        }
    }

    suspend fun synchronize(eventIds: List<Long>) {
        eventIds.distinct().forEach { subscribe(it) }
    }

    suspend fun unsubscribeAll(eventIds: List<Long>) {
        eventIds.distinct().forEach { unsubscribe(it) }
    }

    private fun messaging(): FirebaseMessaging = FirebaseMessaging.getInstance()

    private suspend fun await(task: Task<*>) {
        suspendCancellableCoroutine { continuation ->
            task.addOnCompleteListener { result ->
                if (!continuation.isActive) return@addOnCompleteListener
                if (result.isSuccessful) {
                    continuation.resume(Unit)
                } else {
                    continuation.resumeWithException(
                        result.exception ?: IllegalStateException("Firebase task failed"),
                    )
                }
            }
        }
    }

    companion object {
        private const val TAG = "PushTopicManager"

        fun topicName(eventId: Long): String = "macro_event_$eventId"
    }
}
