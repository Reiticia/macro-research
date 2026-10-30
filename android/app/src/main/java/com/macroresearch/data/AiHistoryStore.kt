package com.macroresearch.data

import com.google.gson.Gson
import com.macroresearch.data.remote.NewsArticle
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** Latest execution per event/method, private no-backup storage; never contains unredacted requests. */
data class AiHistory(
    val session: String = "",
    val steps: List<AiExecutionStep> = emptyList(),
    val requests: List<AiRequestRecord> = emptyList(),
    val articles: List<NewsArticle>? = emptyList(),
)

class AiHistoryStore(private val directory: File) {
    private val gson = Gson()
    private val sessions = mutableMapOf<String, String>()

    @Synchronized
    fun load(key: String): AiHistory {
        val file = file(key)
        if (!file.isFile || file.length() > MAX_FILE_BYTES) return AiHistory()
        return runCatching {
            gson.fromJson(file.readText(), AiHistory::class.java).let { history ->
                // A process death can leave a checkpoint in flight. It cannot still be running.
                if (sessions[key] == history.session) history else history.copy(
                    steps = history.steps.map { if (it.status == AiStepStatus.RUNNING) it.copy(status = AiStepStatus.CANCELLED) else it },
                    requests = history.requests.map { if (it.status == AiStepStatus.RUNNING) it.copy(status = AiStepStatus.CANCELLED) else it },
                )
            }
        }.getOrDefault(AiHistory())
    }

    @Synchronized
    fun begin(key: String, appendRequests: Boolean = false): AiHistory {
        val previous = if (appendRequests) load(key).requests else emptyList()
        val history = AiHistory(UUID.randomUUID().toString(), requests = previous)
        sessions[key] = history.session
        write(key, history)
        return history
    }

    @Synchronized
    fun save(key: String, history: AiHistory) {
        // Late callbacks from an old run or a data-mode switch may not revive cleared data.
        if (sessions[key] != history.session) return
        write(key, history)
    }

    @Synchronized
    fun finish(key: String, history: AiHistory, status: AiStepStatus) {
        save(key, history.copy(
            steps = history.steps.map { if (it.status == AiStepStatus.RUNNING) it.copy(status = status) else it },
            requests = history.requests.map { if (it.status == AiStepStatus.RUNNING) it.copy(status = status) else it },
        ))
        if (sessions[key] == history.session) sessions.remove(key)
    }

    @Synchronized
    fun clear(key: String) {
        sessions.remove(key)
        file(key).delete()
    }

    @Synchronized
    fun clearAll() {
        sessions.clear()
        directory.listFiles()?.forEach { it.delete() }
    }

    private fun file(key: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(directory, "$hash.json")
    }

    private fun write(key: String, history: AiHistory) {
        directory.mkdirs()
        val target = file(key)
        val bytes = gson.toJson(history).toByteArray(Charsets.UTF_8)
        check(bytes.size <= MAX_FILE_BYTES) { "AI history exceeds local storage limit" }
        val temp = File(directory, target.name + ".tmp")
        try {
            FileOutputStream(temp).use { stream -> stream.write(bytes); stream.fd.sync() }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            val files = directory.listFiles()?.filter { it.extension == "json" }?.sortedBy { it.lastModified() }.orEmpty()
            var size = files.sumOf { it.length() }
            var count = files.size
            for (old in files) {
                if (size <= MAX_TOTAL_BYTES && count <= MAX_FILES) break
                if (old == target || sessions.values.isNotEmpty() && sessions.keys.any { file(it) == old }) continue
                val length = old.length()
                if (old.delete()) { size -= length; count-- }
            }
        } finally { temp.delete() }
    }

    companion object {
        private const val MAX_FILE_BYTES = 16 * 1024 * 1024L
        private const val MAX_TOTAL_BYTES = 48 * 1024 * 1024L
        private const val MAX_FILES = 100
    }
}
