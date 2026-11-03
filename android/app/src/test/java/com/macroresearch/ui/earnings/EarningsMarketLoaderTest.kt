package com.macroresearch.ui.earnings

import com.macroresearch.data.*
import com.macroresearch.data.model.EarningsStockCoverage
import com.macroresearch.data.remote.BackendException
import com.macroresearch.data.remote.BackendUnauthorizedException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class EarningsMarketLoaderTest {
    private val now = stockStart.plusSeconds(360)

    @Test fun pollsOnlyWhileVisibleAndReadsTheCorrectDateAndSymbol() = runTest {
        var calls = 0
        val repo = stockRepository { date, symbol ->
            assertEquals(stockDate, date); assertEquals("AAPL", symbol)
            calls++; stockMarket()
        }
        val loader = EarningsMarketLoader(repo, stockDate, "AAPL") { now }
        val job = backgroundScope.launch { loader.monitor() }
        runCurrent()
        assertEquals(1, calls)
        assertNotNull(loader.state.value.market)
        advanceTimeBy(15_000); runCurrent()
        assertEquals(2, calls)
        job.cancel()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(2, calls)
    }

    @Test fun manualRefreshIsReadOnlyAndCancelsTheOldWait() = runTest {
        var calls = 0
        val loader = EarningsMarketLoader(stockRepository { _, _ -> calls++; stockMarket() }, stockDate, "AAPL") { now }
        backgroundScope.launch { loader.monitor() }
        runCurrent()
        loader.refresh(); runCurrent()
        assertEquals(2, calls)
        assertFalse(loader.state.value.loading)
    }

    @Test fun terminalWindowStopsPollingEvenWithMissingSamples() = runTest {
        var calls = 0
        val loader = EarningsMarketLoader(stockRepository { _, _ -> calls++; stockMarket(ended = true) }, stockDate, "AAPL") { now }
        backgroundScope.launch { loader.monitor() }
        runCurrent(); advanceTimeBy(60_000); runCurrent()
        assertEquals(1, calls)
        assertEquals(57, loader.state.value.market?.coverage?.missingMinuteCount)
    }

    @Test fun transientFailureKeepsClearlyMarkedPreviouslyLoadedSamples() = runTest {
        var calls = 0
        val loader = EarningsMarketLoader(stockRepository { _, _ ->
            if (++calls > 1) throw IOException("offline") else stockMarket()
        }, stockDate, "AAPL") { now }
        backgroundScope.launch { loader.monitor() }
        runCurrent(); advanceTimeBy(15_000); runCurrent()
        assertNotNull(loader.state.value.market)
        assertEquals("unavailable", loader.state.value.error)
        assertFalse(loader.state.value.loading)
    }

    @Test fun directModeNeverCallsTheBackendOrSynthesizesHistory() = runTest {
        val settings = MutableStateFlow(BackendSettings())
        var calls = 0
        val loader = EarningsMarketLoader(stockRepository(settings) { _, _ -> calls++; stockMarket() }, stockDate, "AAPL") { now }
        backgroundScope.launch { loader.monitor() }
        runCurrent()
        assertEquals("backend_required", loader.state.value.error)
        assertNull(loader.state.value.market)
        assertEquals(0, calls)
    }

    @Test fun switchingBackendClearsOldDataAndRejectsLateResults() = runTest {
        val settings = MutableStateFlow(BackendSettings(mode = DataSourceMode.BACKEND, baseUrl = "https://one.example"))
        val blocked = CompletableDeferred<Unit>()
        var calls = 0
        val repo = stockRepository(settings) { _, _ ->
            if (settings.value.baseUrl == "https://one.example") {
                calls++
                if (calls > 1) withContext(NonCancellable) { blocked.await() }
                stockMarket()
            } else stockMarket().copy(snapshots = emptyList(), coverage = EarningsStockCoverage(0, 6, 6, false))
        }
        val loader = EarningsMarketLoader(repo, stockDate, "AAPL") { now }
        backgroundScope.launch { loader.monitor() }
        runCurrent()
        assertEquals(3, loader.state.value.market?.snapshots?.size)
        advanceTimeBy(15_000); runCurrent()
        settings.value = settings.value.copy(baseUrl = "https://two.example")
        runCurrent()
        assertNull(loader.state.value.market)
        blocked.complete(Unit); runCurrent()
        assertEquals(0, loader.state.value.market?.snapshots?.size)
    }

    @Test fun switchingToDirectClearsPreviousBackendSamples() = runTest {
        val settings = MutableStateFlow(BackendSettings(mode = DataSourceMode.BACKEND))
        val loader = EarningsMarketLoader(stockRepository(settings) { _, _ -> stockMarket() }, stockDate, "AAPL") { now }
        backgroundScope.launch { loader.monitor() }
        runCurrent()
        settings.value = settings.value.copy(mode = DataSourceMode.DIRECT)
        runCurrent()
        assertNull(loader.state.value.market)
        assertEquals("backend_required", loader.state.value.error)
    }

    @Test fun unsupportedAndUnauthorizedDoNotStartAnAutomaticRetryStorm() = runTest {
        for ((error, reason) in listOf(BackendException(404, null, "not found") to "unsupported",
            BackendUnauthorizedException("invalid key") to "unauthorized",
            BackendException(429, null, "slow down") to "rate_limited")) {
            var calls = 0
            val loader = EarningsMarketLoader(stockRepository { _, _ -> calls++; throw error }, stockDate, "AAPL") { now }
            val job = backgroundScope.launch { loader.monitor() }
            runCurrent(); advanceTimeBy(60_000); runCurrent()
            assertEquals(reason, loader.state.value.error)
            assertEquals(1, calls)
            job.cancel()
        }
    }

    @Test fun pendingTodayPollsButOldOrAlreadyReportedDatesDoNot() {
        val pending = stockMarket().copy(tracking = null, snapshots = emptyList(), observationStatus = "watching",
            coverage = EarningsStockCoverage(0, 0, 0, false))
        assertTrue(shouldPollEarningsMarket(pending, stockDate, now))
        assertTrue(shouldPollEarningsMarket(pending.copy(observationStatus = "not_observed"), stockDate, now))
        assertFalse(shouldPollEarningsMarket(pending, stockDate.minusDays(2), now))
        assertFalse(shouldPollEarningsMarket(pending, stockDate.plusDays(1), now))
        for (status in listOf("already_reported", "missed_after_gap", "quarter_changed")) {
            assertFalse(shouldPollEarningsMarket(pending.copy(observationStatus = status), stockDate, now))
        }
    }
}
