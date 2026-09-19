package com.macroresearch.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.macroresearch.data.MacroRepository
import com.macroresearch.data.model.AnalysisReport
import com.macroresearch.data.model.AiAnalysis
import com.macroresearch.data.model.EconomicEvent
import com.macroresearch.data.model.EventDetailResponse
import com.macroresearch.data.model.MarketResponse
import com.macroresearch.data.model.MarketQuotesResponse
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class LoadState<T>(
    val value: T? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

class HomeViewModel(private val repository: MacroRepository) : ViewModel() {
    val events: StateFlow<List<EconomicEvent>> = combine(
        repository.observeUpcoming(),
        repository.selectedCountries,
    ) { events, countries ->
        events.filter { it.country in countries }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _refresh = MutableStateFlow(LoadState<Unit>())
    val refresh = _refresh.asStateFlow()
    private val _liveMarket = MutableStateFlow<MarketQuotesResponse?>(null)
    val liveMarket = _liveMarket.asStateFlow()
    private var liveMarketRequest: Job? = null

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        _refresh.value = LoadState(loading = true)
        runCatching { repository.refreshUpcoming() }
            .onSuccess { _refresh.value = LoadState(Unit, loading = false) }
            .onFailure { _refresh.value = LoadState(loading = false, error = it.message) }
    }

    fun loadLiveMarket(symbols: List<String>) {
        if (symbols.isEmpty() || liveMarketRequest?.isActive == true) return
        liveMarketRequest = viewModelScope.launch {
            runCatching { repository.marketQuotes(symbols) }
                .onSuccess { _liveMarket.value = it }
        }
    }
}

class MarketViewModel(private val repository: MacroRepository) : ViewModel() {
    private val symbols = listOf(
        "nasdaq100", "sp500", "gold", "silver", "dxy",
        "eur_usd", "us2y", "us10y", "bitcoin", "ethereum",
    )
    private val _state = MutableStateFlow(LoadState<MarketQuotesResponse>())
    val state = _state.asStateFlow()
    private var request: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        if (request?.isActive == true) return
        request = viewModelScope.launch {
            val previous = _state.value.value
            _state.value = LoadState(value = previous, loading = true)
            runCatching { repository.marketQuotes(symbols) }
                .onSuccess { _state.value = LoadState(value = it, loading = false) }
                .onFailure {
                    _state.value = LoadState(
                        value = previous,
                        loading = false,
                        error = it.message,
                    )
                }
        }
    }
}

data class CalendarState(
    val date: LocalDate = LocalDate.now(),
    val events: List<EconomicEvent> = emptyList(),
    val importance: Set<Int> = setOf(2, 3),
    val countries: Set<String> = emptySet(),
    val loading: Boolean = true,
    val error: String? = null,
) {
    val filtered: List<EconomicEvent> get() = events.filter {
        it.importance in importance && it.country in countries
    }
}

class CalendarViewModel(private val repository: MacroRepository) : ViewModel() {
    private val _state = MutableStateFlow(
        CalendarState(countries = repository.selectedCountries.value),
    )
    val state = _state.asStateFlow()
    private var request: Job? = null

    init {
        selectDate(LocalDate.now())
        viewModelScope.launch {
            repository.selectedCountries.collect { countries ->
                _state.value = _state.value.copy(countries = countries)
            }
        }
        viewModelScope.launch {
            repository.translationsUpdated.collect { refreshTranslations() }
        }
    }

