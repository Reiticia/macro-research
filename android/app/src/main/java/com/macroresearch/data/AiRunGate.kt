package com.macroresearch.data

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

/** Blocks only overlapping calls, never imposes a time-based cooldown or queues duplicate work. */
class AiRunGate {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> run(key: String, block: suspend () -> T): T {
        val lock = locks.getOrPut(key) { Mutex() }
        check(lock.tryLock()) { "AI analysis is already running for this event" }
        return try { block() } finally { lock.unlock() }
    }
}
