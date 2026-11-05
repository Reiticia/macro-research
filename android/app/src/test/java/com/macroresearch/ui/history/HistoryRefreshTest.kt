package com.macroresearch.ui.history

import androidx.lifecycle.ViewModelStore
import com.macroresearch.data.MacroRepository
import com.macroresearch.data.model.EconomicEvent
import com.macroresearch.ui.HistoryViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryRefreshTest {
    private val stores = mutableListOf<ViewModelStore>()
    @Before fun mainDispatcher() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { stores.forEach { it.clear() }; Dispatchers.resetMain() }

    private data class Request(val countries: Set<String>, val category: String?, val limit: Int,
        val offset: Int, val force: Boolean, val importance: Set<Int>)
    private fun event(id: Long) = EconomicEvent(id, "test", id.toString(), null, "united states", "USD", "inflation", "CPI $id",
        eventTime = "2026-11-04T13:30:00Z", importance = 3, actual = "2", previous = "1", consensus = "1",
        forecast = null, unit = "%", status = "released")
    private inner class Source : HistoryDataSource {
        override val selectedCountries = MutableStateFlow(setOf("united states"))
        override val translationsUpdated = MutableSharedFlow<Unit>()
        val requests = mutableListOf<Request>()
        var fetch: suspend (Request) -> List<EconomicEvent> = { request ->
            List(request.limit) { event((request.offset + it + 1).toLong()) }
        }
        override suspend fun history(countries: Collection<String>, category: String?, limit: Int, offset: Int,
            forceRefresh: Boolean, importance: Collection<Int>?): List<EconomicEvent> {
            val request = Request(countries.toSet(), category, limit, offset, forceRefresh, importance.orEmpty().toSet())
            requests += request
            return fetch(request)
        }
        override suspend fun cachedTranslations(names: List<String>) = emptyMap<String, Pair<String, String>>()
    }
    private fun viewModel(source: Source) = HistoryViewModel(source).also { vm ->
        stores += ViewModelStore().also { it.put("history", vm) }
    }

    @Test fun manualRefreshForcesTheNewestPageAndPreservesFilters() = runTest {
        val source = Source()
        val vm = viewModel(source)
        vm.refresh(category = "inflation", importance = setOf(2, 3))
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(16, vm.state.value.value!!.size)
        assertEquals(8, source.requests.last().offset)
        vm.refreshFromUser()
        assertEquals(16, vm.state.value.value!!.size) // Cached rows remain visible while refreshing.
        advanceUntilIdle()
        val request = source.requests.last()
        assertTrue(request.force)
        assertEquals(0, request.offset)
        assertEquals(MacroRepository.HISTORY_PAGE_SIZE, request.limit)
        assertEquals("inflation", request.category)
        assertEquals(setOf(2, 3), request.importance)
        assertEquals(source.selectedCountries.value, request.countries)
        assertEquals(8, vm.state.value.value!!.size)
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(8, source.requests.last().offset)
        assertFalse(source.requests.last().force)
    }

    @Test fun repeatedRefreshClicksDoNotRestartAnActiveRequest() = runTest {
        val source = Source()
        val vm = viewModel(source)
        vm.refresh()
        advanceUntilIdle()
        val finish = CompletableDeferred<Unit>()
        source.fetch = { finish.await(); listOf(event(9)) }
        vm.refreshFromUser()
        runCurrent()
        repeat(5) { vm.refreshFromUser() }
        assertEquals(2, source.requests.size)
        assertTrue(vm.state.value.loading)
        finish.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.state.value.loading)
        assertEquals(listOf(9L), vm.state.value.value!!.map { it.id })
    }

    @Test fun failedRefreshKeepsVisibleRowsAndCanBeRetried() = runTest {
        val source = Source()
        val vm = viewModel(source)
        vm.refresh()
        advanceUntilIdle()
        val original = vm.state.value.value
        source.fetch = { throw IOException("offline") }
        vm.refreshFromUser()
        advanceUntilIdle()
        assertEquals(original, vm.state.value.value)
        assertEquals("offline", vm.state.value.error)
        assertFalse(vm.state.value.loading)
        source.fetch = { listOf(event(20)) }
        vm.refreshFromUser()
        advanceUntilIdle()
        assertNull(vm.state.value.error)
        assertEquals(listOf(20L), vm.state.value.value!!.map { it.id })
    }

    @Test fun noSelectedCountriesMakesRefreshALocalEmptyResult() = runTest {
        val source = Source().apply { selectedCountries.value = emptySet() }
        val vm = viewModel(source)
        vm.refresh()
        advanceUntilIdle()
        vm.refreshFromUser()
        advanceUntilIdle()
        assertTrue(source.requests.isEmpty())
        assertTrue(vm.state.value.value.orEmpty().isEmpty())
        assertFalse(vm.state.value.loading)
        assertFalse(vm.hasMore.value)
    }
}