    fun selectDate(date: LocalDate) {
        // Cancel the previous fetch: a slow earlier response must never overwrite the day the
        // user just picked, and the list must not keep showing another day's events meanwhile.
        request?.cancel()
        _state.value = _state.value.copy(date = date, events = emptyList(), loading = true, error = null)
        request = viewModelScope.launch {
            try {
                val events = repository.calendar(date)
                if (_state.value.date == date) {
                    _state.value = _state.value.copy(events = events, loading = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (_state.value.date == date) {
                    _state.value = _state.value.copy(loading = false, error = error.message)
                }
            }
        }
    }

    fun applyFilters(importance: Set<Int>, countries: Set<String>) {
        _state.value = _state.value.copy(importance = importance, countries = countries)
        repository.setCountries(countries)
    }

    /** Re-reads name-keyed translations after the user corrects one on the detail screen. */
    fun refreshTranslations() {
        val current = _state.value.events
        if (current.isEmpty()) return
        viewModelScope.launch {
            val updates = runCatching {
                repository.cachedTranslations(current.map(EconomicEvent::event))
            }.getOrNull().orEmpty()
            if (updates.isEmpty()) return@launch
            val refreshed = current.map { it.withTranslation(updates) }
            if (refreshed != current) _state.value = _state.value.copy(events = refreshed)
        }
    }
}

/** Outcome of a manual published-value retry on the event detail screen. */
enum class ReleaseFetchOutcome { RETRIEVED, STILL_MISSING }

data class EventDetailState(
    val detail: EventDetailResponse? = null,
    val market: MarketResponse? = null,
    val followed: Boolean = false,
    val loading: Boolean = true,
    val error: String? = null,
    val releaseFetching: Boolean = false,
    val releaseOutcome: ReleaseFetchOutcome? = null,
    val releaseError: String? = null,
)

class EventDetailViewModel(
    private val id: Long,
    private val repository: MacroRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(EventDetailState())
    val state = _state.asStateFlow()
    private var releaseRequest: Job? = null

    init {
        refresh()
        viewModelScope.launch {
            repository.observeFollowed(id).collect { followed ->
                _state.value = _state.value.copy(followed = followed)
            }
        }
        viewModelScope.launch {
            // Event-name enrichment is asynchronous. Keep the loaded detail/market state and
            // replace only its event when Room receives the Chinese names.
            repository.observeEvent(id).collect { event ->
                val detail = _state.value.detail ?: return@collect
                if (event != null && event != detail.event) {
                    _state.value = _state.value.copy(detail = detail.copy(event = event))
                }
            }
        }
    }

    fun refresh() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = null)
        runCatching {
            val detail = async { repository.event(id) }
            val market = async { runCatching { repository.market(id) }.getOrNull() }
            detail.await() to market.await()
        }.onSuccess { (detail, market) ->
            _state.value = _state.value.copy(detail = detail, market = market, loading = false)
        }.onFailure {
            _state.value = _state.value.copy(loading = false, error = it.message)
        }
    }

    /** Retries the published value for this event only; the market snapshot is left untouched. */
    fun fetchRelease() {
        if (releaseRequest?.isActive == true) return
        _state.value = _state.value.copy(releaseFetching = true, releaseOutcome = null, releaseError = null)
        releaseRequest = viewModelScope.launch {
            try {
                val detail = repository.refreshEventRelease(id)
                _state.value = _state.value.copy(
                    detail = detail,
                    releaseFetching = false,
                    releaseOutcome = if (detail.event.actual.isNullOrBlank()) ReleaseFetchOutcome.STILL_MISSING
                    else ReleaseFetchOutcome.RETRIEVED,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = _state.value.copy(releaseFetching = false, releaseError = error.message)
            }
        }
    }

    fun toggleFollowed() = viewModelScope.launch {
        repository.setFollowed(id, !_state.value.followed)
    }
}

data class AnalysisState(
    val event: EconomicEvent? = null,
    val report: AnalysisReport? = null,
    val market: MarketResponse? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

/** Cached AI briefing plus its in-flight generation state. */
data class AiAnalysisState(
    val analysis: AiAnalysis? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val feedbackSent: Boolean = false,
)

class AnalysisViewModel(
    private val id: Long,
    private val repository: MacroRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(AnalysisState())
    val state = _state.asStateFlow()
    private val _ai = MutableStateFlow(AiAnalysisState())
    val ai = _ai.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            // Analysis may open while the title translation is still being fetched.
            repository.observeEvent(id).collect { event ->
                if (event != null && event != _state.value.event) {
                    _state.value = _state.value.copy(event = event)
                }
            }
        }
    }

    fun refreshSharedAi(language: String) = viewModelScope.launch { loadAi(language) }

    suspend fun loadAi(language: String) {
        _ai.value = AiAnalysisState(loading = true)
        try {
            val result = if (repository.translationSettings.value.configured) {
                repository.aiAnalysis(id)
            } else {
                repository.sharedAiAnalysis(id, language)
            }
            _ai.value = AiAnalysisState(analysis = result)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _ai.value = AiAnalysisState(error = error.message)
        }
    }

