package com.macroresearch.ui.calendar

import androidx.lifecycle.ViewModelStore
import com.macroresearch.data.*
import com.macroresearch.data.model.*
import com.macroresearch.data.remote.BackendUnauthorizedException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.*

@OptIn(ExperimentalCoroutinesApi::class)
class MonthCalendarTest {
    private val now = Instant.parse("2026-11-03T15:00:00Z")
    private val today = LocalDate.of(2026, 11, 3)
    private val settings = MutableStateFlow(BackendSettings())
    private val stores = mutableListOf<ViewModelStore>()

    @Before fun mainDispatcher() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() {
        stores.forEach { it.clear() }
        Dispatchers.resetMain()
    }

    private class Economic(override val settings: MutableStateFlow<BackendSettings>) : MonthEconomicSource {
        override val countries = MutableStateFlow(setOf("united states"))
        val rows = MutableStateFlow<List<EconomicEvent>>(emptyList())
        val requests = mutableListOf<Pair<LocalDate, LocalDate>>()
        override fun observe(start: LocalDate, end: LocalDate) = rows
        override suspend fun refresh(start: LocalDate, end: LocalDate) { requests += start to end }
    }
    private fun earnings(
        initial: Map<LocalDate, EarningsCalendar> = emptyMap(),
        load: suspend (LocalDate) -> EarningsCalendar,
    ) = EarningsRepository(
        settings, object : EarningsStore {
            val cache = initial.entries.associate { (date, calendar) ->
                "direct:$date" to com.google.gson.Gson().toJson(calendar)
            }.toMutableMap()
            override suspend fun read(source: String, date: String) = cache["$source:$date"]
            override suspend fun write(source: String, date: String, payload: String) { cache["$source:$date"] = payload }
            override suspend fun prune(before: String) = Unit
        }, direct = { date, _ -> load(date) }, backend = { date, _ -> load(date) }, clock = { now },
    )
    private fun calendar(date: LocalDate, symbol: String = "AAPL") = EarningsCalendar(
        date.toString(), updatedAt = now.toString(), events = listOf(EarningsEvent(symbol, "Apple", "nasdaq", "after_hours")),
    )
    private fun vm(economic: Economic, earnings: EarningsRepository?): MonthCalendarViewModel =
        MonthCalendarViewModel(economic, earnings, clock = { now }).also { vm ->
            stores += ViewModelStore().also { it.put("month", vm) }
        }

    @Test fun sundayGridIncludesLeapDaysYearBoundariesAndRealSpilloverDates() {
        for (month in listOf(YearMonth.of(2024, 2), YearMonth.of(2026, 11), YearMonth.of(2026, 12))) {
            val dates = monthDates(month)
            assertEquals(42, dates.size)
            assertEquals(DayOfWeek.SUNDAY, dates.first().dayOfWeek)
            assertEquals(DayOfWeek.SATURDAY, dates.last().dayOfWeek)
            assertEquals(42, dates.distinct().size)
            assertTrue(dates.containsAll((1..month.lengthOfMonth()).map(month::atDay)))
        }
        assertTrue(monthDates(YearMonth.of(2024, 2)).contains(LocalDate.of(2024, 2, 29)))
        assertTrue(monthDates(YearMonth.of(2026, 12)).any { it.year == 2027 })
    }

    @Test fun earningsRangeIsInclusiveAndNeverReinterpretsTheMarketDate() {
        assertTrue(supportsEarnings(today.minusDays(120), today))
        assertTrue(supportsEarnings(today.plusDays(60), today))
        assertFalse(supportsEarnings(today.minusDays(121), today))
        assertFalse(supportsEarnings(today.plusDays(61), today))
    }

    @Test fun economicInstantsGroupInDeviceZoneIncludingDstBoundaries() {
        val event = event("2026-11-01T03:30:00Z")
        val newYork = economicByDate(listOf(event), EARNINGS_ZONE)
        val shanghai = economicByDate(listOf(event), ZoneId.of("Asia/Shanghai"))
        assertEquals(listOf(event), newYork[LocalDate.of(2026, 10, 31)])
        assertEquals(listOf(event), shanghai[LocalDate.of(2026, 11, 1)])
        assertTrue(economicByDate(listOf(event.copy(eventTime = "invalid")), EARNINGS_ZONE).isEmpty())
    }

    @Test fun loadsOneEconomicRangeAndEarningsProgressivelyWithSelectedDayFirst() = runTest {
        val source = Economic(settings)
        val requests = mutableListOf<LocalDate>()
        val vm = vm(source, earnings { date -> requests += date; calendar(date) })
        advanceUntilIdle()
        assertEquals(today, requests.first())
        assertEquals(42, requests.size)
        assertEquals(monthDates(YearMonth.from(today)).toSet(), requests.toSet())
        assertEquals(1, source.requests.size)
        assertFalse(vm.state.value.earningsLoading)
        assertTrue(vm.state.value.economicLoaded)
        assertEquals("after_hours", vm.state.value.earnings[today]?.calendar?.events?.single()?.session)
    }

