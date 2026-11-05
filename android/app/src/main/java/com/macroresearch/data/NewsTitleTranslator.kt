package com.macroresearch.data

import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant

internal data class NewsTitleTranslationResult(val titles: Map<String, String>, val failed: Boolean = false)

/** Separate, opt-in presentation cache. Original RSS/archive titles are never overwritten. */
class NewsTitleTranslator(
    private val file: File,
    private val translate: suspend (List<String>, String, TranslationSettings, String) -> Map<String, String>,
    private val clock: () -> Instant = Instant::now,
    private val gson: Gson = Gson(),
) {
    private data class Entry(val key: String, val text: String, val savedAt: Long)
    private val mutex = Mutex()
    private val entries = linkedMapOf<String, Entry>()
    private val cooldowns = linkedMapOf<String, Long>()
    private var loaded = false

    internal suspend fun titles(
        titles: List<String>, language: String, namespace: String, settings: TranslationSettings, apiKey: String,
    ): NewsTitleTranslationResult = mutex.withLock {
        require(language in setOf("en", "zh-CN", "zh-TW"))
        val input = titles.distinct().filter { it.isNotBlank() && it.length <= 300 }.take(BATCH_SIZE)
        if (input.isEmpty() || !settings.configured || apiKey.isBlank()) return@withLock NewsTitleTranslationResult(emptyMap())
        load()
        val now = clock().epochSecond
        entries.entries.removeAll { now - it.value.savedAt !in 0..TTL_SECONDS }
        val provider = digest("${settings.baseUrl}|${settings.model}")
        fun key(title: String) = digest(gson.toJson(listOf(namespace, provider, language, title)))
        val result = linkedMapOf<String, String>()
        input.forEach { title -> entries.remove(key(title))?.let { entry ->
            entries[entry.key] = entry
            result[title] = entry.text
        } }
        val pending = input.filterNot(result::containsKey)
        if (pending.isEmpty()) return@withLock NewsTitleTranslationResult(result)
        if ((cooldowns[provider] ?: 0) > now) return@withLock NewsTitleTranslationResult(result, failed = true)
        val translated = try {
            withTimeoutOrNull(20_000) { translate(pending, language, settings, apiKey) }.orEmpty()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { emptyMap() }
        pending.forEach { title ->
            translated[title]?.trim()?.takeIf { it.isNotBlank() && it.length <= 600 && '<' !in it && '>' !in it }
                ?.let { text ->
                    val id = key(title)
                    entries[id] = Entry(id, text, now)
                    result[title] = text
                }
        }
        val failed = pending.any { it !in result }
        if (failed) {
            cooldowns[provider] = now + 60
            while (cooldowns.size > 8) cooldowns.remove(cooldowns.keys.first())
        }
        while (entries.size > MAX_ENTRIES) entries.remove(entries.keys.first())
        save()
        NewsTitleTranslationResult(result, failed)
    }

    private suspend fun load() = withContext(Dispatchers.IO) {
        if (loaded) return@withContext
        loaded = true
        try {
            if (!file.isFile || file.length() > MAX_BYTES) return@withContext
            val now = clock().epochSecond
            gson.fromJson(file.readText(), Array<Entry>::class.java).takeLast(MAX_ENTRIES).forEach { entry ->
                if (entry.key.matches(Regex("[a-f0-9]{64}")) && entry.text.isNotBlank() && entry.text.length <= 600 &&
                    now - entry.savedAt in 0..TTL_SECONDS) entries[entry.key] = entry
            }
        } catch (_: Exception) { entries.clear() }
    }

    private suspend fun save() = withContext(Dispatchers.IO) {
        try {
            val text = gson.toJson(entries.values)
            if (text.toByteArray().size > MAX_BYTES) return@withContext
            file.parentFile?.mkdirs()
            val temp = File(file.path + ".tmp")
            temp.writeText(text)
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (_: Exception) { /* A cache failure must not prevent reading the news. */ }
    }

    companion object {
        const val BATCH_SIZE = 10
        private const val MAX_ENTRIES = 512
        private const val MAX_BYTES = 2 * 1024 * 1024
        private const val TTL_SECONDS = 30L * 24 * 60 * 60
        private fun digest(value: String) = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
