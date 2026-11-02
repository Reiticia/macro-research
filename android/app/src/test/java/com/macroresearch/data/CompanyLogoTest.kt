package com.macroresearch.data

import com.google.gson.Gson
import com.macroresearch.data.remote.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class CompanyLogoTest {
    private val png get() = javaClass.getResourceAsStream("/earnings/logo.png")!!.use { it.readBytes() }
    private val now = Instant.parse("2026-11-01T12:00:00Z")
    private fun response() = MockResponse().setHeader("Content-Type","image/png").setBody(Buffer().write(png))

    @Test fun pathValidationPreservesShareClassesWithoutGuessing() {
        assertEquals("BRK.B",companyLogoSymbol("brk.b"))
        assertEquals("BRK-B",companyLogoSymbol("BRK-B"))
        for (symbol in listOf("", "..", "../AAPL", "AAPL?key=x", "https://evil", "中", "AAPL%2fX")) assertNull(companyLogoSymbol(symbol))
    }

    @Test fun pngHeadersAreBoundedBeforeBitmapDecoding() {
        assertTrue(validCompanyLogo(png))
        assertFalse(validCompanyLogo("<html>blocked</html>".toByteArray()))
        val giant=png.copyOf();java.nio.ByteBuffer.wrap(giant,16,4).putInt(10000)
        assertFalse(validCompanyLogo(giant)); assertFalse(validCompanyLogo(ByteArray(MAX_LOGO_BYTES+1)))
    }

    @Test fun defaultPublicHostReceivesNoCredentialsOrDeviceIdentity() = runBlocking {
        val http=OkHttpClient.Builder().addInterceptor { chain ->
            val request=chain.request()
            assertEquals("images.financialmodelingprep.com",request.url.host)
            assertEquals("/symbol/BRK.B.png",request.url.encodedPath)
            assertNull(request.header("Authorization"));assertNull(request.header("X-Installation-Id"));assertNull(request.header("X-Android-Id-Hash"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").header("Content-Type","image/png").body(png.toResponseBody()).build()
        }.build()
        assertArrayEquals(png,CompanyLogoClient(http).image("brk.b"))
    }

    @Test fun invalidResponsesFailQuietlyAndOversizeChunkedBodiesAreBounded() = runBlocking {
        MockWebServer().use { server ->
            val client=CompanyLogoClient(OkHttpClient(),base=server.url("/symbol").toString())
            for (reply in listOf(
                MockResponse().setResponseCode(404),
                MockResponse().setHeader("Content-Type","text/html").setBody("blocked"),
                MockResponse().setHeader("Content-Type","image/png").setBody("not a png"),
                MockResponse().setHeader("Content-Type","image/png").setChunkedBody(Buffer().write(ByteArray(MAX_LOGO_BYTES+1)),8192),
            )) { server.enqueue(reply);assertNull(client.image("AAPL")) }
            assertNull(client.image("../AAPL"));assertEquals(4,server.requestCount)
        }
    }

    @Test fun upstreamRateLimitSuppressesOtherSymbolsToo() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After","900"))
            val client=CompanyLogoClient(OkHttpClient(),base=server.url("/symbol").toString(),clock={now})
            assertNull(client.image("AAPL"));assertNull(client.image("MSFT"));assertEquals(1,server.requestCount)
        }
    }

    @Test fun backendImageRequestsKeepExistingAuthenticationAndNeverUsePublicHost() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(response())
            val backend=BackendClient(OkHttpClient(),Gson(),{server.url("/").toString().trimEnd('/')},{"private-key"},{BackendDeviceIdentity("installation","android-hash")})
            assertArrayEquals(png,backend.companyLogo("AAPL"))
            val request=server.takeRequest()
            assertEquals("/api/v1/earnings/logo/AAPL",request.requestUrl!!.encodedPath)
            assertEquals("image/png",request.getHeader("Accept"))
            assertEquals("Bearer private-key",request.getHeader("Authorization"))
            assertEquals("installation",request.getHeader("X-Installation-Id"))
            assertTrue(request.getHeader("Cache-Control")!!.contains("no-store"))
            server.enqueue(MockResponse().setResponseCode(404));assertNull(backend.companyLogo("MSFT"))
        }
    }

    @Test fun cacheCoalescesAndSeparatesModesAndBackendAddresses() = runBlocking {
        val settings=MutableStateFlow(BackendSettings());var direct=0;var backend=0
        val loader=CompanyLogoLoader(settings,{direct++;png},{backend++;png},clock={now})
        val (a,b)=listOf(async {loader.image("aapl")},async {loader.image("AAPL")}).map {it.await()}
        assertArrayEquals(png,a);assertArrayEquals(png,b);assertEquals(1,direct)
        settings.value=BackendSettings(mode=DataSourceMode.BACKEND,baseUrl="https://one.example")
        loader.image("AAPL");loader.image("AAPL");assertEquals(1,backend)
        settings.value=settings.value.copy(baseUrl="https://two.example")
        loader.image("AAPL");assertEquals(2,backend);assertEquals(1,direct)
    }

    @Test fun failedImagesAreNegativeCachedAndDoNotFallBackAcrossModes() = runBlocking {
        var instant=now;var calls=0
        val loader=CompanyLogoLoader(MutableStateFlow(BackendSettings(mode=DataSourceMode.BACKEND)),{error("No public fallback")},{calls++;if(calls==1)null else png},clock={instant})
        assertNull(loader.image("AAPL"));assertNull(loader.image("AAPL"));assertEquals(1,calls)
        instant=now.plusSeconds(301);assertArrayEquals(png,loader.image("AAPL"));assertEquals(2,calls)
        loader.image("AAPL");assertEquals(2,calls)
    }

    @Test fun lateImagesFromAnOldSourceAreDiscarded() = runBlocking {
        val settings=MutableStateFlow(BackendSettings());val started=CompletableDeferred<Unit>();val finish=CompletableDeferred<Unit>()
        val loader=CompanyLogoLoader(settings,{started.complete(Unit);finish.await();png},{png},clock={now})
        val pending=async {loader.image("AAPL")}
        started.await();settings.value=BackendSettings(mode=DataSourceMode.BACKEND,baseUrl="https://new.example");finish.complete(Unit)
        assertNull(pending.await());assertArrayEquals(png,loader.image("AAPL"))
    }
}
