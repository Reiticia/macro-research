package com.macroresearch.ui.earnings

import com.macroresearch.data.BackendSettings
import com.macroresearch.data.DataSourceMode
import com.macroresearch.data.EarningsRepository
import com.macroresearch.data.model.EarningsQuote
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

internal data class EarningsQuoteState(val quote: EarningsQuote? = null, val loading: Boolean = false, val error: String? = null)

/** Polls only while its detail composition is STARTED. No persistent price cache on the client. */
internal class EarningsQuoteLoader(
    private val repository: EarningsRepository,
    private val symbol: String,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val mutable = MutableStateFlow(EarningsQuoteState())
    val state = mutable.asStateFlow()
    private val refresh = MutableStateFlow(0L)
    fun refresh() { refresh.value++ }

    suspend fun monitor() {
        var source: BackendSettings? = null
        combine(repository.settings, refresh) { settings, _ -> settings }.onEach { settings ->
            if (source != settings) { source = settings; mutable.value = EarningsQuoteState() }
        }.collectLatest { settings ->
            if (settings.mode != DataSourceMode.BACKEND) {
                mutable.value = EarningsQuoteState(error = "backend_required")
                return@collectLatest
            }
            while (true) {
                val started = monotonicMillis()
                mutable.value = mutable.value.copy(loading = true)
                try {
                    val quote = repository.quote(symbol)
                    currentCoroutineContext().ensureActive()
                    mutable.value = EarningsQuoteState(quote = quote)
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
                delay((5_000 - (monotonicMillis() - started)).coerceAtLeast(1))
            }
        }
    }
}
