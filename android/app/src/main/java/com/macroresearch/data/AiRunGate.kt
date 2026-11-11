package com.macroresearch.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext

/** Single-flight runs with revocable leases: reset/source changes invalidate even late callbacks. */
class AiRunGate {
    private val runs = mutableMapOf<String, Run>()

    inner class Run internal constructor(internal val key: String, internal val job: Job) {
        val isActive: Boolean get() = synchronized(runs) { runs[key] === this && job.isActive }
        fun ensureActive() {
            if (!isActive) throw CancellationException("AI request was reset or its data source changed")
        }
    }

    suspend fun <T> run(key: String, block: suspend (Run) -> T): T = coroutineScope {
        val run = Run(key, currentCoroutineContext()[Job]!!)
        synchronized(runs) {
            check(key !in runs) { "AI analysis is already running for this event" }
            runs[key] = run
        }
        try {
            block(run).also { run.ensureActive() }
        } finally {
            synchronized(runs) { if (runs[key] === run) runs.remove(key) }
        }
    }

    /** Does not wait for transport shutdown; a new run may start immediately after reset. */
    fun cancel(key: String) {
        val old = synchronized(runs) { runs.remove(key) }
        old?.job?.cancel(CancellationException("AI conversation reset"))
    }

    fun cancelAll() {
        val old = synchronized(runs) { runs.values.toList().also { runs.clear() } }
        old.forEach { it.job.cancel(CancellationException("AI data source changed")) }
    }
}
