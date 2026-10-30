package com.macroresearch.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Safe operational metadata only: never prompts, credentials, model reasoning or tool arguments. */
enum class AiOperation {
    LOAD_EVENT, LOAD_MARKET, APPLY_RULES, LOAD_NEWS, EXPECTATION, COMPARISON, SINGLE_PASS,
    READ_EVENT, READ_RULES, READ_MARKET, READ_NEWS, COMPATIBILITY, VALIDATE, SAVE, FETCH_NEWS, OPEN_ARTICLE, RESOLVE_ARTICLE,
}

enum class AiStepStatus { RUNNING, SUCCEEDED, FAILED, CANCELLED }
enum class AiStepDetail { NONE, MISSING_DATA, NEWS_DISABLED, PARTIAL_NEWS, NO_NEWS, UNSUPPORTED_TOOLS, INVALID_TOOL, LIMIT_REACHED, PAGE_UNAVAILABLE, REDIRECT, BACKEND_DATA, DIRECT_DATA, INTERFACE_UNAVAILABLE, PAGE_HTTP_ERROR, PAGE_TOO_LARGE, PAGE_NO_TEXT, GOOGLE_RESOLUTION_FAILED }

data class AiExecutionStep(
    val id: Int,
    val operation: AiOperation,
    val status: AiStepStatus = AiStepStatus.RUNNING,
    val durationMs: Long? = null,
    val detail: AiStepDetail = AiStepDetail.NONE,
    val count: Int? = null,
    val url: String? = null,
    val httpStatus: Int? = null,
)

/** One run. Immutable snapshots are delivered for local persistence and real-time UI updates. */
class AiExecutionTrace(private val observer: (List<AiExecutionStep>) -> Unit = {}) {
    private val steps = mutableListOf<AiExecutionStep>()
    private val starts = mutableMapOf<Int, Long>()

    @Synchronized
    fun start(operation: AiOperation, url: String? = null): Int {
        val id = steps.size
        if (id >= 64) return -1
        steps += AiExecutionStep(id, operation, url = url?.let(::traceUrl))
        starts[id] = System.nanoTime()
        observer(steps.toList())
        return id
    }

    @Synchronized
    fun finish(id: Int, status: AiStepStatus = AiStepStatus.SUCCEEDED, detail: AiStepDetail = AiStepDetail.NONE, count: Int? = null, httpStatus: Int? = null) {
        if (id !in steps.indices || steps[id].status != AiStepStatus.RUNNING) return
        val elapsed = starts.remove(id)?.let { (System.nanoTime() - it) / 1_000_000 }
        steps[id] = steps[id].copy(status = status, durationMs = elapsed, detail = detail, count = count, httpStatus = httpStatus)
        observer(steps.toList())
    }

    private fun traceUrl(value: String): String? = value.toHttpUrlOrNull()?.let { url ->
        url.newBuilder().username("").password("").fragment(null).apply {
            for (name in url.queryParameterNames) {
                if (Regex("(?i)token|key|secret|password|auth|signature").containsMatchIn(name))
                    setQueryParameter(name, "[REDACTED]")
            }
        }.build().toString().take(2048)
    }

    @Synchronized
    fun finishRunning(status: AiStepStatus) {
        steps.filter { it.status == AiStepStatus.RUNNING }.map { it.id }.forEach { finish(it, status) }
    }
}
