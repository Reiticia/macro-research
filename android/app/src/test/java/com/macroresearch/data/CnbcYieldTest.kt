package com.macroresearch.data

import com.google.gson.Gson
import com.macroresearch.data.remote.DirectMarketClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Treasury yields used to come from Yahoo only, which is rate limited or unreachable on many
 * networks, so US2Y/US10Y stayed empty. CNBC now supplies both the quote and the one-minute bars.
 */
class CnbcYieldTest {
    private val client = DirectMarketClient(OkHttpClient())

    @Test
    fun quoteIsParsedWithADerivedDailyChange() {
        val quote = client.parseCnbcQuote("us2y", QUOTE)

        assertEquals("us2y", quote.symbol)
        assertEquals("cnbc", quote.provider)
        assertEquals(4.606, quote.price, 1e-9)
        // CNBC's change_pct contradicts its own change field, so the move is derived from the
        // previous session close: (4.606 - 4.55) / 4.55 * 100.
        assertEquals(1.2308, quote.changePercent!!, 1e-3)
        assertEquals("2026-09-11T15:17:11Z", quote.timestamp)
        assertEquals(4.61, quote.high!!, 1e-9)
        assertEquals(4.54, quote.low!!, 1e-9)
        assertEquals("reg_mkt", quote.marketState)
    }

    @Test
    fun quoteFallsBackToTheOpenAndRejectsUnusablePayloads() {
        val quote = client.parseCnbcQuote(
            "us10y",
            """{"FormattedQuoteResult":{"FormattedQuote":[{"symbol":"US10Y","last":"4.938%","open":"4.967%"}]}}""",
        )
        assertEquals(4.938, quote.price, 1e-9)
        assertTrue(quote.changePercent!! < 0)

        assertTrue(runCatching { client.parseCnbcQuote("us10y", """{"FormattedQuoteResult":{"FormattedQuote":[]}}""") }.isFailure)
        assertTrue(
            runCatching {
                client.parseCnbcQuote("us10y", """{"FormattedQuoteResult":{"FormattedQuote":[{"last":"--"}]}}""")
            }.isFailure,
        )
    }

    @Test
    fun treasuryCsvProvidesAStaleDailyFallbackAndChange() {
        val quote = client.parseTreasuryQuote(
            "us2y",
            """Date,"2 Yr","10 Yr"
09/18/2026,4.76,5.01
09/17/2026,4.67,4.94
""",
            "2 Yr",
        )
        assertEquals("us2y", quote.symbol)
        assertEquals("treasury", quote.provider)
        assertEquals(4.76, quote.price, 1e-9)
        assertEquals((4.76 - 4.67) / 4.67 * 100.0, quote.changePercent!!, 1e-9)
        assertTrue(quote.stale)
        assertEquals("closed", quote.marketState)
    }

    @Test
    fun oneMinuteBarsFeedTheBasisPointReactionWindows() {
        val eventTime = Instant.now().minusSeconds(7_200).truncatedTo(ChronoUnit.SECONDS)
        val bars = client.parseCnbcCandles("us10y", chart(barsAround(eventTime)), eventTime.plusSeconds(3_600))

        // Baseline is the last bar strictly before the release; yields move in basis points.
        val reaction = client.reaction(7, "us10y", eventTime, bars)!!
        assertEquals(4.90, reaction.baselinePrice, 1e-9)
        assertEquals("basis_points", reaction.reactionUnit)
        assertEquals(2.0, reaction.change1m!!, 1e-9)
        assertEquals(10.0, reaction.change5m!!, 1e-9)
        assertEquals(30.0, reaction.change30m!!, 1e-9)
        assertEquals(60.0, reaction.change60m!!, 1e-9)
    }

    @Test
    fun barsAfterTheObservationWindowAndBrokenRowsAreIgnored() {
        val eventTime = Instant.now().minusSeconds(7_200).truncatedTo(ChronoUnit.SECONDS)
        val json = """
            {"barData":{"priceBars":[
              {"close":"4.90","tradeTimeinMills":${eventTime.minusSeconds(60).toEpochMilli()}},
              {"close":"4.91","tradeTimeinMills":${eventTime.toEpochMilli()}},
              {"close":"4.92","tradeTimeinMills":${eventTime.plusSeconds(60).toEpochMilli()}},
              {"close":"","tradeTimeinMills":${eventTime.plusSeconds(120).toEpochMilli()}},
              {"close":"9.99","tradeTimeinMills":${eventTime.plusSeconds(9_000).toEpochMilli()}}
            ]}}
        """.trimIndent()

        val bars = client.parseCnbcCandles("us2y", json, eventTime.plusSeconds(120))

        assertEquals(listOf(4.90, 4.91, 4.92), bars.map { it.close })
        assertEquals("us2y", bars.first().symbol)
    }

