package com.macroresearch.data

import com.macroresearch.data.remote.companyLogoSymbol
import com.macroresearch.data.remote.validCompanyLogo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.time.Instant

/** Memory + persistent cache; usable stale logos render immediately while one refresh runs. */
class CompanyLogoLoader(
    private val settings: StateFlow<BackendSettings>,
    private val direct: suspend (String) -> ByteArray?,
    private val backend: suspend (String) -> ByteArray?,
    private val clock: () -> Instant = Instant::now,
    private val diskCache: CompanyLogoDiskCache? = null,
    private val refreshScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val cache = java.util.LinkedHashMap<String, CachedCompanyLogo>(64, 0.75f, true)
    // Fixed stripes avoid an unbounded per-symbol lock map; cached reads never wait on network IO.
    private val locks = Array(32) { Mutex() }
    private val downloads = Semaphore(4)

    suspend fun image(symbol: String): ByteArray? = withContext(Dispatchers.IO) {
        val safe = companyLogoSymbol(symbol) ?: return@withContext null
        val context = settings.value
        val key = key(context, safe)
        val old = cached(key)
        val now = clock()
        if (old != null && usable(old, now)) {
            if (settings.value != context) return@withContext null
            if (now >= old.refreshAfter) {
                refreshScope.launch(Dispatchers.IO) { refresh(key, safe, context) }
            }
            return@withContext old.image
        }
        // Missing-image records also survive restarts, so unavailable logos are not re-fetched.
        if (old != null && old.image == null && now >= old.fetchedAt && now < old.refreshAfter) return@withContext null
        refresh(key, safe, context)
    }

    private suspend fun refresh(key: String, symbol: String, context: BackendSettings): ByteArray? =
        locks[(key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            if (settings.value != context) return@withLock null
            val old = cached(key)
            val now = clock()
            if (old != null && now >= old.fetchedAt && now < old.refreshAfter) return@withLock old.image.takeIf { usable(old, now) }
            val image = try {
                downloads.withPermit {
                    (if (context.mode == DataSourceMode.DIRECT) direct(symbol) else backend(symbol))?.takeIf(::validCompanyLogo)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            if (settings.value != context) return@withLock null
            val fetched = clock()
            val entry = if (image != null) {
                CachedCompanyLogo(image, fetched, fetched.plusSeconds(FRESH_SECONDS))
            } else {
                val stale = old?.takeIf { usable(it, fetched) }
                CachedCompanyLogo(stale?.image, stale?.fetchedAt ?: fetched, fetched.plusSeconds(RETRY_SECONDS))
            }
            remember(key, entry)
            diskCache?.write(key, entry)
            entry.image
        }

    private fun cached(key: String): CachedCompanyLogo? = synchronized(cache) { cache[key] }
        ?: diskCache?.read(key)?.also { remember(key, it) }

    private fun remember(key: String, entry: CachedCompanyLogo) = synchronized(cache) {
        cache[key] = entry
        while (cache.size > 64) cache.remove(cache.keys.first())
    }

    private fun usable(entry: CachedCompanyLogo, now: Instant): Boolean =
        entry.image != null && now >= entry.fetchedAt && now < entry.fetchedAt.plusSeconds(MAX_AGE_SECONDS)

    private fun key(context: BackendSettings, symbol: String): String =
        if (context.mode == DataSourceMode.DIRECT) "direct:$symbol" else "backend:${context.baseUrl}:$symbol"

    private companion object {
        const val FRESH_SECONDS = 7 * 86400L
        const val MAX_AGE_SECONDS = 30 * 86400L
        const val RETRY_SECONDS = 300L
    }
}
