package com.macroresearch.data

import com.macroresearch.data.model.AiAnalysis
import com.macroresearch.data.model.AnalysisReport
import com.macroresearch.data.model.EconomicEvent
import com.macroresearch.data.model.EventDetailResponse
import com.macroresearch.data.model.MarketQuotesResponse
import com.macroresearch.data.model.MarketResponse
import com.macroresearch.data.remote.AiAnalysisClient
import com.macroresearch.data.remote.AiAnalysisInput
import com.macroresearch.data.remote.BackendClient
import com.macroresearch.data.remote.CalendarFetchResult
import com.macroresearch.data.remote.DirectMarketClient
import com.macroresearch.data.remote.EconomicCalendarClient
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Result of a translation-correction request. */
sealed interface CorrectionOutcome {
    /** Direct mode: the correction is already applied on this device. */
    data object AppliedLocally : CorrectionOutcome

    /** Backend mode: the admin still has to approve it. */
    data class Queued(val status: String) : CorrectionOutcome
}

/** Everything the server needs to brief one released event. */
data class AiAnalysisRequest(
    val event: EconomicEvent,
    val report: AnalysisReport,
    val languageTag: String,
    val method: AnalysisMethod,
    val revision: Int,
    val regenerate: Boolean,
)

/**
 * Where a screen's data comes from.
 *
 * Two implementations with identical signatures keep the repository's caching and UI logic
 * unchanged whichever mode the user picked; only the failure text and the correction sink differ.
 */
interface DataSource {
    val mode: DataSourceMode

    suspend fun calendar(
        start: LocalDate,
        end: LocalDate,
        countryCodes: Collection<String>? = null,
    ): CalendarFetchResult

    /** Remote history page. Direct mode reads the local cache instead. */
    suspend fun history(
        countries: Collection<String>,
        category: String?,
        limit: Int,
        offset: Int,
    ): List<EconomicEvent>

    /** Full detail from the remote, or null when the local cache is authoritative. */
    suspend fun eventDetail(id: Long): EventDetailResponse?

    /** Force a re-read of one event's day; null when the local refresher handles it. */
    suspend fun refreshRelease(id: Long): EventDetailResponse?

    suspend fun ruleAnalysis(
        event: EconomicEvent,
        market: suspend () -> MarketResponse,
    ): AnalysisReport

    suspend fun aiAnalysis(request: AiAnalysisRequest): AiAnalysis

    suspend fun cachedAiAnalysis(id: Long, language: String, method: AnalysisMethod): AiAnalysis? = null

    suspend fun serverEventId(event: EconomicEvent): Long = event.id

    suspend fun translations(names: List<String>): Map<String, Pair<String, String>> = emptyMap()

    suspend fun feedback(id: Long, language: String, method: Int, revision: Int, message: String) {
        error("Configure a backend to submit feedback")
    }

    suspend fun eventMarket(event: EconomicEvent): MarketResponse

    suspend fun quotes(symbols: List<String>): MarketQuotesResponse

    suspend fun submitCorrection(
        event: EconomicEvent,
        zhCn: String,
        zhTw: String,
    ): CorrectionOutcome

    /** Validates the backend address and token; only backend mode implements this. */
    suspend fun meta(): com.macroresearch.data.remote.BackendMeta
}