    @Test fun filtersStopHiddenRequestsAndOutOfRangeMonthsDoNotFetchEarnings() = runTest {
        val source = Economic(settings)
        var calls = 0
        val vm = vm(source, earnings { date -> calls++; calendar(date) })
        advanceUntilIdle()
        vm.selectContent(CalendarContent.ECONOMIC)
        vm.selectMonth(YearMonth.of(2027, 4))
        val before = calls
        advanceUntilIdle()
        assertEquals(before, calls)
        val economicCalls = source.requests.size
        vm.selectContent(CalendarContent.EARNINGS)
        advanceUntilIdle()
        assertEquals(economicCalls, source.requests.size)
        assertEquals(before, calls)
        assertFalse(vm.state.value.earningsLoading)
        assertFalse(vm.state.value.showEconomic)
    }

    @Test fun clickingADatePrioritizesItAheadOfOtherMonthRequests() = runTest {
        val source = Economic(settings)
        val requests = mutableListOf<LocalDate>()
        val first = CompletableDeferred<Unit>()
        val vm = vm(source, earnings { date ->
            requests += date
            if (requests.size == 1) first.await()
            calendar(date)
        })
        runCurrent()
        val selected = today.plusDays(10)
        vm.selectDate(selected)
        first.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(today, selected), requests.take(2))
    }

    @Test fun lateResponseFromPreviousMonthCannotPopulateNewMonth() = runTest {
        val source = Economic(settings)
        val first = CompletableDeferred<Unit>()
        var calls = 0
        val vm = vm(source, earnings { date ->
            calls++
            if (calls == 1) withContext(NonCancellable) { first.await() }
            calendar(date)
        })
        runCurrent()
        val next = YearMonth.of(2026, 12)
        vm.selectMonth(next)
        first.complete(Unit)
        advanceUntilIdle()
        assertEquals(next, vm.state.value.month)
        assertTrue(vm.state.value.earnings.keys.all { it in monthDates(next) })
        assertFalse(vm.state.value.earnings.containsKey(today))
    }

    @Test fun authorizationFailureIsNotAnEmptyMonthAndDoesNotCauseRequestStorm() = runTest {
        settings.value = BackendSettings(mode = DataSourceMode.BACKEND, baseUrl = "https://example.test")
        var calls = 0
        val vm = vm(Economic(settings), earnings { calls++; throw BackendUnauthorizedException("denied") })
        advanceUntilIdle()
        assertEquals(1, calls)
        assertTrue(vm.state.value.earnings.values.all { it.error == "unauthorized" && it.calendar == null })
        assertFalse(vm.state.value.earningsLoading)
    }

    @Test fun staleLocalSnapshotsAppearBeforeMonthNetworkRequestsComplete() = runTest {
        val first = CompletableDeferred<Unit>()
        val cachedDate = today.plusDays(5)
        val cached = calendar(cachedDate).copy(updatedAt = now.minusSeconds(172800).toString())
        val vm = vm(Economic(settings), earnings(mapOf(cachedDate to cached)) { date ->
            first.await()
            calendar(date)
        })
        runCurrent()
        assertTrue(vm.state.value.earnings[cachedDate]?.calendar?.stale == true)
        assertFalse(vm.state.value.earnings[cachedDate]?.loaded == true)
        assertTrue(vm.state.value.earningsLoading)
        first.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.state.value.earnings[cachedDate]?.calendar?.stale == true)
    }

    @Test fun aSourceChangeDropsLateResultsAndNeverUsesTheOldModeAsFallback() = runTest {
        val first = CompletableDeferred<Unit>()
        var calls = 0
        val vm = vm(Economic(settings), earnings { date ->
            val symbol = if (++calls == 1) "OLD" else "NEW"
            if (calls == 1) withContext(NonCancellable) { first.await() }
            calendar(date, symbol)
        })
        runCurrent()
        settings.value = BackendSettings(mode = DataSourceMode.BACKEND, baseUrl = "https://new.example")
        runCurrent()
        first.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.state.value.earnings.values.isNotEmpty())
        assertTrue(vm.state.value.earnings.values.all { it.calendar?.events?.single()?.symbol == "NEW" })
    }

    @Test fun repeatedNetworkFailureStopsAfterThreeDatesWithoutInventingEmptySuccess() = runTest {
        var calls = 0
        val vm = vm(Economic(settings), earnings { calls++; throw java.io.IOException("offline") })
        advanceUntilIdle()
        assertEquals(3, calls)
        assertEquals(42, vm.state.value.earnings.size)
        assertTrue(vm.state.value.earnings.values.all { it.error == "unavailable" && it.calendar == null })
    }

    @Test fun countryAndImportanceSelectionsApplyToBothGridAndDayList() = runTest {
        val source = Economic(settings)
        val vm = vm(source, null)
        source.rows.value = listOf(event(now.toString()), event(now.toString()).copy(id = 2, importance = 1))
        advanceUntilIdle()
        assertEquals(2, vm.state.value.filteredEconomic.size)
        vm.toggleImportance(1)
        assertEquals(1, vm.state.value.filteredEconomic.size)
        source.countries.value = emptySet()
        advanceUntilIdle()
        assertTrue(vm.state.value.filteredEconomic.isEmpty())
    }

    private fun event(time: String) = EconomicEvent(
        1, "tradingview", "1", null, "united states", "USD", "inflation", "CPI",
        eventTime = time, importance = 3, actual = null, previous = "2.5", consensus = "2.6",
        forecast = null, unit = "%", status = "scheduled",
    )
}
