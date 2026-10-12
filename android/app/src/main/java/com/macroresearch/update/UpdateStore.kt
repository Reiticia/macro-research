package com.macroresearch.update

import android.content.Context
import android.util.AtomicFile
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

interface UpdateStore {
    var automatic: Boolean
    var lastAttempt: Long
    var lastSuccess: Long
    var rateLimitedUntil: Long
    var ignoredCode: Long
    var deferredCode: Long
    var deferredUntil: Long
    var ticket: DownloadTicket?
}
data class DownloadTicket(val id: Long, val release: UpdateRelease)

class UpdatePreferences(context: Context, debug: Boolean) : UpdateStore {
    private val preferences = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val defaultAutomatic = !debug
    override var automatic: Boolean
        get() = preferences.getBoolean("automatic", defaultAutomatic)
        set(value) { preferences.edit().putBoolean("automatic", value).apply() }
    private fun number(key: String) = preferences.getLong(key, 0)
    private fun number(key: String, value: Long) { preferences.edit().putLong(key, value).apply() }
    override var lastAttempt: Long get() = number("attempt"); set(value) = number("attempt", value)
    override var lastSuccess: Long get() = number("success"); set(value) = number("success", value)
    override var rateLimitedUntil: Long get() = number("rateLimit"); set(value) = number("rateLimit", value)
    override var ignoredCode: Long get() = number("ignored"); set(value) = number("ignored", value)
    override var deferredCode: Long get() = number("deferred"); set(value) = number("deferred", value)
    override var deferredUntil: Long get() = number("deferredUntil"); set(value) = number("deferredUntil", value)
    private var cachedTicket = readTicket()
    private fun readTicket(): DownloadTicket? = runCatching {
            val text = preferences.getString("ticket", null) ?: return null
            val root = UpdateContract.json(text.toByteArray())
            val release = root.getAsJsonObject("release")
            val manifest = UpdateContract.parseManifest(release.getAsJsonObject("manifest"))
            val url = release.text("apkUrl")
            require(GitHubUrls.asset(url, manifest.versionName, manifest.apkAssetName))
            val id = root.long("id"); require(id > 0)
            DownloadTicket(id, UpdateRelease(manifest, url, "$RELEASES_PAGE/tag/v${manifest.versionName}"))
        }.getOrNull()
    override var ticket: DownloadTicket?
        get() = cachedTicket
        @android.annotation.SuppressLint("ApplySharedPref") // Persist the system task before publishing its state.
        set(value) {
            val saved = preferences.edit().apply {
                if (value == null) remove("ticket") else putString("ticket", gson.toJson(value))
            }.commit()
            cachedTicket = value
            if (!saved) throw UpdateFailure(UpdateError.STATE_STORAGE)
        }
}

interface WhatsNewStore {
    fun acknowledgedCode(): Long
    fun acknowledge(code: Long)
}
class AtomicWhatsNewStore(directory: File) : WhatsNewStore {
    private val file = AtomicFile(File(directory, "whats-new-version"))
    override fun acknowledgedCode(): Long = runCatching { file.openRead().use { it.readBytes().toString(Charsets.UTF_8).toLong() } }.getOrDefault(0)
    override fun acknowledge(code: Long) {
        val stream = file.startWrite()
        try { stream.write(code.toString().toByteArray()); file.finishWrite(stream) }
        catch (failure: Exception) { file.failWrite(stream); throw failure }
    }
}
class WhatsNewCoordinator(val notes: ReleaseNotes, private val store: WhatsNewStore) {
    private val _pending = MutableStateFlow(store.acknowledgedCode() != notes.versionCode)
    val pending = _pending.asStateFlow()
    fun acknowledge(): Boolean = runCatching {
        store.acknowledge(notes.versionCode)
        _pending.value = false
    }.isSuccess
}
