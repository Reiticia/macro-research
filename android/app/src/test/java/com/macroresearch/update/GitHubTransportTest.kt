package com.macroresearch.update

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.*
import org.junit.Test

class GitHubTransportTest {
    private fun response(code: Int, body: ResponseBody = "[]".toResponseBody(), headers: Map<String, String> = emptyMap()): Response =
        Response.Builder().request(Request.Builder().url("https://api.github.com/repos/$UPDATE_REPOSITORY/releases").build())
            .protocol(Protocol.HTTP_1_1).code(code).message("test").body(body).apply { headers.forEach { (key, value) -> header(key, value) } }.build()
    @Test fun rateLimitIncludesRetryTimeAndNonRateLimitFailuresAreNotSuccess() {
        val transport = GitHubTransport { 100_000 }
        response(429, headers = mapOf("Retry-After" to "60")).use {
            val error = assertThrows(UpdateFailure::class.java) { transport.readResponse(it) }
            assertEquals(UpdateError.RATE_LIMIT, error.reason); assertEquals(160_000, error.retryAt)
        }
        response(403, headers = mapOf("X-RateLimit-Remaining" to "0", "X-RateLimit-Reset" to "200")).use {
            assertEquals(200_000, assertThrows(UpdateFailure::class.java) { transport.readResponse(it) }.retryAt)
        }
        response(403, headers = mapOf("Retry-After" to "30", "X-RateLimit-Remaining" to "10")).use {
            assertEquals(130_000, assertThrows(UpdateFailure::class.java) { transport.readResponse(it) }.retryAt)
        }
        for (code in listOf(404, 500, 403)) response(code).use {
            assertEquals(UpdateError.NETWORK, assertThrows(UpdateFailure::class.java) { transport.readResponse(it) }.reason)
        }
    }
    @Test fun advertisedAndStreamingResponseSizesAreBounded() {
        val transport = GitHubTransport()
        response(200, ByteArray(1024 * 1024 + 1).toResponseBody()).use {
            assertEquals(UpdateError.INVALID_RELEASE, assertThrows(UpdateFailure::class.java) { transport.readResponse(it) }.reason)
        }
        val streaming = object : ResponseBody() {
            override fun contentType() = "application/json".toMediaType()
            override fun contentLength() = -1L
            override fun source(): BufferedSource = Buffer().write(ByteArray(1024 * 1024 + 1))
        }
        response(200, streaming).use {
            assertThrows(UpdateFailure::class.java) { transport.readResponse(it) }
        }
        response(200).use { assertArrayEquals("[]".toByteArray(), transport.readResponse(it)) }
    }
}
