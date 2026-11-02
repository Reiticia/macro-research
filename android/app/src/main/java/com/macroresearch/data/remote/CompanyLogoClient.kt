package com.macroresearch.data.remote

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.Proxy
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

internal const val MAX_LOGO_BYTES = 256 * 1024
internal fun companyLogoSymbol(symbol: String): String? = symbol.takeIf { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9.-]{0,31}")) }?.uppercase(Locale.ROOT)

internal fun validCompanyLogo(bytes: ByteArray): Boolean {
    if (bytes.size !in 24..MAX_LOGO_BYTES || !bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(-119,80,78,71,13,10,26,10)) || bytes.copyOfRange(12,16).toString(Charsets.US_ASCII) != "IHDR") return false
    val dimensions = java.nio.ByteBuffer.wrap(bytes,16,8)
    return dimensions.int in 1..1024 && dimensions.int in 1..1024
}

/** Bounded PNG-only reader; JSON/HTML error responses must never reach the image decoder. */
internal fun readCompanyLogo(response: Response): ByteArray? {
    val body = response.body ?: return null
    if (!response.isSuccessful || !response.header("Content-Type").orEmpty().substringBefore(';').trim().equals("image/png",true) || body.contentLength() > MAX_LOGO_BYTES) return null
    val output = java.io.ByteArrayOutputStream()
    body.byteStream().use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > MAX_LOGO_BYTES) return null
            output.write(buffer,0,count)
        }
    }
    return output.toByteArray().takeIf(::validCompanyLogo)
}

class CompanyLogoClient(
    private val client: OkHttpClient,
    private val proxy: () -> Proxy? = { null },
    private val base: String = "https://images.financialmodelingprep.com/symbol",
    private val clock: () -> Instant = Instant::now,
) {
    @Volatile private var retryAt: Instant = Instant.MIN

    suspend fun image(symbol: String): ByteArray? {
        val safe = companyLogoSymbol(symbol) ?: return null
        if (clock() < retryAt) return null
        // Dedicated public request: never use backend authentication / installation headers.
        val http = (proxy()?.let { client.newBuilder().proxy(it) } ?: client.newBuilder())
            .callTimeout(10,java.util.concurrent.TimeUnit.SECONDS).build()
        val request = Request.Builder().url("${base.trimEnd('/')}/$safe.png").header("Accept","image/png").build()
        return http.newCall(request).awaitResponse { response ->
            if (response.code == 403 || response.code == 429) {
                val value = response.header("Retry-After")
                val seconds = (value?.toLongOrNull() ?: value?.let { runCatching {
                    java.time.Duration.between(clock(),ZonedDateTime.parse(it,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).seconds
                }.getOrNull() } ?: 300).coerceIn(60,86400)
                retryAt = clock().plusSeconds(seconds)
            }
            readCompanyLogo(response)
        }
    }
}