    fun feedback(language: String, message: String) = viewModelScope.launch {
        val result = _ai.value.analysis ?: return@launch
        _ai.value = _ai.value.copy(error = null)
        try {
            repository.submitAnalysisFeedback(language, result, message)
            _ai.value = _ai.value.copy(feedbackSent = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _ai.value = _ai.value.copy(error = error.message)
        }
    }

    /** Runs or re-runs the AI briefing on the user's own endpoint and stores it locally. */
    fun generateAiAnalysis(languageTag: String) = viewModelScope.launch {
        _ai.value = _ai.value.copy(loading = true, error = null)
        runCatching { repository.generateAiAnalysis(id, languageTag) }
            .onSuccess { _ai.value = AiAnalysisState(analysis = it) }
            .onFailure { error ->
                _ai.value = _ai.value.copy(loading = false, error = error.message)
            }
    }

    fun refresh() = viewModelScope.launch {
        _state.value = AnalysisState(loading = true)
        val eventResult = runCatching { repository.event(id).event }
        if (eventResult.isFailure) {
            _state.value = AnalysisState(loading = false, error = eventResult.exceptionOrNull()?.message)
            return@launch
        }
        val report = async { runCatching { repository.analysis(id) } }
        val market = async { runCatching { repository.market(id) } }
        val reportResult = report.await()
        _state.value = AnalysisState(
            event = eventResult.getOrNull(),
            report = reportResult.getOrNull(),
            market = market.await().getOrNull(),
            loading = false,
            error = reportResult.exceptionOrNull()?.message,
        )
    }
}

class HistoryViewModel(private val repository: MacroRepository) : ViewModel() {
    private val _state = MutableStateFlow(LoadState<List<EconomicEvent>>())
    val state = _state.asStateFlow()
    private val _category = MutableStateFlow<String?>(null)
    val category = _category.asStateFlow()
    private val _hasMore = MutableStateFlow(true)
    val hasMore = _hasMore.asStateFlow()
    private var selectedCountries = repository.selectedCountries.value
    private var sourceOffset = 0
    private var request: Job? = null

    init {
        viewModelScope.launch {
            // The screen performs the first reset itself; react only to later country changes.
            repository.selectedCountries.drop(1).collect { countries ->
                selectedCountries = countries
                refresh()
            }
        }
        viewModelScope.launch {
            repository.translationsUpdated.collect { refreshTranslations() }
        }
    }

    fun refresh(category: String? = _category.value, forceNetwork: Boolean = false) {
        request?.cancel()
        val retained = if (category == _category.value) {
            _state.value.value.orEmpty().filter { it.country in selectedCountries }
        } else emptyList()
        _category.value = category
        sourceOffset = 0
        _hasMore.value = selectedCountries.isNotEmpty()
        // Keep visible history during a retry and on network failure, not a blank screen. A
        // completed refresh replaces it with the newest eight rows.
        _state.value = LoadState(value = retained, loading = false)
        if (selectedCountries.isNotEmpty()) {
            loadPage(forceRefresh = forceNetwork, replaceExisting = true)
        }
    }

    fun loadMore() = loadPage(replaceExisting = false)

    private fun loadPage(forceRefresh: Boolean = false, replaceExisting: Boolean) {
        if (_state.value.loading || !_hasMore.value) return
        val existing = _state.value.value.orEmpty()
        val category = _category.value
        _state.value = LoadState(existing, loading = true)
        request = viewModelScope.launch {
            try {
                val page = repository.history(
                    countries = selectedCountries,
                    category = category,
                    limit = MacroRepository.HISTORY_PAGE_SIZE,
                    offset = sourceOffset,
                    forceRefresh = forceRefresh,
                )
                sourceOffset += page.size
                val merged = (if (replaceExisting) page else existing + page)
                    .distinctBy { it.id }
                    .sortedWith(compareByDescending<EconomicEvent> { it.eventTime }.thenByDescending { it.importance })
                _hasMore.value = page.size == MacroRepository.HISTORY_PAGE_SIZE
                _state.value = LoadState(merged, loading = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = LoadState(existing, loading = false, error = error.message)
            }
        }
    }

    /** Re-reads name-keyed translations after the user corrects one on the detail screen. */
    fun refreshTranslations() {
        val current = _state.value.value.orEmpty()
        if (current.isEmpty()) return
        viewModelScope.launch {
            val updates = runCatching {
                repository.cachedTranslations(current.map(EconomicEvent::event))
            }.getOrNull().orEmpty()
            if (updates.isEmpty()) return@launch
            // A page refresh may have completed while translations were being read.
            val latest = _state.value.value.orEmpty()
            val refreshed = latest.map { it.withTranslation(updates) }
            if (refreshed != latest) _state.value = _state.value.copy(value = refreshed)
        }
    }
}

/** Applies locally stored translations (keyed by event name) onto an already rendered event. */
private fun EconomicEvent.withTranslation(
    updates: Map<String, Pair<String, String>>,
): EconomicEvent = updates[event]?.let { (zhCn, zhTw) ->
    if (eventZhCn == zhCn && eventZhTw == zhTw) this
    else copy(eventZhCn = zhCn, eventZhTw = zhTw)
} ?: this

@Suppress("UNCHECKED_CAST")
fun <T : ViewModel> viewModelFactory(create: () -> T): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        override fun <R : ViewModel> create(modelClass: Class<R>): R = create() as R
    }
