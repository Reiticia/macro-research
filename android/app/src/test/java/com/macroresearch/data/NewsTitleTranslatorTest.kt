package com.macroresearch.data

import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.time.Instant

class NewsTitleTranslatorTest {
    @get:Rule val temporary = TemporaryFolder()
    private val settings = TranslationSettings(true, "https://personal.example/v1", "test-model")
    private val now = Instant.parse("2026-11-05T10:00:00Z")
    private fun file() = File(temporary.root, "title-translations.json")

    @Test fun cacheSurvivesRecreationAndIsolatesLanguageTitleSourceAndProvider() = runBlocking {
        var calls = 0
        val translate: suspend (List<String>, String, TranslationSettings, String) -> Map<String, String> = { titles, lang, _, key ->
            assertEquals("private-key", key)
            calls++
            titles.associateWith { "Translated $lang" }
        }
        fun translator() = NewsTitleTranslator(file(), translate, clock = { now })
        val first = translator()
        assertEquals("Translated zh-CN", first.titles(listOf("Original headline"), "zh-CN", "backend-a", settings, "private-key").titles["Original headline"])
        val recreated = translator()
        recreated.titles(listOf("Original headline"), "zh-CN", "backend-a", settings, "private-key")
        assertEquals(1, calls)
        recreated.titles(listOf("Original headline"), "zh-TW", "backend-a", settings, "private-key")
        recreated.titles(listOf("Original headline"), "en", "backend-a", settings, "private-key")
        recreated.titles(listOf("Revised headline"), "zh-CN", "backend-a", settings, "private-key")
        recreated.titles(listOf("Original headline"), "zh-CN", "direct", settings, "private-key")
        recreated.titles(listOf("Original headline"), "zh-CN", "backend-b", settings, "private-key")
        recreated.titles(listOf("Original headline"), "zh-CN", "backend-a", settings.copy(model = "other-model"), "private-key")
        recreated.titles(listOf("Original headline"), "zh-CN", "backend-a", settings.copy(baseUrl = "https://other.example/v1"), "private-key")
        assertEquals(8, calls)
        val text = file().readText()
        listOf("private-key", "Original headline", "backend-a", "personal.example", "test-model").forEach { assertFalse(text.contains(it)) }
    }

    @Test fun partialFailurePreservesSuccessAndCoolsDownWithoutRetryStorms() = runBlocking {
        var time = now
        var calls = 0
        val translator = NewsTitleTranslator(file(), { titles, _, _, _ ->
            calls++
            if (calls == 1) mapOf("First" to "第一条") else titles.associateWith { "译文" }
        }, clock = { time })
        val first = translator.titles(listOf("First", "Second"), "zh-CN", "direct", settings, "key")
        assertTrue(first.failed)
        assertEquals(mapOf("First" to "第一条"), first.titles)
        val cached = translator.titles(listOf("First"), "zh-CN", "direct", settings, "key")
        assertFalse(cached.failed)
        assertEquals(first.titles, cached.titles)
        assertTrue(translator.titles(listOf("Third"), "zh-CN", "direct", settings, "key").failed)
        assertEquals(1, calls)
        time = time.plusSeconds(61)
        val retried = translator.titles(listOf("First", "Second"), "zh-CN", "direct", settings, "key")
        assertFalse(retried.failed)
        assertEquals("第一条", retried.titles["First"])
        assertEquals("译文", retried.titles["Second"])
        assertEquals(2, calls)
    }

    @Test fun networkAndInvalidResponsesFallBackWithoutPersistingFakeSuccess() = runBlocking {
        val translator = NewsTitleTranslator(file(), { _, _, _, _ -> throw IOException("HTTP 429") }, clock = { now })
        assertTrue(translator.titles(listOf("Headline"), "en", "direct", settings, "key").failed)
        assertTrue(translator.titles(listOf("Headline"), "en", "direct", settings, "key").titles.isEmpty())
        val invalid = NewsTitleTranslator(File(temporary.root, "other.json"), { _, _, _, _ ->
            mapOf("Headline" to "<script>bad</script>", "Other" to "invented")
        }, clock = { now })
        assertTrue(invalid.titles(listOf("Headline"), "en", "direct", settings, "key").titles.isEmpty())
    }

