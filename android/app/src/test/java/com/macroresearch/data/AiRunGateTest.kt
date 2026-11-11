package com.macroresearch.data

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AiRunGateTest {
    @Test fun resetCancelsOnlyConversationAndRejectsCancellationResistantLateWrites() = runBlocking {
        val gate = AiRunGate()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var oldCommitted = false
        val old = async {
            runCatching {
                gate.run("conversation") { run ->
                    entered.complete(Unit)
                    withContext(NonCancellable) {
                        release.await()
                        run.ensureActive()
                        oldCommitted = true
                    }
                }
            }
        }
        entered.await()
        gate.cancel("conversation")
        gate.run("conversation") { it.ensureActive() } // Reset does not leave the old lock held.
        gate.run("briefing") { it.ensureActive() }
        release.complete(Unit)
        assertTrue(old.await().exceptionOrNull() is CancellationException)
        assertFalse(oldCommitted)
    }

    @Test fun sourceSwitchRevokesAllRunsAndOldFinallyCannotRemoveTheirReplacements() = runBlocking {
        val gate = AiRunGate()
        val entered = List(2) { CompletableDeferred<Unit>() }
        val release = CompletableDeferred<Unit>()
        val old = listOf("analysis", "conversation").mapIndexed { i, key ->
            async {
                runCatching { gate.run(key) { run ->
                    entered[i].complete(Unit)
                    withContext(NonCancellable) { release.await(); run.ensureActive() }
                } }
            }
        }
        entered.forEach { it.await() }
        gate.cancelAll()
        val replacementEntered = CompletableDeferred<Unit>()
        val replacementRelease = CompletableDeferred<Unit>()
        val replacement = async {
            gate.run("analysis") { run -> replacementEntered.complete(Unit); replacementRelease.await(); run.ensureActive() }
        }
        replacementEntered.await()
        release.complete(Unit)
        old.forEach { assertTrue(it.await().exceptionOrNull() is CancellationException) }
        assertTrue(runCatching { gate.run("analysis") {} }.exceptionOrNull() is IllegalStateException)
        replacementRelease.complete(Unit)
        replacement.await()
        gate.run("analysis") { it.ensureActive() }
    }
}