    private fun barsAround(eventTime: Instant): List<Pair<Instant, Double>> = buildList {
        // Two quiet bars before the release, then a jump and a drift.
        add(eventTime.minusSeconds(120) to 4.89)
        add(eventTime.minusSeconds(60) to 4.90)
        add(eventTime to 4.92)
        add(eventTime.plusSeconds(60) to 4.92)
        add(eventTime.plusSeconds(5 * 60) to 5.00)
        add(eventTime.plusSeconds(30 * 60) to 5.20)
        add(eventTime.plusSeconds(60 * 60) to 5.50)
    }

    private fun chart(bars: List<Pair<Instant, Double>>): String = Gson().toJson(
        mapOf(
            "barData" to mapOf(
                "priceBars" to bars.map { (time, close) ->
                    mapOf(
                        "open" to "$close", "high" to "$close", "low" to "$close",
                        "close" to "$close", "volume" to 0, "tradeTimeinMills" to time.toEpochMilli(),
                    )
                },
            ),
        ),
    )

    @Test
    fun chartUrlUsesTheSymbolQueryParameterCnbcExpects() {
        // Regression: "charts/US10Y/1D.json" answers HTTP 400; the symbol belongs in the query.
        val client = DirectMarketClient(OkHttpClient(), cnbcChartBase = "http://cnbc.invalid/charts")
        assertEquals(
            "http://cnbc.invalid/charts/1D.json?symbol=US10Y&interval=1&requestMethod=itv&events=1",
            client.cnbcChartUrl("US10Y"),
        )
    }

    @Test
    fun directQuotesUseTheFiveSecondMemoryCache() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(QUOTE))
            server.start()
            val cached = DirectMarketClient(
                OkHttpClient(),
                cnbcQuoteBase = server.url("/quote").toString().trimEnd('/'),
            )

            assertEquals(4.606, cached.quotes(listOf("us2y")).quotes.single().price, 1e-9)
            assertEquals(4.606, cached.quotes(listOf("us2y")).quotes.single().price, 1e-9)
            assertEquals(1, server.requestCount)
            assertEquals("https://www.cnbc.com/", server.takeRequest().getHeader("Referer"))
        }
    }

    @Test
    fun treasuryRequestsGoThroughTheConfiguredProxy() = runBlocking {
        // Yahoo and CNBC are TLS-blocked on some networks, so both honour the user's proxy
        // (Yahoo shares the same helper), while the crypto/quote providers stay direct.
        MockWebServer().use { proxy ->
            proxy.enqueue(MockResponse().setBody(QUOTE))
            proxy.enqueue(MockResponse().setBody(QUOTE))
            proxy.start()
            val proxied = DirectMarketClient(
                OkHttpClient(),
                proxy = { Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxy.port)) },
                cnbcQuoteBase = "http://cnbc.invalid/quote",
            )

            val quotes = proxied.quotes(listOf("us2y", "us10y")).quotes

            assertEquals(listOf("us2y", "us10y"), quotes.map { it.symbol })
            assertEquals(listOf("cnbc", "cnbc"), quotes.map { it.provider })
            // An HTTP proxy receives the absolute request line, which proves the route is used.
            val requests = listOf(proxy.takeRequest(), proxy.takeRequest())
            requests.forEach { request ->
                assertTrue(request.requestLine.startsWith("GET http://cnbc.invalid/quote?symbols="))
                assertEquals("https://www.cnbc.com/", request.getHeader("Referer"))
            }
        }
    }

    private val QUOTE = """
        {"FormattedQuoteResult":{"FormattedQuote":[{"symbol":"US2Y","last":"4.606%","change":"+0.056",
         "change_pct":"-0.1055%","open":"4.589%","high":"4.610%","low":"4.540%","previous_day_closing":"4.55%","last_timedate":"11:17 AM EDT",
         "last_time":"2026-09-11T11:17:11.000-0400","curmktstatus":"REG_MKT"}]}}
    """.trimIndent()
}
