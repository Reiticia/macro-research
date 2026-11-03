package com.macroresearch.data.remote

import com.google.gson.Gson
import com.macroresearch.data.stockDate
import com.macroresearch.data.stockMarket
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class EarningsMarketClientTest {
    @Test fun exactIdentityAuthenticatedAndNeverServedFromHttpCache() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val backend = BackendClient(OkHttpClient(), Gson(), { server.url("/").toString().trimEnd('/') }, { "fixture-key" })
            server.enqueue(MockResponse().setBody(Gson().toJson(stockMarket())))
            val market = backend.earningsMarket(stockDate, "AAPL")
            assertEquals(3, market.snapshots.size)
            val request = server.takeRequest()
            assertEquals("/api/v1/earnings/market/2026-11-03/AAPL", request.path)
            assertEquals("GET", request.method)
            assertEquals("Bearer fixture-key", request.getHeader("Authorization"))
            assertTrue(request.getHeader("Cache-Control").orEmpty().contains("no-cache"))
            assertTrue(request.getHeader("Cache-Control").orEmpty().contains("no-store"))
        }
    }

    @Test fun anOldBackendIsUnsupportedNotAnEmptySuccessfulWindow() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val backend = BackendClient(OkHttpClient(), Gson(), { server.url("/").toString().trimEnd('/') }, { "fixture-key" })
            server.enqueue(MockResponse().setResponseCode(404).setBody("{}"))
            try {
                backend.earningsMarket(stockDate, "AAPL")
                fail("404 must not become an empty success")
            } catch (error: BackendException) { assertEquals(404, error.statusCode) }
        }
    }

    @Test fun neverAcceptsAnotherStocksResponse() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val backend = BackendClient(OkHttpClient(), Gson(), { server.url("/").toString().trimEnd('/') }, { "fixture-key" })
            server.enqueue(MockResponse().setBody(Gson().toJson(stockMarket().copy(symbol = "NVDA"))))
            try {
                backend.earningsMarket(stockDate, "AAPL")
                fail("Wrong stock identity")
            } catch (_: IllegalArgumentException) { }
        }
    }
}
