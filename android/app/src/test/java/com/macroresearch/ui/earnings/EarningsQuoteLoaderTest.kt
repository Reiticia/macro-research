package com.macroresearch.ui.earnings

import com.macroresearch.data.*
import com.macroresearch.data.remote.BackendException
import com.macroresearch.data.remote.BackendUnauthorizedException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class EarningsQuoteLoaderTest {
    @Test fun fetchesImmediatelyThenEveryFiveSecondsAndStopsWhenCancelled() = runTest {
        var calls = 0
        val repo = quoteRepository { symbol -> assertEquals("AAPL", symbol); calls++; currentStockQuote() }
        val loader = EarningsQuoteLoader(repo, "AAPL") { testScheduler.currentTime }
        val job = backgroundScope.launch { loader.monitor() }
        runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(4_999); runCurrent(); assertEquals(1, calls)
        advanceTimeBy(1); runCurrent(); assertEquals(2, calls)
        advanceTimeBy(5_000); runCurrent(); assertEquals(3, calls)
        job.cancel(); advanceTimeBy(60_000); runCurrent(); assertEquals(3, calls)
        assertEquals(currentStockQuote(), loader.state.value.quote)
    }

    @Test fun slowFetchDoesNotOverlapOrAddAnExtraFiveSecondsToEachRefresh() = runTest {
        var calls = 0
        val loader = EarningsQuoteLoader(quoteRepository { calls++; delay(2_000); currentStockQuote() }, "AAPL") { testScheduler.currentTime }
        backgroundScope.launch { loader.monitor() }
        runCurrent(); advanceTimeBy(2_000); runCurrent()
        assertEquals(1, calls)
        assertNotNull(loader.state.value.quote)
        advanceTimeBy(3_000); runCurrent(); assertEquals(2, calls)
    }

    @Test fun failureRetainsLastPriceWithAnErrorAndCanRecover() = runTest {
        var calls = 0
        val loader = EarningsQuoteLoader(quoteRepository {
            if (++calls == 2) throw IOException("offline") else currentStockQuote()
        }, "AAPL") { testScheduler.currentTime }
        backgroundScope.launch { loader.monitor() }
        runCurrent(); advanceTimeBy(5_000); runCurrent()
        assertEquals("unavailable", loader.state.value.error)
        assertNotNull(loader.state.value.quote)
        advanceTimeBy(5_000); runCurrent()
        assertNull(loader.state.value.error)
        assertEquals(3, calls)
    }

    @Test fun directModeNeverCallsBackendAndSourceSwitchDiscardsLateResponses() = runTest {
        val settings = MutableStateFlow(BackendSettings())
        val blocked = CompletableDeferred<Unit>()
        var calls = 0
        val loader = EarningsQuoteLoader(quoteRepository(settings) {
            calls++
            withContext(NonCancellable) { blocked.await() }
            currentStockQuote()
        }, "AAPL") { testScheduler.currentTime }
        backgroundScope.launch { loader.monitor() }
        runCurrent()
        assertEquals(0, calls)
        assertEquals("backend_required", loader.state.value.error)
        settings.value = settings.value.copy(mode = DataSourceMode.BACKEND)
        runCurrent(); assertEquals(1, calls)
        settings.value = settings.value.copy(mode = DataSourceMode.DIRECT)
        runCurrent(); assertNull(loader.state.value.quote)
        blocked.complete(Unit); runCurrent()
        assertNull(loader.state.value.quote)
        assertEquals("backend_required", loader.state.value.error)
    }

    @Test fun authUnsupportedAndRateLimitsStopAutoRetriesButManualRefreshRemainsAvailable() = runTest {
        for ((error, reason) in listOf(
            BackendUnauthorizedException("denied") to "unauthorized",
            BackendException(404, "not_found", "old backend") to "unsupported",
            BackendException(501, "unsupported", "old backend") to "unsupported",
            BackendException(429, "quota_exceeded", "wait") to "rate_limited")) {
            var calls = 0
            val loader = EarningsQuoteLoader(quoteRepository { calls++; throw error }, "AAPL") { testScheduler.currentTime }
            val job = backgroundScope.launch { loader.monitor() }
            runCurrent(); advanceTimeBy(60_000); runCurrent()
            assertEquals(1, calls)
            assertEquals(reason, loader.state.value.error)
            loader.refresh(); runCurrent(); assertEquals(2, calls)
            job.cancel()
        }
    }
}
