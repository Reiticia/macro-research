package com.macroresearch.data.model

import com.macroresearch.data.builtinEventName

data class EconomicEvent(
    val id: Long,
    val provider: String,
    val providerId: String,
    val releaseGroupId: Long?,
    val country: String,
    val currency: String?,
    val category: String,
    val event: String,
    val eventZhCn: String? = null,
    val eventZhTw: String? = null,
    val eventTime: String,
    val importance: Int,
    val actual: String?,
    val previous: String?,
    val consensus: String?,
    val forecast: String?,
    val unit: String?,
    val status: String,
)

data class EventObservation(
    val id: Long,
    val eventId: Long,
    val observedAt: String,
    val actual: String?,
    val previous: String?,
    val consensus: String?,
    val forecast: String?,
)

data class EventDetailResponse(
    val event: EconomicEvent,
    val observations: List<EventObservation>,
)

data class ExpectedReaction(
    val symbol: String,
    val direction: String,
    val rationale: String,
)

data class MarketReaction(
    val eventId: Long,
    val symbol: String,
    val baselinePrice: Double,
    val reactionUnit: String,
    val change1m: Double?,
    val change5m: Double?,
    val change15m: Double?,
    val change30m: Double?,
    val change60m: Double?,
)

data class ReactionComparison(
    val symbol: String,
    val expected: String,
    val observedChange: Double?,
    val conforms: Boolean?,
)

data class AnalysisReport(
    val id: Long,
    val eventId: Long,
    val rawSurprise: String?,
    val macroSignal: String,
    val expectedReactions: List<ExpectedReaction>,
    val observedReactions: List<MarketReaction>,
    val comparisons: List<ReactionComparison>,
    val summary: String,
    val createdAt: String,
    val updatedAt: String,
    val historical: HistoricalEvidence? = null,
)

data class HistoricalEvidence(
    val fetchedAt: String,
    val revisedDataPossible: Boolean,
    val coverage: List<HistoricalCoverage>,
)

data class HistoricalCoverage(
    val symbol: String,
    val source: String,
    val intervalSeconds: Long?,
    val status: String,
    val reason: String?,
    val availableHorizons: List<Int>,
    val baselineTime: String?,
    val sampleTimes: Map<String, String>,
)

data class MarketSnapshot(
    val id: Long,
    val eventId: Long,
    val symbol: String,
    val timestamp: String,
    val price: Double,
    val open: Double?,
    val high: Double?,
    val low: Double?,
    val close: Double?,
    val volume: Double?,
)

data class MarketResponse(
    val snapshots: List<MarketSnapshot>,
    val reactions: List<MarketReaction>,
)

data class LiveMarketQuote(
    val symbol: String,
    val timestamp: String,
    val price: Double,
    val provider: String,
    val changePercent: Double?,
    val high: Double?,
    val low: Double?,
    val marketState: String?,
    val stale: Boolean,
)

data class MarketQuotesResponse(
    val quotes: List<LiveMarketQuote>,
    val unavailable: List<String>,
)

/** One link of the AI-generated transmission chain, e.g. "CPI surprise" → "real yields". */
data class TransmissionStep(
    val from: String,
    val to: String,
    val direction: String,
    val rationale: String,
    /** Set by the comparison pass: confirmed / contradicted / unobserved. */
    val verdict: String? = null,
)

/** AI market analysis for a released event, cached locally and re-runnable. */
/** Token usage a relay reported for the calls behind one briefing. */
data class AiUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    /** Number of model calls; a two-pass method makes two. */
    val calls: Int = 0,
)

data class AiAnalysis(
    val eventId: Long,
    val method: Int,
    val revision: Int,
    val chain: List<TransmissionStep>,
    val dataAnalysis: String,
    val marketOutlook: String,
    val risks: String?,
    val model: String,
    val generatedAt: String,
    /** Tokens the server spent producing this row, when the relay reported them. */
    val usage: AiUsage? = null,
    /** True when no model call happened for this request. */
    val fromCache: Boolean = false,
    /** True when the rate limit served this result instead of a fresh run. */
    val rateLimited: Boolean = false,
    val retryAfterSeconds: Long? = null,
) {
    /** Short audit line for the analysis screen; null when the relay reported nothing. */
    fun usageSummary(): String? {
        val usage = usage ?: return null
        if (usage.calls <= 0) return null
        return if (usage.totalTokens > 0) {
            "${usage.calls} × ${usage.totalTokens} tokens"
        } else {
            "${usage.calls} ×"
        }
    }
}

/** Message pushed by the backend over its WebSocket. */
data class SocketEvent(
    val type: String,
    val eventId: Long? = null,
    val event: String? = null,
    val eventZhCn: String? = null,
    val eventZhTw: String? = null,
    val actual: String? = null,
    val consensus: String? = null,
) {
    /** Event name in the reader's language, falling back to the dictionary then the source. */
    fun localizedName(isTraditionalChinese: Boolean): String? {
        val source = event ?: return null
        return if (isTraditionalChinese) {
            eventZhTw ?: builtinEventName(source, true) ?: eventZhCn ?: source
        } else {
            eventZhCn ?: builtinEventName(source, false) ?: eventZhTw ?: source
        }
    }
}

