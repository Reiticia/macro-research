package com.macroresearch.data

import com.macroresearch.data.model.*
import com.macroresearch.data.remote.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.io.IOException

class AiEvidenceLoaderTest {
    private val local = EconomicEvent(77, "trading_view", "tv-release", null, "United States", "USD", "rate", "Fed Interest Rate Decision", eventTime = "2026-10-28T18:00:00Z", importance = 3, actual = null, previous = "4", consensus = "4", forecast = "4", unit = "%", status = "completed")
    private val remote = local.copy(id = 586731, actual = "4")
    private val windows = MarketResponse(emptyList(), listOf(MarketReaction(586731, "dxy", 102.3285, "percent", -0.02, -0.035, 0.026, 0.015, -0.133)))
    private val report = LocalAnalysisEngine().analyze(remote, emptyList())

    private fun source(handler: (String, Array<out Any?>) -> Any?): DataSource = Proxy.newProxyInstance(
        DataSource::class.java.classLoader, arrayOf(DataSource::class.java),
    ) { _, method, args ->
        try { handler(method.name, args ?: emptyArray()) } catch (error: Exception) {
            @Suppress("UNCHECKED_CAST")
            val continuation = args?.lastOrNull() as? kotlin.coroutines.Continuation<Any?> ?: throw error
            continuation.resumeWith(Result.failure(error))
            kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
        }
    } as DataSource

    private fun backend(calls: MutableList<String>, capabilities: List<String> = listOf("calendar", "market", "analysis"), fail: Pair<String, Exception>? = null): DataSource = source { name, args ->
        calls += name
        if (fail?.first == name) throw fail.second
        when (name) {
            "meta" -> BackendMeta("fixture", 1, false, capabilities)
            "serverEventId" -> { assertEquals(local.id, (args[0] as EconomicEvent).id); remote.id }
            "eventDetail" -> { assertEquals(remote.id, args[0]); EventDetailResponse(remote, emptyList()) }
            "eventMarket" -> { assertEquals(remote.id, (args[0] as EconomicEvent).id); windows }
            "ruleAnalysis" -> { assertEquals(remote.id, (args[0] as EconomicEvent).id); report }
            else -> error("Unexpected backend call: $name")
        }
    }

    @Test fun configuredAddressUsesBackendEvenInDirectModeAndRemapsIds() = runBlocking {
        val calls = mutableListOf<String>()
        var steps = emptyList<AiExecutionStep>()
        val loader = AiEvidenceLoader(source { name, _ -> error("Public provider must not be called: $name") }, backend(calls), "https://backend.example", false, AiExecutionTrace { steps = it })
        val event = loader.event(local)
        assertEquals("4", event.actual)
        assertEquals(local.id, event.id)
        val market = loader.market(event)
        val result = loader.report(event, market)
        assertEquals(local.id, market.reactions.single().eventId)
        assertEquals(-0.133, market.reactions.single().change60m!!, 0.00001)
        assertEquals(market.reactions, result.observedReactions)
        assertEquals(local.id, result.eventId)
        assertEquals(1, calls.count { it == "meta" })
        assertEquals(1, calls.count { it == "serverEventId" })
        assertEquals(1, calls.count { it == "eventDetail" })
        assertTrue(steps.all { it.detail == AiStepDetail.BACKEND_DATA })
    }

    @Test fun backendModeUsesBackendIdWithoutProviderLookup() = runBlocking {
        val calls = mutableListOf<String>()
        val loader = AiEvidenceLoader(source { _, _ -> error("No public requests") }, backend(calls), "https://backend.example", true)
        loader.event(remote)
        loader.market(remote)
        assertFalse(calls.contains("serverEventId"))
    }

    @Test fun missingDataOutageAuthAndRateLimitNeverTriggerDirectFallback() = runBlocking {
        for (failure in listOf(BackendException(404, "not_found", "missing event"), BackendException(429, "quota_exceeded", "limited"), BackendUnavailableException("offline"), BackendUnauthorizedException("invalid key"), IOException("network"))) {
            for (operation in listOf("meta", "eventDetail", "eventMarket", "ruleAnalysis")) {
                val loader = AiEvidenceLoader(source { _, _ -> fail("Must not contact public providers"); null }, backend(mutableListOf(), fail = operation to failure), "https://backend.example", false)
                val attempt = runCatching { val event = loader.event(local); val market = loader.market(event); loader.report(event, market) }
                assertTrue("$operation $failure", attempt.isFailure)
            }
        }
    }

    @Test fun missingCapabilityAllowsOnlyThatDataClassToUseDirectProvider() = runBlocking {
        val calls = mutableListOf<String>()
        var directMarket = 0
        val direct = source { name, _ -> assertEquals("eventMarket", name); directMarket++; windows }
        val loader = AiEvidenceLoader(direct, backend(calls, listOf("calendar", "analysis")), "https://backend.example", false)
        val event = loader.event(local)
        val market = loader.market(event)
        loader.report(event, market)
        assertEquals(1, directMarket)
        assertFalse(calls.contains("eventMarket"))
        assertTrue(calls.contains("ruleAnalysis"))
    }

    @Test fun noBackendUsesDirectWhileEmptyLegacyCapabilitiesStillTryBackend() = runBlocking {
        var directCalls = 0
        val loader = AiEvidenceLoader(source { name, _ -> assertEquals("eventMarket", name); directCalls++; windows }, source { _, _ -> error("No backend configured") }, "", false)
        assertEquals(local, loader.event(local))
        loader.market(local)
        assertEquals(1, directCalls)
        val calls = mutableListOf<String>()
        AiEvidenceLoader(source { _, _ -> error("No public requests") }, backend(calls, emptyList()), "https://backend.example", false).market(local)
        assertTrue(calls.contains("eventMarket"))
    }

    @Test fun explicitNotImplementedAllowsFallbackButGeneric501DoesNot() = runBlocking {
        for (code in listOf("not_implemented", "other_error")) {
            var directCalls = 0
            val loader = AiEvidenceLoader(source { _, _ -> directCalls++; windows }, backend(mutableListOf(), fail = "eventMarket" to BackendException(501, code, "fixture")), "https://backend.example", false)
            val result = runCatching { loader.market(local) }
            assertEquals(code == "not_implemented", result.isSuccess)
            assertEquals(if (code == "not_implemented") 1 else 0, directCalls)
        }
    }
}
