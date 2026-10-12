package com.macroresearch.update

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

private class MemoryStore : UpdateStore {
    override var automatic = true
    override var lastAttempt = 0L
    override var lastSuccess = 0L
    override var rateLimitedUntil = 0L
    override var ignoredCode = 0L
    override var deferredCode = 0L
    override var deferredUntil = 0L
    override var ticket: DownloadTicket? = null
}
private class FakeDownloader : UpdateDownloader {
    var starts = 0
    var removed = 0
    var cleaned = 0
    var progress = DownloadState(DownloadPhase.VERIFYING)
    var verificationError: UpdateError? = null
    override suspend fun start(release: UpdateRelease): Long { starts++; return 1 }
    override suspend fun query(ticket: DownloadTicket) = progress
    override suspend fun verify(ticket: DownloadTicket): File {
        verificationError?.let { throw UpdateFailure(it) }
        return File("verified-test.apk")
    }
    override suspend fun readyFile(ticket: DownloadTicket): File = verify(ticket)
    override suspend fun remove(ticket: DownloadTicket) { removed++ }
    override suspend fun cleanupInstalled() { cleaned++ }
}

class UpdateCoordinatorTest {
    @Test fun firstInstallOldVersionAndCrossVersionUpgradeNeedExplicitAcknowledgement() {
        var acknowledged = 0L
        var writable = true
        val store = object : WhatsNewStore {
            override fun acknowledgedCode() = acknowledged
            override fun acknowledge(code: Long) { if (!writable) error("Disk full"); acknowledged = code }
        }
        assertTrue(WhatsNewCoordinator(notes(), store).pending.value)
        val first = WhatsNewCoordinator(notes(), store)
        writable = false
        assertFalse(first.acknowledge()); assertTrue(first.pending.value)
        writable = true
        assertTrue(first.acknowledge()); assertFalse(first.pending.value)
        assertFalse(WhatsNewCoordinator(notes(), store).pending.value)
        assertTrue(WhatsNewCoordinator(notes(20, "0.20.0"), store).pending.value)
        assertEquals("繁體", first.notes.localized("zh-TW").client.single())
    }
    @Test fun dailyChecksPersistFailuresButManualChecksBypassTheDailyInterval() {
        val store = MemoryStore()
        var time = 100_000L; var calls = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val coordinator = UpdateCoordinator(UpdateChecker { calls++; throw UpdateFailure(UpdateError.NETWORK) }, store, FakeDownloader(), scope, 16, 35) { time }
            coordinator.onForeground(); assertEquals(1, calls)
            assertNull(coordinator.state.value.prompt)
            coordinator.onBackground(); coordinator.onForeground(); assertEquals(1, calls)
            coordinator.check(); assertEquals(2, calls)
            time += DAY_MILLIS
            coordinator.onBackground(); coordinator.onForeground(); assertEquals(3, calls)
        } finally { scope.cancel() }
    }
    @Test fun ignoreAndLaterOnlySuppressAutomaticPromptsAndRateLimitsStillApply() {
        val store = MemoryStore()
        var time = 100_000L; var calls = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val coordinator = UpdateCoordinator(UpdateChecker { calls++; release() }, store, FakeDownloader(), scope, 16, 35) { time }
            coordinator.onForeground(); assertNotNull(coordinator.state.value.prompt)
            coordinator.ignore(); assertNull(coordinator.state.value.prompt)
            time += DAY_MILLIS
            coordinator.onBackground(); coordinator.onForeground(); assertNull(coordinator.state.value.prompt)
            coordinator.check(); assertNotNull(coordinator.state.value.prompt)
            coordinator.defer(); assertEquals(time + DAY_MILLIS, store.deferredUntil)
            store.rateLimitedUntil = time + 60_000
            val previous = calls
            coordinator.check(); assertEquals(previous, calls)
            assertEquals(UpdateError.RATE_LIMIT, coordinator.state.value.check.error)
        } finally { scope.cancel() }
    }
    @Test fun concurrentChecksAreSingleFlightAndBackgroundCancellationRestoresState() = runBlocking {
        val gate = CompletableDeferred<UpdateRelease?>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var calls = 0
        try {
            val coordinator = UpdateCoordinator(UpdateChecker { calls++; gate.await() }, MemoryStore(), FakeDownloader(), scope, 16, 35) { 100_000 }
            coordinator.onForeground(); coordinator.check(); assertEquals(1, calls)
            gate.complete(release()); yield()
            assertEquals(CheckPhase.AVAILABLE, coordinator.state.value.check.phase)
            val nextGate = CompletableDeferred<UpdateRelease?>()
            val second = UpdateCoordinator(UpdateChecker { nextGate.await() }, MemoryStore(), FakeDownloader(), scope, 16, 35) { 100_000 }
            second.onForeground(); second.onBackground(); yield()
            assertEquals(CheckPhase.IDLE, second.state.value.check.phase)
        } finally { scope.cancel() }
    }
    @Test fun downloadsAreDeduplicatedRestoredVerifiedAndNeverAutomaticallyInstalled() = runBlocking {
        val store = MemoryStore().apply { automatic = false }
        val downloads = FakeDownloader()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val coordinator = UpdateCoordinator(UpdateChecker { release() }, store, downloads, scope, 16, 35)
            coordinator.onForeground(); coordinator.download(release()); coordinator.download(release())
            assertEquals(1, downloads.starts)
            assertEquals(DownloadPhase.READY, coordinator.state.value.download.phase)
            coordinator.onBackground()
            val restarted = UpdateCoordinator(UpdateChecker { null }, store, downloads, scope, 16, 35)
            restarted.onForeground()
            assertEquals(DownloadPhase.READY, restarted.state.value.download.phase)
            assertEquals(File("verified-test.apk"), restarted.fileForInstallation())
            restarted.cancelDownload(); assertNull(store.ticket)
            assertEquals(DownloadPhase.IDLE, restarted.state.value.download.phase)
        } finally { scope.cancel() }
    }
    @Test fun failedVerificationOrMissingFileRemovesTicketAndBlocksInstallation() = runBlocking {
        val store = MemoryStore().apply { automatic = false; ticket = DownloadTicket(1, release()) }
        val downloads = FakeDownloader().apply { verificationError = UpdateError.SIGNATURE }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val coordinator = UpdateCoordinator(UpdateChecker { null }, store, downloads, scope, 16, 35)
            coordinator.onForeground()
            assertEquals(UpdateError.SIGNATURE, coordinator.state.value.download.error)
            assertNull(store.ticket)
            try { coordinator.fileForInstallation(); fail("Missing file accepted") } catch (_: UpdateFailure) { }
            assertEquals(UpdateError.MISSING_FILE, coordinator.state.value.download.error)
        } finally { scope.cancel() }
    }
    @Test fun systemPauseRestoresAndCancellationRemovesTheTask() {
        val store = MemoryStore().apply { automatic = false; ticket = DownloadTicket(1, release()) }
        val downloads = FakeDownloader().apply { progress = DownloadState(DownloadPhase.PAUSED, 1, 3, systemReason = 2) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val coordinator = UpdateCoordinator(UpdateChecker { null }, store, downloads, scope, 16, 35)
            coordinator.onForeground()
            assertEquals(DownloadPhase.PAUSED, coordinator.state.value.download.phase)
            assertEquals(2, coordinator.state.value.download.systemReason)
            coordinator.onBackground(); coordinator.onForeground()
            coordinator.cancelDownload()
            assertNull(store.ticket); assertEquals(1, downloads.removed)
        } finally { scope.cancel() }
    }
    @Test fun cachedFileLossAndUnsupportedSdkBlockInstallation() = runBlocking {
        val store = MemoryStore().apply { automatic = false; ticket = DownloadTicket(1, release()) }
        val downloads = FakeDownloader()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val coordinator = UpdateCoordinator(UpdateChecker { release().copy(manifest = release().manifest.copy(minSdk = 36)) }, store, downloads, scope, 16, 35)
            coordinator.onForeground(); assertEquals(DownloadPhase.READY, coordinator.state.value.download.phase)
            downloads.verificationError = UpdateError.MISSING_FILE
            try { coordinator.fileForInstallation(); fail("Removed cache file accepted") } catch (_: UpdateFailure) { }
            assertNull(store.ticket)
            coordinator.check()
            assertEquals(UpdateError.UNSUPPORTED_ANDROID, coordinator.state.value.check.error)
            assertNull(coordinator.state.value.prompt)
        } finally { scope.cancel() }
    }
    @Test fun upgradingCleansStaleDownloadBeforeStartupIsReady() {
        val store = MemoryStore().apply { automatic = false; ticket = DownloadTicket(1, release(16, "0.14.0")) }
        val downloads = FakeDownloader()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val coordinator = UpdateCoordinator(UpdateChecker { null }, store, downloads, scope, 16, 35)
            assertFalse(coordinator.state.value.initialized)
            coordinator.onForeground()
            assertTrue(coordinator.state.value.initialized)
            assertNull(store.ticket)
            assertEquals(1, downloads.removed); assertEquals(1, downloads.cleaned)
        } finally { scope.cancel() }
    }
}
