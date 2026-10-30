package com.macroresearch.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class AiHistoryStoreTest {
    private fun withStore(block: (java.io.File, AiHistoryStore) -> Unit) {
        val directory = Files.createTempDirectory("ai-history-test").toFile()
        try { block(directory, AiHistoryStore(directory)) } finally { directory.deleteRecursively() }
    }

    @Test fun eventMethodAndConversationHistorySurviveClosingAndRecreatingTheStore() = withStore { directory, store ->
        val key = "analysis|DIRECT||1|2"
        val history = store.begin(key).copy(
            steps = listOf(AiExecutionStep(0, AiOperation.READ_NEWS, AiStepStatus.SUCCEEDED, 10, count = 3)),
            requests = listOf(AiRequestRecord(0, AiRequestPhase.COMPARISON, "2026-10-29T00:00:00Z", "{\"messages\":[]}", AiStepStatus.SUCCEEDED, 200, 20)),
        )
        store.save(key, history)
        store.finish(key, history, AiStepStatus.SUCCEEDED)
        val reopened = AiHistoryStore(directory)
        assertEquals(history, reopened.load(key))
        assertTrue(reopened.load("analysis|DIRECT||1|1").requests.isEmpty())
        assertTrue(reopened.load("analysis|BACKEND|https://example.com|1|2").requests.isEmpty())
        assertTrue(reopened.load("analysis|DIRECT||2|2").steps.isEmpty())
        assertTrue(reopened.load("conversation|DIRECT||1").requests.isEmpty())
        assertTrue(directory.listFiles()!!.none { it.extension == "tmp" })
    }

    @Test fun cancellationFailureAndProcessDeathRetainTheLastActualRequest() = withStore { directory, store ->
        for (terminal in listOf(AiStepStatus.CANCELLED, AiStepStatus.FAILED)) {
            val history = store.begin("event").copy(
                steps = listOf(AiExecutionStep(0, AiOperation.SINGLE_PASS)),
                requests = listOf(AiRequestRecord(0, AiRequestPhase.SINGLE_PASS, "now", "{\"model\":\"fixture\"}")),
            )
            store.save("event", history)
            store.finish("event", history, terminal)
            val restored = AiHistoryStore(directory).load("event")
            assertEquals(terminal, restored.steps.single().status)
            assertEquals(terminal, restored.requests.single().status)
            assertEquals(history.requests.single().requestJson, restored.requests.single().requestJson)
        }
        val running = store.begin("process-death").copy(steps = listOf(AiExecutionStep(0, AiOperation.LOAD_EVENT)))
        store.save("process-death", running)
        assertEquals(AiStepStatus.RUNNING, store.load("process-death").steps.single().status)
        assertEquals(AiStepStatus.CANCELLED, AiHistoryStore(directory).load("process-death").steps.single().status)
    }

    @Test fun newAnalysisReplacesOnlyItsOwnTraceWhileFollowUpsAppend() = withStore { _, store ->
        val old = store.begin("analysis").copy(steps = listOf(AiExecutionStep(0, AiOperation.SAVE, AiStepStatus.SUCCEEDED)))
        store.save("analysis", old)
        val fresh = store.begin("analysis")
        store.save("analysis", old) // A late callback must not overwrite the new session.
        assertEquals(fresh, store.load("analysis"))
        val conversation = store.begin("conversation").copy(requests = listOf(AiRequestRecord(0, AiRequestPhase.FOLLOW_UP, "now", "{}", AiStepStatus.SUCCEEDED)))
        store.save("conversation", conversation)
        val continued = store.begin("conversation", appendRequests = true)
        assertEquals(conversation.requests, continued.requests)
        var records = emptyList<AiRequestRecord>()
        AiRequestLog(continued.requests) { records = it }.start(AiRequestPhase.FOLLOW_UP, "{}", "fixture")
        assertEquals(listOf(0, 1), records.map { it.id })
        store.clear("conversation")
        store.save("conversation", continued)
        assertTrue(store.load("conversation").requests.isEmpty())
        store.clearAll()
        store.save("analysis", fresh)
        assertTrue(store.load("analysis").steps.isEmpty())
    }

    @Test fun corruptFilesDoNotPreventViewingTheEvent() = withStore { directory, store ->
        store.begin("event")
        directory.listFiles()!!.single().writeText("corrupted history")
        assertEquals(AiHistory(), store.load("event"))
    }

    @Test fun localRunsHaveNoCooldownButOverlappingRequestsAreRejected() = runBlocking {
        val gate = AiRunGate()
        var calls = 0
        repeat(3) { gate.run("event") { calls++ } }
        assertEquals(3, calls) // No delay or clock advance between requests.
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = launch { gate.run("event") { entered.complete(Unit); release.await() } }
        entered.await()
        assertTrue(runCatching { gate.run("event") { calls++ } }.isFailure)
        assertEquals(3, calls)
        gate.run("another-event") { calls++ }
        first.cancelAndJoin()
        gate.run("event") { calls++ }
        assertEquals(5, calls)
        assertTrue(runCatching { gate.run("event") { error("fixture failure") } }.isFailure)
        gate.run("event") { calls++ }
        assertEquals(6, calls)
    }
}
