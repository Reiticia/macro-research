package com.macroresearch.ui.earnings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macroresearch.data.EarningsRepository
import com.macroresearch.data.model.EARNINGS_ZONE
import com.macroresearch.data.model.EarningsCalendar
import com.macroresearch.data.remote.BackendException
import com.macroresearch.data.remote.BackendUnauthorizedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

data class EarningsState(
    val date: LocalDate = LocalDate.now(EARNINGS_ZONE),
    val calendar: EarningsCalendar? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

class EarningsViewModel(private val repository: EarningsRepository) : ViewModel() {
    private val mutable = MutableStateFlow(EarningsState())
    val state = mutable.asStateFlow()
    private var request: Job? = null

    init {
        viewModelScope.launch { repository.settings.collect { refresh(reset = true) } }
    }

    fun selectDate(date: LocalDate) {
        if (date == mutable.value.date) return
        mutable.value = EarningsState(date = date)
        refresh()
    }

    fun refresh(force: Boolean = false, reset: Boolean = false) {
        request?.cancel()
        val date = mutable.value.date
        mutable.value = mutable.value.copy(calendar = if (reset) null else mutable.value.calendar, loading = true, error = null)
        request = viewModelScope.launch {
            try {
                val cache = repository.cached(date)
                mutable.value = mutable.value.copy(calendar = cache)
                val result = repository.load(date, force)
                mutable.value = EarningsState(date, result)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                val reason = when {
                    error is BackendUnauthorizedException -> "unauthorized"
                    error is BackendException && (error.statusCode == 404 || error.statusCode == 501) -> "unsupported"
                    else -> "unavailable"
                }
                mutable.value = mutable.value.copy(loading = false, error = reason)
            }
        }
    }
}