/** The original on-device pipeline: direct providers plus the local rule engine. */
class DirectDataSource(
    internal val calendarClient: EconomicCalendarClient,
    private val marketClient: DirectMarketClient,
    private val aiAnalysisClient: AiAnalysisClient,
    private val analysisEngine: LocalAnalysisEngine,
    private val translationPreferences: TranslationPreferences,
) : DataSource {
    override val mode = DataSourceMode.DIRECT

    override suspend fun calendar(
        start: LocalDate,
        end: LocalDate,
        countryCodes: Collection<String>?,
    ): CalendarFetchResult = calendarClient.fetch(start, end, countryCodes)

    override suspend fun history(
        countries: Collection<String>,
        category: String?,
        limit: Int,
        offset: Int,
    ): List<EconomicEvent> = emptyList()

    override suspend fun eventDetail(id: Long): EventDetailResponse? = null

    override suspend fun refreshRelease(id: Long): EventDetailResponse? = null

    override suspend fun ruleAnalysis(
        event: EconomicEvent,
        market: suspend () -> MarketResponse,
    ): AnalysisReport = analysisEngine.analyze(event, market().reactions)

    override suspend fun aiAnalysis(request: AiAnalysisRequest): AiAnalysis {
        val settings = translationPreferences.settings.value
        val apiKey = translationPreferences.apiKey()
        require(settings.configured && apiKey != null) {
            "Configure an API key in Settings first"
        }
        val draft = aiAnalysisClient.analyze(
            AiAnalysisInput(
                event = request.event,
                macroSignal = request.report.macroSignal,
                rawSurprise = request.report.rawSurprise,
                expectedReactions = request.report.expectedReactions,
                observedReactions = request.report.observedReactions,
                languageTag = request.languageTag,
            ),
            settings = settings,
            apiKey = apiKey,
            method = request.method,
        )
        return AiAnalysis(
            eventId = request.event.id,
            method = request.method.wireValue,
            revision = request.revision,
            chain = draft.chain,
            dataAnalysis = draft.dataAnalysis,
            marketOutlook = draft.marketOutlook,
            risks = draft.risks,
            model = settings.model,
            generatedAt = Instant.now().toString(),
            fromCache = false,
            rateLimited = false,
        )
    }

    override suspend fun eventMarket(event: EconomicEvent): MarketResponse =
        marketClient.eventMarket(event)

    override suspend fun quotes(symbols: List<String>): MarketQuotesResponse =
        marketClient.quotes(symbols)

    override suspend fun submitCorrection(
        event: EconomicEvent,
        zhCn: String,
        zhTw: String,
    ): CorrectionOutcome = CorrectionOutcome.AppliedLocally

    override suspend fun meta(): com.macroresearch.data.remote.BackendMeta =
        error("Direct mode has no backend endpoint")
}

/**
 * The self-hosted backend.
 *
 * Nothing here silently falls back to a direct provider: if the backend is down the user sees
 * cached rows plus an explicit error, which is what keeps the server-side alerting meaningful.
 */
class BackendDataSource(
    private val client: BackendClient,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : DataSource {
    override val mode = DataSourceMode.BACKEND

    override suspend fun calendar(
        start: LocalDate,
        end: LocalDate,
        countryCodes: Collection<String>?,
    ): CalendarFetchResult {
        val from = start.atStartOfDay(zone).toInstant()
        val to = end.plusDays(1).atStartOfDay(zone).toInstant().minusSeconds(1)
        val events = client.calendar(from, to)
            .filter { countryCodes.isNullOrEmpty() || it.country in countryCodes }
        return CalendarFetchResult(events)
    }

    override suspend fun history(
        countries: Collection<String>,
        category: String?,
        limit: Int,
        offset: Int,
    ): List<EconomicEvent> = client.history(countries, category, limit, offset)

    override suspend fun eventDetail(id: Long): EventDetailResponse = client.event(id)

    override suspend fun refreshRelease(id: Long): EventDetailResponse = client.refresh(id)

    override suspend fun ruleAnalysis(
        event: EconomicEvent,
        market: suspend () -> MarketResponse,
    ): AnalysisReport = client.analysis(event.id)

    override suspend fun aiAnalysis(request: AiAnalysisRequest): AiAnalysis =
        error("Shared server analysis is read-only")

    override suspend fun serverEventId(event: EconomicEvent): Long =
        client.eventByProvider(event.provider, event.providerId).id

    override suspend fun translations(names: List<String>): Map<String, Pair<String, String>> = client.translations(names)

    override suspend fun cachedAiAnalysis(id: Long, language: String, method: AnalysisMethod): AiAnalysis? =
        client.aiAnalysis(id, language, method, timezone = "UTC")

    override suspend fun feedback(id: Long, language: String, method: Int, revision: Int, message: String) {
        client.submitAnalysisFeedback(id, language, method, revision, message)
    }

    override suspend fun eventMarket(event: EconomicEvent): MarketResponse = client.market(event.id)

    override suspend fun quotes(symbols: List<String>): MarketQuotesResponse =
        client.marketQuotes(symbols)

    override suspend fun submitCorrection(
        event: EconomicEvent,
        zhCn: String,
        zhTw: String,
    ): CorrectionOutcome {
        val record = client.submitCorrection(event.event, zhCn, zhTw)
        return CorrectionOutcome.Queued(record.status)
    }

    override suspend fun meta(): com.macroresearch.data.remote.BackendMeta = client.meta()
}
