package com.macroresearch.data

import com.macroresearch.data.model.EarningsEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class EarningsFavoritesTest {
    private class Store : EarningsFavoritesStore {
        val rows = MutableStateFlow<Map<String, List<FavoriteEarnings>>>(emptyMap())
        override fun observe(source: String) = rows.map { it[source].orEmpty() }
        override suspend fun set(source: String, favorite: FavoriteEarnings, enabled: Boolean) {
            val others = rows.value[source].orEmpty().filterNot { it.date == favorite.date && it.event.symbol == favorite.event.symbol }
            rows.value = rows.value + (source to if (enabled) others + favorite else others)
        }
    }
    private val date = LocalDate.of(2026, 11, 3)
    private val event = EarningsEvent("AAPL", "Apple", "nasdaq")

    @Test fun favoriteIsAnIdempotentOccurrenceAndSurvivesRepositoryRecreation() = runTest {
        val store = Store()
        val repo = quoteRepository(favorites = store)
        repo.setFavorite(date, event, true)
        repo.setFavorite(date, event, true)
        assertEquals(listOf(FavoriteEarnings(date, event)), repo.favorites.first())
        assertEquals(repo.favorites.first(), quoteRepository(favorites = store).favorites.first())
        repo.setFavorite(date.plusDays(1), event, true)
        assertEquals(2, repo.favorites.first().size)
        repo.setFavorite(date, event.copy(source = "tradingview"), false)
        assertEquals(listOf(FavoriteEarnings(date.plusDays(1), event)), repo.favorites.first())
        repo.setFavorite(date, event, false)
        assertEquals(1, repo.favorites.first().size)
    }

    @Test fun modeAndBackendNamespacesStayIsolatedAndDoNotStoreBackendAddresses() = runTest {
        val store = Store()
        val settings = MutableStateFlow(BackendSettings())
        val repo = quoteRepository(settings, store)
        repo.setFavorite(date, event, true)
        settings.value = BackendSettings(mode = DataSourceMode.BACKEND, baseUrl = "https://one.example")
        assertTrue(repo.favorites.first().isEmpty())
        repo.setFavorite(date, event.copy(name = "Backend Apple"), true)
        settings.value = settings.value.copy(baseUrl = "https://two.example")
        assertTrue(repo.favorites.first().isEmpty())
        settings.value = settings.value.copy(baseUrl = "https://one.example")
        assertEquals("Backend Apple", repo.favorites.first().single().event.name)
        settings.value = BackendSettings()
        assertEquals("Apple", repo.favorites.first().single().event.name)
        assertTrue(store.rows.value.keys.none { it.contains("https") || it.contains("example") })
    }

    @Test fun malformedSymbolsAreRejectedBeforeStorage() = runTest {
        val store = Store()
        val repo = quoteRepository(favorites = store)
        try { repo.setFavorite(date, event.copy(symbol = "../AAPL"), true); fail() }
        catch (_: IllegalArgumentException) { }
        assertTrue(store.rows.value.isEmpty())
    }
}
