package com.macroresearch.data.remote

import com.google.gson.Gson
import com.macroresearch.data.currentStockQuote
import com.macroresearch.data.model.decodeEarningsQuote
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class EarningsQuoteClientTest {
    @Test fun currentQuoteUsesProtectedEndpointAndDisablesHttpCaching() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val client = BackendClient(OkHttpClient(), Gson(), { server.url("/").toString().trimEnd('/') }, { "fixture-key" })
            server.enqueue(MockResponse().setBody(Gson().toJson(currentStockQuote())))
            assertEquals(currentStockQuote(), client.earningsQuote("AAPL"))
            val request = server.takeRequest()
            assertEquals("/api/v1/earnings/quote/AAPL", request.path)
            assertEquals("Bearer fixture-key", request.getHeader("Authorization"))
            assertTrue(request.getHeader("Cache-Control").orEmpty().contains("no-store"))
            assertTrue(request.getHeader("Cache-Control").orEmpty().contains("no-cache"))
        }
    }

    @Test fun parserRejectsWrongIdentityBadPricesFutureTimesAndUnsupportedSources() {
        val gson = Gson()
        for (quote in listOf(currentStockQuote().copy(symbol = "MSFT"), currentStockQuote().copy(price = "NaN"),
            currentStockQuote().copy(price = "0"), currentStockQuote().copy(timestamp = "2027-01-01T00:00:00Z"),
            currentStockQuote().copy(provider = "unknown"), currentStockQuote().copy(currency = "not-a-currency"))) {
            try { decodeEarningsQuote(gson.toJson(quote), "AAPL"); fail("Invalid quote accepted") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals(currentStockQuote(), decodeEarningsQuote(gson.toJson(currentStockQuote()), "AAPL"))
    }
}
