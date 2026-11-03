package com.macroresearch.ui.earnings

import com.macroresearch.data.BackendSettings
import com.macroresearch.data.DataSourceMode
import com.macroresearch.data.EarningsRepository
import com.macroresearch.data.model.EARNINGS_ZONE
import com.macroresearch.data.model.EarningsMarket
import com.macroresearch.data.remote.BackendException
import com.macroresearch.data.remote.BackendUnauthorizedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import java.time.Instant
import java.time.LocalDate

internal data class EarningsMarketState(
    val market: EarningsMarket? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

/** Owned by the visible detail composition, not a background ViewModel poller. */
internal class EarningsMarketLoader(
    private val repository: EarningsRepository,
    private val date: LocalDate,
    private val symbol: String,
    private val clock: () -> Instant = Instant::now,
) {
    private val mutable = MutableStateFlow(EarningsMarketState())
    val state = mutable.asStateFlow()
    private val refresh = MutableStateFlow(0L)
    fun refresh() { refresh.value++ }

    suspend fun monitor() {
        var source: BackendSettings? = null
        combine(repository.settings, refresh) { settings, _ -> settings }.onEach { settings ->
            // Clear before collectLatest waits for a cancelled, possibly uncooperative old call.
            if (source != settings) {
                source = settings
                mutable.value = EarningsMarketState()
            }
        }.collectLatest { settings ->
            if (settings.mode != DataSourceMode.BACKEND) {
                mutable.value = EarningsMarketState(error = "backend_required")
                return@collectLatest
            }
            while (true) {
                mutable.value = mutable.value.copy(loading = true, error = null)
                try {
                    val result = repository.market(date, symbol)
                    currentCoroutineContext().ensureActive()
                    mutable.value = EarningsMarketState(market = result)
                    if (!shouldPollEarningsMarket(result, date, clock())) break
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    currentCoroutineContext().ensureActive()
                    val reason = when {
                        error is BackendUnauthorizedException -> "unauthorized"
                        error is BackendException && error.statusCode == 429 -> "rate_limited"
                        error is UnsupportedOperationException || (error is BackendException && error.statusCode in setOf(404, 501)) -> "unsupported"
                        else -> "unavailable"
                    }
                    mutable.value = mutable.value.copy(loading = false, error = reason)
                    if (reason != "unavailable") break
                }
                delay(15_000)
            }
        }
    }
}

internal fun shouldPollEarningsMarket(market: EarningsMarket, date: LocalDate, now: Instant): Boolean {
    val opening = market.openingSession
    // Keep observing an upcoming opening even when the original release window has ended.
    if (opening != null && !opening.coverage.windowEnded && opening.tracking.status != "completed") return true
    if (market.tracking != null) return !market.coverage.windowEnded && market.tracking.status != "completed"
    val today = now.atZone(EARNINGS_ZONE).toLocalDate()
    return date in today.minusDays(1)..today && market.observationStatus in setOf("not_observed", "watching")
}
