package com.macroresearch.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.coroutines.coroutineContext

data class UpdateUiState(
    val automatic: Boolean,
    val initialized: Boolean = false,
    val lastSuccess: Long = 0,
    val check: CheckState = CheckState(),
    val download: DownloadState = DownloadState(),
    val downloadRelease: UpdateRelease? = null,
    val prompt: UpdateRelease? = null,
)

class UpdateCoordinator(
    private val checker: UpdateChecker,
    private val store: UpdateStore,
    private val downloader: UpdateDownloader,
    private val scope: CoroutineScope,
    private val installedCode: Long,
    private val sdk: Int,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(UpdateUiState(store.automatic, lastSuccess = store.lastSuccess, downloadRelease = store.ticket?.release))
    val state = _state.asStateFlow()
    private var foreground = false
    private var checkJob: Job? = null
    private data class CheckRequest(var manual: Boolean)
    private var activeCheck: CheckRequest? = null
    private var pollJob: Job? = null
    private var startJob: Job? = null
    private val downloadMutex = Mutex()

    fun setAutomatic(enabled: Boolean) {
        store.automatic = enabled
        _state.update { it.copy(automatic = enabled) }
    }
    fun onForeground() {
        foreground = true
        refreshDownload()
        if (store.automatic && (store.lastAttempt == 0L || now() < store.lastAttempt || now() - store.lastAttempt >= DAY_MILLIS)) check(false)
    }
    fun onBackground() {
        foreground = false
        checkJob?.cancel()
        if (_state.value.check.phase == CheckPhase.CHECKING) _state.update { it.copy(check = CheckState()) }
        pollJob?.cancel()
    }
    fun check(manual: Boolean = true) {
        if (checkJob?.isActive == true) {
            if (manual) activeCheck?.manual = true
            return
        }
        if (now() < store.rateLimitedUntil) {
            _state.update { it.copy(check = CheckState(CheckPhase.FAILED, error = UpdateError.RATE_LIMIT, retryAt = store.rateLimitedUntil)) }
            return
        }
        if (!manual) store.lastAttempt = now()
        val previous = _state.value.check
        val request = CheckRequest(manual)
        activeCheck = request
        _state.update { it.copy(check = CheckState(CheckPhase.CHECKING)) }
        checkJob = scope.launch {
            try {
                val release = checker.latest()
                coroutineContext.ensureActive()
                if (release != null && release.manifest.minSdk > sdk) throw UpdateFailure(UpdateError.UNSUPPORTED_ANDROID)
                store.lastSuccess = now()
                store.rateLimitedUntil = 0
                val phase = when {
                    release == null -> CheckPhase.NO_RELEASE
                    release.manifest.versionCode > installedCode -> CheckPhase.AVAILABLE
                    else -> CheckPhase.CURRENT
                }
                val canPrompt = phase == CheckPhase.AVAILABLE && store.ticket == null &&
                    (request.manual || (release!!.manifest.versionCode != store.ignoredCode &&
                    !(release.manifest.versionCode == store.deferredCode && now() < store.deferredUntil)))
                _state.update { it.copy(lastSuccess = store.lastSuccess, check = CheckState(phase, release), prompt = if (canPrompt) release else null) }
            } catch (cancelled: CancellationException) {
                if (activeCheck === request) _state.update { it.copy(check = previous) }
                throw cancelled
            } catch (failure: Exception) {
                val error = failure as? UpdateFailure ?: UpdateFailure(UpdateError.NETWORK)
                if (error.reason == UpdateError.RATE_LIMIT) store.rateLimitedUntil = error.retryAt
                _state.update { it.copy(check = CheckState(CheckPhase.FAILED, error = error.reason, retryAt = error.retryAt)) }
            } finally { if (activeCheck === request) activeCheck = null }
        }
    }
    fun defer() {
        _state.value.prompt?.let { store.deferredCode = it.manifest.versionCode; store.deferredUntil = now() + DAY_MILLIS }
        _state.update { it.copy(prompt = null) }
    }
    fun ignore() {
        _state.value.prompt?.let { store.ignoredCode = it.manifest.versionCode }
        _state.update { it.copy(prompt = null) }
    }
    fun download(release: UpdateRelease) {
        if (startJob?.isActive == true || _state.value.download.phase in setOf(DownloadPhase.DOWNLOADING, DownloadPhase.PAUSED, DownloadPhase.VERIFYING, DownloadPhase.READY)) return
        _state.update { it.copy(prompt = null, downloadRelease = release, download = DownloadState(DownloadPhase.DOWNLOADING, total = release.manifest.apkSize)) }
        startJob = scope.launch {
            downloadMutex.withLock {
                try {
                    if (release.manifest.versionCode <= installedCode || release.manifest.minSdk > sdk) throw UpdateFailure(UpdateError.VERIFICATION)
                    store.ticket?.let { downloader.remove(it) }
                    store.ticket = null
                    val ticket = DownloadTicket(downloader.start(release), release)
                    try { store.ticket = ticket }
                    catch (failure: Exception) { downloader.remove(ticket); throw failure }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    failDownload(failure)
                }
            }
            if (foreground) refreshDownload()
        }
    }
    fun cancelDownload() {
        pollJob?.cancel()
        scope.launch {
            startJob?.join()
            downloadMutex.withLock {
                try {
                    store.ticket?.let { downloader.remove(it) }
                    store.ticket = null
                    _state.update { it.copy(download = DownloadState(), downloadRelease = null) }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    failDownload(failure)
                }
            }
        }
    }
    private fun refreshDownload() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (foreground) {
                val keepPolling = downloadMutex.withLock {
                    val ticket = store.ticket
                    if (ticket == null) {
                        if (!_state.value.initialized) runCatching { downloader.cleanupInstalled() }.onFailure {
                            if (it is CancellationException) throw it
                            failDownload(it as? Exception ?: UpdateFailure(UpdateError.STORAGE))
                        }
                        _state.update { it.copy(initialized = true) }
                        return@withLock false
                    }
                    _state.update { it.copy(downloadRelease = ticket.release) }
                    try {
                        if (ticket.release.manifest.versionCode <= installedCode) {
                            downloader.remove(ticket); downloader.cleanupInstalled(); forgetTicket()
                            _state.update { it.copy(download = DownloadState(), downloadRelease = null) }
                            return@withLock false
                        }
                        if (_state.value.download.phase == DownloadPhase.READY) {
                            downloader.readyFile(ticket)
                            return@withLock false
                        }
                        val progress = downloader.query(ticket)
                        _state.update { it.copy(download = progress) }
                        if (progress.phase == DownloadPhase.FAILED) {
                            downloader.remove(ticket); forgetTicket()
                            return@withLock false
                        }
                        if (progress.phase == DownloadPhase.VERIFYING) {
                            downloader.verify(ticket)
                            coroutineContext.ensureActive()
                            _state.update { it.copy(download = DownloadState(DownloadPhase.READY, ticket.release.manifest.apkSize, ticket.release.manifest.apkSize)) }
                            return@withLock false
                        }
                        true
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        downloader.remove(ticket); forgetTicket()
                        failDownload(failure)
                        false
                    } finally { if (coroutineContext.isActive) _state.update { it.copy(initialized = true) } }
                }
                if (!keepPolling) break
                delay(1000)
            }
        }
    }
    private fun forgetTicket() {
        try { store.ticket = null }
        catch (failure: Exception) { failDownload(failure) }
    }
    private fun failDownload(failure: Exception) {
        _state.update { it.copy(download = DownloadState(DownloadPhase.FAILED, error = (failure as? UpdateFailure)?.reason ?: UpdateError.DOWNLOAD)) }
    }
    suspend fun fileForInstallation(): File = downloadMutex.withLock {
        try {
            val ticket = store.ticket ?: throw UpdateFailure(UpdateError.MISSING_FILE)
            downloader.readyFile(ticket)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            store.ticket?.let { downloader.remove(it) }; forgetTicket()
            failDownload(failure)
            throw failure
        }
    }
    fun installationFailed() { _state.update { it.copy(download = it.download.copy(error = UpdateError.INSTALL)) } }
}
