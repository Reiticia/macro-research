package com.macroresearch.data

import com.macroresearch.data.remote.MAX_LOGO_BYTES
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

class CompanyLogoDiskCacheTest {
    private val png get() = javaClass.getResourceAsStream("/earnings/logo.png")!!.use { it.readBytes() }
    private val now = Instant.parse("2026-11-01T12:00:00Z")
    private fun withDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("company-logo-test").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }

    @Test fun restartReusesDiskWithoutNetworkAndDiskDoesNotStoreBackendAddressOrKey() = withDirectory { directory -> runBlocking {
        val settings = MutableStateFlow(BackendSettings(mode = DataSourceMode.BACKEND, baseUrl = "https://backend.example.com"))
        var calls = 0
        fun loader() = CompanyLogoLoader(settings, { error("No direct fallback") }, { calls++; png },
            clock = { now }, diskCache = CompanyLogoDiskCache(directory))
        assertArrayEquals(png, loader().image("aapl"))
        assertArrayEquals(png, loader().image("AAPL"))
        assertEquals(1, calls)
        assertTrue(directory.listFiles()!!.all { it.name.matches(Regex("[a-f0-9]{64}\\.logo")) })
        assertFalse(directory.listFiles()!!.single().readBytes().toString(Charsets.ISO_8859_1).contains("backend.example"))
        settings.value = settings.value.copy(baseUrl = "https://other.example.com")
        loader().image("AAPL")
        assertEquals(2, calls)
        settings.value = settings.value.copy(baseUrl = "https://backend.example.com")
        assertArrayEquals(png, loader().image("AAPL"))
        assertEquals(2, calls)
    } }

    @Test fun staleDiskImageReturnsBeforeRefreshAndFailurePreservesOriginalAgeAndRetry() = withDirectory { directory -> runBlocking {
        var instant = now
        val settings = MutableStateFlow(BackendSettings())
        val disk = CompanyLogoDiskCache(directory)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            CompanyLogoLoader(settings, { png }, { error("unused") }, clock = { instant }, diskCache = disk).image("AAPL")
            instant = now.plusSeconds(8 * 86400L)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var calls = 0
            val loader = CompanyLogoLoader(settings, {
                calls++; entered.complete(Unit); release.await(); null
            }, { error("unused") }, clock = { instant }, diskCache = disk, refreshScope = scope)
            assertArrayEquals(png, withTimeout(2_000) { loader.image("AAPL") })
            entered.await()
            repeat(10) { assertArrayEquals(png, loader.image("AAPL")) }
            release.complete(Unit)
            scope.coroutineContext[Job]!!.children.toList().joinAll()
            assertEquals(1, calls)
            val saved = disk.read("direct:AAPL")!!
            assertEquals(now, saved.fetchedAt)
            assertEquals(instant.plusSeconds(300), saved.refreshAfter)
            val restarted = CompanyLogoLoader(settings, { error("Retry cooldown should survive restart") }, { null },
                clock = { instant }, diskCache = CompanyLogoDiskCache(directory))
            assertArrayEquals(png, restarted.image("AAPL"))
            instant = now.plusSeconds(31 * 86400L)
            val expired = CompanyLogoLoader(settings, { null }, { null }, clock = { instant }, diskCache = disk)
            assertNull(expired.image("AAPL"))
        } finally { scope.cancel() }
    } }

    @Test fun negativesSurviveRestartButRetryAfterFiveMinutes() = withDirectory { directory -> runBlocking {
        var instant = now
        var calls = 0
        val settings = MutableStateFlow(BackendSettings())
        fun loader() = CompanyLogoLoader(settings, { calls++; if (calls == 1) null else png }, { null },
            clock = { instant }, diskCache = CompanyLogoDiskCache(directory))
        assertNull(loader().image("AAPL"))
        assertNull(loader().image("AAPL"))
        assertEquals(1, calls)
        instant = now.plusSeconds(301)
        assertArrayEquals(png, loader().image("AAPL"))
        assertEquals(2, calls)
    } }

    @Test fun corruptOversizeAndTamperedEntriesAreMissesAndAtomicReplacementLeavesNoTemps() = withDirectory { directory ->
        val disk = CompanyLogoDiskCache(directory)
        val entry = CachedCompanyLogo(png, now, now.plusSeconds(3600))
        for (kind in listOf("truncated", "digest", "oversize")) {
            disk.write("direct:AAPL", entry)
            val file = directory.listFiles()!!.single()
            when (kind) {
                "truncated" -> file.writeText("bad")
                "digest" -> file.writeBytes(file.readBytes().also { it[it.lastIndex] = (it.last() + 1).toByte() })
                else -> file.writeBytes(ByteArray(MAX_LOGO_BYTES + 100))
            }
            assertNull(disk.read("direct:AAPL"))
            assertFalse(file.exists())
        }
        repeat(3) { disk.write("direct:AAPL", entry) }
        assertEquals(1, directory.listFiles()!!.size)
        assertArrayEquals(png, disk.read("direct:AAPL")!!.image)
    }

    @Test fun cacheEvictsLeastRecentlyUsedAndEnforcesTotalBytes() = withDirectory { directory ->
        val disk = CompanyLogoDiskCache(directory, maxEntries = 2)
        val entry = CachedCompanyLogo(png, now, now.plusSeconds(3600))
        disk.write("a", entry)
        val first = directory.listFiles()!!.single()
        disk.write("b", entry)
        first.setLastModified(1)
        disk.write("c", entry)
        assertEquals(2, directory.listFiles()!!.size)
        assertNull(disk.read("a"))
        assertNotNull(disk.read("c"))
        val bounded = CompanyLogoDiskCache(directory, maxBytes = 1)
        bounded.write("d", entry)
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun invalidSymbolsAndOldSourcesNeverWriteDiskAndDifferentSymbolsDoNotBlockCachedReads() = withDirectory { directory -> runBlocking {
        val settings = MutableStateFlow(BackendSettings())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val loader = CompanyLogoLoader(settings, { symbol ->
            if (symbol == "MSFT") { entered.complete(Unit); release.await() }; png
        }, { png }, clock = { now }, diskCache = CompanyLogoDiskCache(directory))
        assertNull(loader.image("../AAPL"))
        assertArrayEquals(png, loader.image("AAPL"))
        val slow = async { loader.image("MSFT") }
        entered.await()
        assertArrayEquals(png, withTimeout(1_000) { loader.image("AAPL") })
        settings.value = BackendSettings(mode = DataSourceMode.BACKEND, baseUrl = "https://new.example")
        release.complete(Unit)
        assertNull(slow.await())
        assertEquals(1, directory.listFiles()!!.size)
        assertNull(CompanyLogoDiskCache(directory).read("direct:MSFT"))
    } }

    @Test fun memoryEvictionStillUsesDiskAndDirectLogosIgnoreUnusedBackendEdits() = withDirectory { directory -> runBlocking {
        var calls = 0
        val settings = MutableStateFlow(BackendSettings())
        val loader = CompanyLogoLoader(settings, { calls++; png }, { error("No backend request") },
            clock = { now }, diskCache = CompanyLogoDiskCache(directory))
        repeat(65) { loader.image("S$it") }
        assertEquals(65, calls)
        settings.value = settings.value.copy(baseUrl = "https://unused.example.com")
        assertArrayEquals(png, loader.image("S0"))
        assertEquals(65, calls)
    } }

    @Test fun unavailableCacheDirectoryDoesNotFailImageLoading() = withDirectory { directory -> runBlocking {
        val file = File(directory, "not-a-directory").apply { writeText("fixture") }
        val loader = CompanyLogoLoader(MutableStateFlow(BackendSettings()), { png }, { null },
            clock = { now }, diskCache = CompanyLogoDiskCache(file))
        assertArrayEquals(png, loader.image("AAPL"))
    } }
}
