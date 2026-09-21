package com.macroresearch.data.remote

import com.google.gson.Gson
import com.macroresearch.data.model.SocketEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    @Volatile
    private var webSocket: WebSocket? = null
    @Volatile
    private var reconnectAttempt = 0
    @Volatile
    private var closed = true
    private var reconnectJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Synchronized
    fun connect() {
        closed = false
        if (webSocket != null) return
        reconnectJob?.cancel()
        reconnectJob = null
        val url = urlProvider() ?: return
        val token = tokenProvider() ?: return
        webSocket = client.newWebSocket(
            Request.Builder().url(url).header("authorization", "Bearer $token").build(),
            listener,
        )
    }

    @Synchronized
    fun close() {
        closed = true
        reconnectJob?.cancel()
        reconnectJob = null
        webSocket?.close(NORMAL_CLOSURE, "client paused")
        webSocket = null
        reconnectAttempt = 0
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val current = synchronized(this@BackendSocket) {
                !closed && this@BackendSocket.webSocket === webSocket
            }
            if (!current) return
            reconnectAttempt = 0
            _connected.tryEmit(true)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val current = synchronized(this@BackendSocket) {
                !closed && this@BackendSocket.webSocket === webSocket
            }
            if (!current) return
            runCatching { gson.fromJson(text, SocketEvent::class.java) }
                .onSuccess(_events::tryEmit)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            val current = synchronized(this@BackendSocket) {
                if (this@BackendSocket.webSocket !== webSocket) {
                    false
                } else {
                    this@BackendSocket.webSocket = null
                    true
                }
            }
            if (current) _connected.tryEmit(false)
        }

        override fun onFailure(webSocket: WebSocket, throwable: Throwable, response: Response?) {
            val (current, delaySeconds) = synchronized(this@BackendSocket) {
                if (this@BackendSocket.webSocket !== webSocket) {
                    false to null
                } else {
                    this@BackendSocket.webSocket = null
                    val delay = if (closed) {
                        null
                    } else {
                        val next = 1L shl reconnectAttempt.coerceAtMost(5)
                        reconnectAttempt++
                        next
                    }
                    true to delay
                }
            }
            if (!current) return
            _connected.tryEmit(false)
            if (delaySeconds == null) return
            // The token may have been rotated or the server restarted; retry with a bounded
            // backoff and a hard stop once the screen is gone.
            synchronized(this@BackendSocket) {
                if (closed) return
                reconnectJob = scope.launch {
                    delay(delaySeconds * 1_000)
                    synchronized(this@BackendSocket) {
                        reconnectJob = null
                        if (closed || this@BackendSocket.webSocket != null) return@launch
                    }
                    connect()
                }
            }
        }
    }

    private companion object {
        const val NORMAL_CLOSURE = 1000
    }
}
