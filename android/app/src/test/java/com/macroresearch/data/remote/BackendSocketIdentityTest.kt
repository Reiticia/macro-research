package com.macroresearch.data.remote

import com.google.gson.Gson
import com.macroresearch.data.BackendDeviceIdentity
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class BackendSocketIdentityTest {
    @Test fun websocketHandshakeCarriesTheSameBindingHeaders() {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }
        }))
        val identity = BackendDeviceIdentity("11111111-1111-4111-8111-111111111111", "a".repeat(64))
        val socket = BackendSocket(OkHttpClient(), Gson(), { server.url("/api/v1/ws").toString() }, { "test-key" }, { identity })
        try {
            socket.connect()
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("Bearer test-key", request.getHeader("authorization"))
            assertEquals(identity.installationId, request.getHeader("X-Installation-Id"))
            assertEquals(identity.androidIdHash, request.getHeader("X-Android-Id-Hash"))
        } finally {
            socket.close()
            server.shutdown()
        }
    }
}
