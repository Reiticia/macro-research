package com.macroresearch.data

import com.macroresearch.data.model.AnalysisReport
import com.macroresearch.data.model.EconomicEvent
import com.macroresearch.data.model.MarketResponse
import com.macroresearch.data.remote.BackendException
import kotlinx.coroutines.CancellationException

/** Personal model credentials never decide the data plane. A configured backend is authoritative. */
internal class AiEvidenceLoader(
    private val direct: DataSource,
    private val backend: DataSource,
    private val backendAddress: String,
    private val backendIds: Boolean,
    private val trace: AiExecutionTrace? = null,
) {
    private var capabilities: List<String>? = null
    private var serverEvent: EconomicEvent? = null

    private suspend fun supported(capability: String): Boolean {
        if (backendAddress.isBlank()) return false
        val known = capabilities ?: backend.meta().capabilities.also { capabilities = it }
        // An empty legacy list does not prove the absence of any endpoint: try the request.
        return known.isEmpty() || capability in known
    }

    private suspend fun remoteEvent(local: EconomicEvent): EconomicEvent {
        serverEvent?.let { return it }
        val id = if (backendIds) local.id else backend.serverEventId(local)
        return checkNotNull(backend.eventDetail(id)) { "Backend event details are unavailable" }
            .event.also { serverEvent = it }
    }

    suspend fun event(local: EconomicEvent): EconomicEvent = read(AiOperation.LOAD_EVENT, "calendar", {
        remoteEvent(local).copy(id = local.id)
    }, { local })

    suspend fun market(local: EconomicEvent): MarketResponse = read(AiOperation.LOAD_MARKET, "market", {
        backend.eventMarket(remoteEvent(local)).let { response -> response.copy(
            snapshots = response.snapshots.map { it.copy(eventId = local.id) },
            reactions = response.reactions.map { it.copy(eventId = local.id) },
        ) }
    }, { direct.eventMarket(local) })

    suspend fun report(local: EconomicEvent, market: MarketResponse): AnalysisReport = read(AiOperation.APPLY_RULES, "analysis", {
        backend.ruleAnalysis(remoteEvent(local)) { market }.let { report -> report.copy(
            eventId = local.id,
            // Use the same freshly read authoritative windows as the analysis screen and model.
            observedReactions = market.reactions,
        ) }
    }, { direct.ruleAnalysis(local) { market } })

    private suspend fun <T> read(operation: AiOperation, capability: String, remote: suspend () -> T, local: suspend () -> T): T {
        val step = trace?.start(operation)
        return try {
            val hasBackend = backendAddress.isNotBlank()
            val available = supported(capability)
            var detail = if (available) AiStepDetail.BACKEND_DATA else if (hasBackend) AiStepDetail.INTERFACE_UNAVAILABLE else AiStepDetail.DIRECT_DATA
            val result = if (available) {
                try { remote() } catch (error: BackendException) {
                    // A missing event/analysis (404), 401/403, outage or rate limit is NOT an
                    // unsupported interface and may not quietly switch to public providers.
                    if (error.statusCode != 501 || error.code !in setOf("not_implemented", "unsupported_endpoint")) throw error
                    detail = AiStepDetail.INTERFACE_UNAVAILABLE
                    local()
                }
            } else local()
            val count = if (result is MarketResponse) result.reactions.size else null
            step?.let { trace.finish(it, detail = detail, count = count) }
            result
        } catch (cancelled: CancellationException) {
            step?.let { trace.finish(it, AiStepStatus.CANCELLED) }
            throw cancelled
        } catch (error: Exception) {
            step?.let { trace.finish(it, AiStepStatus.FAILED) }
            throw error
        }
    }
}
