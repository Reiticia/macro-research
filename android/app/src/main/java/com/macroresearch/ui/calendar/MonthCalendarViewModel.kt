package com.macroresearch.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macroresearch.data.BackendSettings
import com.macroresearch.data.EarningsRepository
import com.macroresearch.data.MacroRepository
import com.macroresearch.data.model.EARNINGS_ZONE
import com.macroresearch.data.model.EconomicEvent
import com.macroresearch.data.remote.BackendException
import com.macroresearch.data.remote.BackendUnauthorizedException
import com.macroresearch.ui.common.toggleImportanceSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

internal interface MonthEconomicSource {
    val settings: StateFlow<BackendSettings>
    val countries: StateFlow<Set<String>>
    fun observe(start: LocalDate, end: LocalDate): Flow<List<EconomicEvent>>
    suspend fun refresh(start: LocalDate, end: LocalDate)
}

internal class RepositoryMonthEconomicSource(private val repository: MacroRepository) : MonthEconomicSource {
    override val settings = repository.dataSourceSettings
    override val countries = repository.selectedCountries
    override fun observe(start: LocalDate, end: LocalDate) = repository.observeCalendar(start, end)
    override suspend fun refresh(start: LocalDate, end: LocalDate) = repository.refreshCalendarRange(start, end)
}

internal class MonthCalendarViewModel(
    private val economic: MonthEconomicSource,
    private val earnings: EarningsRepository?,
    private val clock: () -> Instant = Instant::now,
) : ViewModel() {
    private val today get() = clock().atZone(EARNINGS_ZONE).toLocalDate()
    private val mutable = MutableStateFlow(MonthCalendarState(
        month = YearMonth.from(today), selectedDate = today,
        countries = economic.countries.value, earningsAvailable = earnings != null,
    ))
    val state = mutable.asStateFlow()
    private var economicRequest: Job? = null
    private var economicObservation: Job? = null
    private var earningsRequest: Job? = null
    private var generation = 0

    init {
        viewModelScope.launch {
            combine(economic.settings, earnings?.settings ?: economic.settings) { a, b -> a to b }
                .collect {
                    mutable.value = mutable.value.copy(
                        economic = emptyList(), earnings = emptyMap(), economicLoaded = false,
                        economicFailed = false,
                    )
                    loadMonth()
                }
        }
        viewModelScope.launch {
            economic.countries.collect { countries -> mutable.value = mutable.value.copy(countries = countries) }
        }
    }

    fun selectMonth(month: YearMonth) {
        if (month == mutable.value.month) return
        mutable.value = mutable.value.copy(
            month = month,
            selectedDate = if (month == YearMonth.from(today)) today else month.atDay(1),
            economic = emptyList(), earnings = emptyMap(), economicLoaded = false, economicFailed = false,
        )
        loadMonth()
    }

    fun selectDate(date: LocalDate) {
        if (YearMonth.from(date) != mutable.value.month) selectMonth(YearMonth.from(date))
        mutable.value = mutable.value.copy(selectedDate = date)
        // The serial earnings worker reads this value before every request, prioritizing this day.
    }

    fun selectToday() = selectDate(today)

    fun selectContent(content: CalendarContent) {
        if (content == mutable.value.content) return
        mutable.value = mutable.value.copy(content = content)
        loadMonth()
    }

    fun toggleImportance(level: Int) {
        mutable.value = mutable.value.copy(importance = toggleImportanceSelection(mutable.value.importance, level))
    }

    fun refresh() = loadMonth(forceSelected = true)

    private fun loadMonth(forceSelected: Boolean = false) {
        economicRequest?.cancel()
        economicObservation?.cancel()
        earningsRequest?.cancel()
        val version = ++generation
        val dates = monthDates(mutable.value.month)
        mutable.value = mutable.value.copy(
            economicLoading = mutable.value.showEconomic && (!mutable.value.economicLoaded || forceSelected),
            earningsLoading = mutable.value.showEarnings,
        )
        if (mutable.value.showEconomic) {
            economicObservation = viewModelScope.launch {
                economic.observe(dates.first(), dates.last()).collect { rows ->
                    if (version == generation) mutable.value = mutable.value.copy(economic = rows)
                }
            }
            if (!mutable.value.economicLoaded || forceSelected) economicRequest = viewModelScope.launch {
                try {
                    economic.refresh(dates.first(), dates.last())
                    if (version == generation) mutable.value = mutable.value.copy(
                        economicLoading = false, economicLoaded = true, economicFailed = false,
                    )
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    if (version == generation) mutable.value = mutable.value.copy(economicLoading = false, economicFailed = true)
                }
            }
        }
        val repository = earnings ?: return
        if (!mutable.value.showEarnings) return
        earningsRequest = viewModelScope.launch {
            val supported = dates.filter { supportsEarnings(it, today) }
            // Read all local snapshots first, so network work never holds the entire grid blank.
            for (date in supported) {
                if (mutable.value.earnings[date]?.calendar == null) {
                    val cached = try { repository.cached(date) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                    if (version != generation) return@launch
                    if (cached != null) updateDay(date, MonthEarningsDay(calendar = cached))
                }
            }
            val pending = supported.toMutableSet()
            var failures = 0
            while (pending.isNotEmpty()) {
                val selected = mutable.value.selectedDate
                val date = when {
                    selected in pending -> selected
                    today in pending -> today
                    else -> pending.first()
                }
                pending.remove(date)
                try {
                    val result = repository.load(date, force = forceSelected && date == selected)
                    if (version != generation) return@launch
                    updateDay(date, MonthEarningsDay(calendar = result, loaded = true))
                    failures = 0
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    if (version != generation) return@launch
                    val reason = when {
                        error is BackendUnauthorizedException -> "unauthorized"
                        error is BackendException && error.statusCode in setOf(404, 501) -> "unsupported"
                        else -> "unavailable"
                    }
                    updateDay(date, MonthEarningsDay(mutable.value.earnings[date]?.calendar, reason, loaded = true))
                    failures++
                    // Do not send a whole month of doomed auth/unsupported/offline requests.
                    if (reason != "unavailable" || failures >= 3) {
                        pending.forEach { remaining ->
                            updateDay(remaining, MonthEarningsDay(mutable.value.earnings[remaining]?.calendar, reason, loaded = true))
                        }
                        break
                    }
                }
            }
            if (version == generation) mutable.value = mutable.value.copy(earningsLoading = false)
        }
    }

    private fun updateDay(date: LocalDate, value: MonthEarningsDay) {
        mutable.value = mutable.value.copy(earnings = mutable.value.earnings + (date to value))
    }
}