    @Test fun missingKeyOrConfigurationNeverCallsTheProvider() = runBlocking {
        val translator = NewsTitleTranslator(file(), { _, _, _, _ -> error("Must not call") })
        assertTrue(translator.titles(listOf("Title"), "en", "direct", settings.copy(configured = false), "key").titles.isEmpty())
        assertTrue(translator.titles(listOf("Title"), "en", "direct", settings, "").titles.isEmpty())
        assertFalse(file().exists())
    }

    @Test fun expiryAndFutureCacheTimesAreNotReused() = runBlocking {
        var time = now
        var calls = 0
        fun translator() = NewsTitleTranslator(file(), { titles, _, _, _ ->
            calls++
            titles.associateWith { "Translated" }
        }, clock = { time })
        translator().titles(listOf("Title"), "en", "direct", settings, "key")
        time = time.plusSeconds(30L * 86400 + 1)
        translator().titles(listOf("Title"), "en", "direct", settings, "key")
        time = now
        translator().titles(listOf("Title"), "en", "direct", settings, "key")
        assertEquals(3, calls)
    }

    @Test fun requestAndPersistentCacheAreBounded() = runBlocking {
        var calls = 0
        val translator = NewsTitleTranslator(file(), { titles, _, _, _ ->
            assertTrue(titles.size <= 10)
            calls++
            titles.associateWith { "Translated" }
        }, clock = { now })
        assertEquals(10, translator.titles((0..100).map { "Title $it" }, "en", "direct", settings, "key").titles.size)
        repeat(53) { batch ->
            translator.titles((0..9).map { "Batch $batch title $it" }, "en", "direct", settings, "key")
        }
        assertEquals(512, JsonParser.parseString(file().readText()).asJsonArray.size())
        assertTrue(file().length() < 2 * 1024 * 1024)
        assertEquals(54, calls)
    }

    @Test fun concurrentSameTitlesUseOneProviderCall() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var calls = 0
        val translator = NewsTitleTranslator(file(), { titles, _, _, _ ->
            calls++
            started.complete(Unit)
            finish.await()
            titles.associateWith { "Translated" }
        }, clock = { now })
        val first = async { translator.titles(listOf("Title"), "en", "direct", settings, "key") }
        started.await()
        val second = async { translator.titles(listOf("Title"), "en", "direct", settings, "key") }
        finish.complete(Unit)
        assertEquals(first.await(), second.await())
        assertEquals(1, calls)
    }

    @Test fun leavingThePageCancelsAndDoesNotPoisonCacheOrCooldown() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var calls = 0
        val translator = NewsTitleTranslator(file(), { titles, _, _, _ ->
            calls++
            if (calls == 1) { started.complete(Unit); awaitCancellation() }
            titles.associateWith { "Translated" }
        }, clock = { now })
        val job = launch { translator.titles(listOf("Title"), "en", "direct", settings, "key") }
        started.await()
        job.cancelAndJoin()
        val result = translator.titles(listOf("Title"), "en", "direct", settings, "key")
        assertFalse(result.failed)
        assertEquals("Translated", result.titles["Title"])
        assertEquals(2, calls)
    }

    @Test fun slowTranslationTimesOutAndFallsBack() = runTest {
        val translator = NewsTitleTranslator(file(), { _, _, _, _ -> delay(60_000); error("Too late") }, clock = { now })
        val result = translator.titles(listOf("Title"), "en", "direct", settings, "key")
        assertTrue(result.failed)
        assertTrue(result.titles.isEmpty())
    }

    @Test fun corruptCacheDoesNotBlockTranslation() = runBlocking {
        file().writeText("broken json")
        val translator = NewsTitleTranslator(file(), { titles, _, _, _ -> titles.associateWith { "Translated" } }, clock = { now })
        assertEquals("Translated", translator.titles(listOf("Title"), "en", "direct", settings, "key").titles["Title"])
    }
}
