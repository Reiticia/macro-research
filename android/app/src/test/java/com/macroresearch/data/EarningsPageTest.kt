package com.macroresearch.data

import com.macroresearch.data.model.EarningsEvent
import com.macroresearch.data.model.TradingViewForecast
import com.macroresearch.data.model.earningsPage
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class EarningsPageTest {
    private fun event(symbol: String = "AAPL", source: String = "nasdaq", ticker: String? = null) =
        EarningsEvent(symbol, "Company", source, tradingView = ticker?.let { TradingViewForecast(it) })

    @Test fun nasdaqRecordsOpenTheirOwnCompanyNotASupplementalNextQuarter() {
        val page = event(ticker = "NASDAQ:MSFT").earningsPage()!!
        assertEquals("Nasdaq", page.provider)
        assertEquals("https://www.nasdaq.com/market-activity/stocks/aapl/earnings", page.url)
        assertEquals(page, event().copy(actualEps = "1.23").earningsPage())
    }

    @Test fun tradingViewFallbackRequiresAnExactExchangeQualifiedTicker() {
        val page = event(source = "tradingview", ticker = "NASDAQ:AAPL").earningsPage()!!
        assertEquals("TradingView", page.provider)
        assertEquals("https://www.tradingview.com/symbols/NASDAQ-AAPL/financials-earnings/", page.url)
        assertNull(event(source = "tradingview").earningsPage())
        for (ticker in listOf("AAPL", "NASDAQ:MSFT", "NASDAQ:AAPL:EXTRA", "UNKNOWN:AAPL", ":AAPL")) {
            assertNull(ticker, event(source = "tradingview", ticker = ticker).earningsPage())
        }
    }

    @Test fun shareClassesAreKeptExactlyWithoutInventingAliases() {
        assertEquals("https://www.nasdaq.com/market-activity/stocks/brk.b/earnings", event("BRK.B").earningsPage()?.url)
        assertEquals("https://www.tradingview.com/symbols/NYSE-BRK.B/financials-earnings/",
            event("BRK.B", "tradingview", "NYSE:BRK.B").earningsPage()?.url)
        assertNull(event("BRK.B", "tradingview", "NYSE:BRK-B").earningsPage())
    }

    @Test fun rejectsPathsUrlsQueriesFragmentsWhitespaceAndUnknownSources() {
        for (symbol in listOf("", "aapl", " AAPL", "AAPL\n", "../AAPL", "AAPL/", "AAPL?x=y", "AAPL#x",
            "https://example.test", "AAPL%2f..", "AAPL@evil.test", "AAPL\\path", "ＡＡＰＬ")) {
            assertNull(symbol, event(symbol).earningsPage())
            assertNull(symbol, event(symbol, "tradingview", "NASDAQ:$symbol").earningsPage())
        }
        assertNull(event(source = "unknown", ticker = "NASDAQ:AAPL").earningsPage())
    }

    @Test fun linksAreFixedHttpsAndContainNoCredentialsQueryOrFragment() {
        for (event in listOf(event(), event(source = "tradingview", ticker = "NYSE:AAPL"))) {
            val url = event.earningsPage()!!.url.toHttpUrl()
            assertTrue(url.isHttps)
            assertTrue(url.host in setOf("www.nasdaq.com", "www.tradingview.com"))
            assertEquals("", url.username)
            assertEquals("", url.password)
            assertNull(url.query)
            assertNull(url.fragment)
        }
    }

    @Test fun nasdaqLowercasingIsIndependentOfDeviceLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("https://www.nasdaq.com/market-activity/stocks/ibm/earnings", event("IBM").earningsPage()?.url)
        } finally {
            Locale.setDefault(original)
        }
    }
}
