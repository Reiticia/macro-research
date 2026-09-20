package com.macroresearch.data.remote

import com.google.gson.Gson
import com.macroresearch.data.model.SocketEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Backend push channel.
 *
 * Connected only while the app is in the foreground: a persistent background socket would need a
 * foreground service on Android and is explicitly out of scope. Reconnects with exponential
 * backoff and rebuilds the URL/token on every attempt so a Settings change is picked up.
 */
class BackendSocket(
    private val client: OkHttpClient,
    private val gson: Gson,
    private val urlProvider: () -> String?,
    private val tokenProvider: () -> String?,
) {
    private val _events = MutableSharedFlow<SocketEvent>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<SocketEvent> = _events.asSharedFlow()

    private val _connected = MutableSharedFlow<Boolean>(extraBufferCapacity = 4)
    val connected: SharedFlow<Boolean> = _connected.asSharedFlow()

    private var webSocket: WebSocket? = null
    private var reconnectAttempt = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun connect() {
        if (webSocket != null) return
        val url = urlProvider() ?: return
        val token = tokenProvider() ?: return
        webSocket = client.newWebSocket(
            Request.Builder().url(url).header("authorization", "Bearer $token").build(),
            listener,
        )
    }

    fun close() {
        webSocket?.close(NORMAL_CLOSURE, "client paused")
        webSocket = null
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            reconnectAttempt = 0
            _connected.tryEmit(true)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            runCatching { gson.fromJson(text, SocketEvent::class.java) }
                .onSuccess(_events::tryEmit)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            this@BackendSocket.webSocket = null
            _connected.tryEmit(false)
        }

        override fun onFailure(webSocket: WebSocket, throwable: Throwable, response: Response?) {
            this@BackendSocket.webSocket = null
            _connected.tryEmit(false)
            // The token may have been rotated or the server restarted; retry with a bounded
            // backoff and a hard stop once the screen is gone.
            val delaySeconds = (1L shl reconnectAttempt.coerceAtMost(5))
            reconnectAttempt++
            scope.launch {
                delay(delaySeconds * 1_000)
                if (this@BackendSocket.webSocket == null) connect()
            }
        }
    }

    private companion object {
        const val NORMAL_CLOSURE = 1000
    }
}
