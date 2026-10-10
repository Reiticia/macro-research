package com.macroresearch.data.remote

import com.google.gson.Gson
import com.macroresearch.data.BackendDataSource
import com.macroresearch.data.BackendDeviceIdentity
import kotlinx.coroutines.runBlocking
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class BackendConnectionVerificationTest {
    private lateinit var server: MockWebServer
    private val identity = BackendDeviceIdentity("11111111-1111-4111-8111-111111111111", "a".repeat(64))
    private var identityReads = 0

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun source(
        token: () -> String? = { "test-key" },
        cache: Cache? = null,
    ) = BackendDataSource(BackendClient(
        OkHttpClient.Builder().cache(cache).callTimeout(5, TimeUnit.SECONDS).build(),
        Gson(), { server.url("/").toString() }, token,
        { identityReads++; identity },
    ))

    private fun meta(api: Int = 1, ai: Boolean = false) = MockResponse().setBody(
        """{"name":"macro-research","version":"legacy-software-version","apiVersion":$api,"capabilities":[],"aiEnabled":$ai}""",
    )
    private fun denied(code: Int = 401) = MockResponse().setResponseCode(code).setBody(
        """{"error":{"code":"unauthorized","message":"unauthorized"}}""",
    )
    private fun status() = MockResponse().setBody("""{"sources":[],"usage":[]}""")

    @Test fun successRequiresLiveProtectedAccessAndAnonymousProbeCannotBindADevice() = runBlocking {
        server.enqueue(meta()); server.enqueue(denied()); server.enqueue(status())
        val result = source().verifyConnection(1)
        assertFalse(result.aiEnabled)
        val publicMeta = server.takeRequest()
        val probe = server.takeRequest()
        val authenticated = server.takeRequest()
        assertEquals("/api/v1/meta", publicMeta.path)
        assertEquals("/api/v1/status", probe.path)
        assertEquals("/api/v1/status", authenticated.path)
        for (request in listOf(publicMeta, probe)) {
            assertNull(request.getHeader("authorization"))
            assertNull(request.getHeader("X-Installation-Id"))
            assertNull(request.getHeader("X-Android-Id-Hash"))
        }
        assertEquals("Bearer test-key", authenticated.getHeader("authorization"))
        assertEquals(identity.installationId, authenticated.getHeader("X-Installation-Id"))
        assertEquals(identity.androidIdHash, authenticated.getHeader("X-Android-Id-Hash"))
        assertEquals(1, identityReads)
        for (request in listOf(publicMeta, probe, authenticated)) {
            assertTrue(request.getHeader("Cache-Control")!!.contains("no-cache"))
            assertTrue(request.getHeader("Cache-Control")!!.contains("no-store"))
        }
    }

    @Test fun successfulVerificationReturnsAiStateWithoutVersionOrModelGeneration() = runBlocking {
        for (enabled in listOf(true, false)) {
            server.enqueue(meta(ai = enabled)); server.enqueue(denied()); server.enqueue(status())
            val result = source().verifyConnection(1)
            assertEquals(enabled, result.aiEnabled)
            assertFalse(result.toString().contains("legacy-software-version"))
            assertEquals("/api/v1/meta", server.takeRequest().path)
            assertEquals("/api/v1/status", server.takeRequest().path)
            assertEquals("/api/v1/status", server.takeRequest().path)
        }
        assertEquals(6, server.requestCount)
    }

    @Test fun successfulPublicMetaDoesNotMakeRejectedKeysPass() = runBlocking {
        for (code in listOf(401, 403)) {
            server.enqueue(meta(ai = true)); server.enqueue(denied()); server.enqueue(denied(code))
            val failure = runCatching { source(token = { "obsolete-key" }).verifyConnection(1) }.exceptionOrNull()
            assertTrue(failure is BackendUnauthorizedException)
        }
        assertEquals(6, server.requestCount)
    }

    @Test fun openAuthenticationGateIsReportedInsteadOfPretendingAnyKeyIsValid() = runBlocking {
        server.enqueue(meta()); server.enqueue(status())
        val failure = runCatching { source().verifyConnection(1) }.exceptionOrNull()
        assertTrue(failure is BackendAuthenticationDisabledException)
        assertEquals(2, server.requestCount)
        assertEquals(0, identityReads)
    }

    @Test fun incompatibleProtocolStopsBeforeAuthorizationOrDeviceBinding() = runBlocking {
        server.enqueue(meta(99))
        assertTrue(runCatching { source().verifyConnection(1) }.exceptionOrNull() is BackendProtocolException)
        assertEquals(1, server.requestCount)
        assertEquals(0, identityReads)
    }

    @Test fun missingKeyNeverPassesThePublicHandshake() = runBlocking {
        server.enqueue(meta()); server.enqueue(denied())
        assertTrue(runCatching { source(token = { null }).verifyConnection(1) }.exceptionOrNull() is IllegalStateException)
        assertEquals(2, server.requestCount)
        assertEquals(0, identityReads)
    }

    @Test fun serverErrorOnProbeOrAuthorizedStatusIsNotVerificationSuccess() = runBlocking {
        server.enqueue(meta()); server.enqueue(MockResponse().setResponseCode(503))
        assertTrue(runCatching { source().verifyConnection(1) }.exceptionOrNull() is BackendUnavailableException)
        server.enqueue(meta()); server.enqueue(denied()); server.enqueue(MockResponse().setResponseCode(503))
        assertTrue(runCatching { source().verifyConnection(1) }.exceptionOrNull() is BackendUnavailableException)
        assertEquals(5, server.requestCount)
    }

    @Test fun revokedOrChangedKeyCannotReuseCachedHandshakeAndAuthorizedStatus() = runBlocking {
        val directory = Files.createTempDirectory("backend-verification-cache").toFile()
        val cache = Cache(directory, 1024 * 1024)
        try {
            var key = "initial-key"
            val source = source(token = { key }, cache = cache)
            server.enqueue(meta().setHeader("Cache-Control", "public, max-age=3600"))
            server.enqueue(denied())
            server.enqueue(status().setHeader("Cache-Control", "public, max-age=3600"))
            source.verifyConnection(1)
            key = "changed-or-revoked-key"
            server.enqueue(meta().setHeader("Cache-Control", "public, max-age=3600"))
            server.enqueue(denied()); server.enqueue(denied())
            assertTrue(runCatching { source.verifyConnection(1) }.exceptionOrNull() is BackendUnauthorizedException)
            assertEquals(6, server.requestCount)
            repeat(5) { server.takeRequest() }
            assertEquals("Bearer $key", server.takeRequest().getHeader("authorization"))
            assertEquals(0, cache.hitCount())
            assertEquals(0, cache.writeSuccessCount())
        } finally { cache.close(); directory.deleteRecursively() }
    }

    @Test fun protocolChangesAreReadFromNetworkRatherThanACachedMeta() = runBlocking {
        val directory = Files.createTempDirectory("backend-meta-cache").toFile()
        val cache = Cache(directory, 1024 * 1024)
        try {
            val source = source(cache = cache)
            server.enqueue(meta().setHeader("Cache-Control", "public, max-age=3600"))
            server.enqueue(denied()); server.enqueue(status())
            source.verifyConnection(1)
            server.enqueue(meta(99))
            assertTrue(runCatching { source.verifyConnection(1) }.exceptionOrNull() is BackendProtocolException)
            assertEquals(4, server.requestCount)
        } finally { cache.close(); directory.deleteRecursively() }
    }
}
