package com.macroresearch.data

import com.macroresearch.data.remote.companyLogoSymbol
import com.macroresearch.data.remote.validCompanyLogo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant

/** Optional decoration. A logo failure never fails a calendar or falls back across data modes. */
class CompanyLogoLoader(
    private val settings: StateFlow<BackendSettings>,
    private val direct: suspend (String) -> ByteArray?,
    private val backend: suspend (String) -> ByteArray?,
    private val clock: () -> Instant = Instant::now,
) {
    private data class Entry(val image: ByteArray?, val expires: Instant)
    private val cache = java.util.LinkedHashMap<String, Entry>(64,0.75f,true)
    private val mutex = Mutex()

    suspend fun image(symbol: String): ByteArray? = withContext(Dispatchers.IO) {
        val safe = companyLogoSymbol(symbol) ?: return@withContext null
        mutex.withLock {
            val context = settings.value
            val key = "${context.mode}:${context.baseUrl}:$safe"
            val old = cache[key]
            if (old != null && clock() < old.expires) return@withLock old.image
            val image = try {
                (if (context.mode == DataSourceMode.DIRECT) direct(safe) else backend(safe))?.takeIf(::validCompanyLogo)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            if (settings.value != context) return@withLock null
            if (cache.size >= 64 && key !in cache) cache.remove(cache.keys.first())
            cache[key] = Entry(image ?: old?.image,clock().plusSeconds(if (image != null) 86400 else 300))
            image ?: old?.image
        }
    }
}
