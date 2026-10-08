package com.macroresearch.data.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class CancellableHttpTest {
    @Test fun cancellationClosesAnInFlightCallIncludingASlowResponseBody() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("slow response body").throttleBody(1, 250, TimeUnit.MILLISECONDS))
        server.start()
        try {
            val call = OkHttpClient().newCall(Request.Builder().url(server.url("/")).build())
            val reading = CompletableDeferred<Unit>()
            val job = launch(Dispatchers.IO) {
                call.awaitResponse { response -> reading.complete(Unit); response.body!!.string() }
            }
            reading.await()
            val start = System.nanoTime()
            job.cancelAndJoin()
            assertTrue(call.isCanceled())
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2_000)
        } finally { server.shutdown() }
    }
}
