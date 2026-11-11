package com.macroresearch.data

import android.util.Log
import com.macroresearch.data.local.AiAnalysisEntity
import com.macroresearch.data.local.AiConversationEntity
import com.macroresearch.data.local.AiAutoRefreshEntity
import com.macroresearch.data.local.AnalysisDao
import com.macroresearch.data.local.EventDao
import com.macroresearch.data.local.FollowedEventEntity
import com.macroresearch.data.local.asEntity
import com.macroresearch.data.local.asExternalModel
import com.macroresearch.data.model.AiAnalysis
import com.macroresearch.data.model.AiUsage
import com.macroresearch.data.model.AnalysisReport
import com.macroresearch.data.model.EconomicEvent
import com.macroresearch.data.model.EarningsSort
import com.macroresearch.data.model.EventDetailResponse
import com.macroresearch.data.model.EventObservation
import com.macroresearch.data.model.hasEventTimeArrived
import com.macroresearch.data.model.MarketQuotesResponse
import com.macroresearch.data.model.MarketResponse
import com.macroresearch.data.remote.AiAnalysisClient
import com.macroresearch.data.remote.AiAnalysisInput
import com.macroresearch.data.remote.AiConversationMessage
import com.macroresearch.data.remote.BackendException
import com.macroresearch.data.remote.BackendUnauthorizedException
import com.macroresearch.data.remote.BackendVerificationResult
import com.macroresearch.data.remote.EconomicCalendarClient
import com.macroresearch.data.remote.NewsRssClient
import com.macroresearch.data.remote.EventPushTopics
import com.macroresearch.data.remote.TranslationClient
import com.macroresearch.data.remote.stableEventId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

/**
 * Single entry point for every screen.
 *
 * [DataSource] supplies the bytes and the derived analysis; this class owns the local cache,
 * the translation enrichment and the mode switch, so a screen never has to know whether the user
 * picked direct providers or a self-hosted backend.
 */
class MacroRepository(
    private val directSource: DataSource,
    private val backendSource: DataSource,
    /** Direct-mode release retry; backend mode refreshes through the server instead. */
    private val calendarClient: EconomicCalendarClient,
    private val networkPreferences: NetworkPreferences,
    private val analysisPreferences: AnalysisPreferences,
    private val newsPreferences: NewsPreferences,
    private val newsRssClient: NewsRssClient,
    private val aiHistoryStore: AiHistoryStore,
    private val backendPreferences: BackendPreferences,
    private val translationClient: TranslationClient,
    private val aiAnalysisClient: AiAnalysisClient,
    private val dao: EventDao,
    private val analysisDao: AnalysisDao,
    private val countryPreferences: CountryPreferences,
    private val marketPreferences: MarketPreferences,
    private val translationPreferences: TranslationPreferences,
    private val pushTopicManager: EventPushTopics,
    private val calendarPreferences: CalendarPreferences,
    val newsReader: NewsReaderRepository,
) {
    private val aiRunGate = AiRunGate()
    // Captured before registration, so a reset also rejects work queued just before it.
    private val conversationResets = mutableMapOf<Long, Long>()
    val selectedCountries: StateFlow<Set<String>> = countryPreferences.selectedCountries
    val selectedMarkets: StateFlow<List<String>> = marketPreferences.selectedMarkets
    val translationSettings: StateFlow<TranslationSettings> = translationPreferences.settings
    val dataSourceSettings: StateFlow<BackendSettings> = backendPreferences.settings
    private val _translationError = MutableStateFlow<String?>(null)
    val translationError = _translationError.asStateFlow()
    val proxyAddress = networkPreferences.address
    val analysisMethod: StateFlow<AnalysisMethod> = analysisPreferences.method
    val newsSettings: StateFlow<NewsSettings> = newsPreferences.settings
    val earningsSort = calendarPreferences.earningsSort
    fun setEarningsSort(sort: EarningsSort) = calendarPreferences.setEarningsSort(sort)
    val eventTimeDisplay = calendarPreferences.eventTimeDisplay
    fun setEventTimeDisplay(mode: EventTimeDisplay) = calendarPreferences.setEventTimeDisplay(mode)
    val calendarDisplayMode = calendarPreferences.mode
    fun setCalendarDisplayMode(mode: CalendarDisplayMode) = calendarPreferences.setMode(mode)

    /** The active plane. Read per call so a settings change applies immediately. */
    private val source: DataSource
        get() = if (dataSourceSettings.value.mode == DataSourceMode.BACKEND) {
            backendSource
        } else {
            directSource
        }

    fun setAnalysisMethod(method: AnalysisMethod) = analysisPreferences.setMethod(method)
    val aiToolsEnabled = analysisPreferences.toolsEnabled
    fun setAiToolsEnabled(enabled: Boolean) = analysisPreferences.setToolsEnabled(enabled)
    fun setNewsSearchEnabled(enabled: Boolean) = newsPreferences.setEnabled(enabled)
    fun setNewsSources(urls: List<String>) = newsPreferences.setSources(urls)
    fun setBiquoteNewsEnabled(enabled: Boolean) = newsPreferences.setBiquoteEnabled(enabled)
    fun setArticlePreviewEnabled(enabled: Boolean) = newsPreferences.setArticlePreviewEnabled(enabled)
    val newsReaderEnabled: StateFlow<Boolean> = newsPreferences.readerEnabled
    fun setNewsReaderEnabled(enabled: Boolean) = newsPreferences.setReaderEnabled(enabled)
    val newsReaderInternationalEnabled = newsPreferences.readerInternationalEnabled
    fun setNewsReaderInternationalEnabled(enabled: Boolean) = newsPreferences.setReaderInternationalEnabled(enabled)

    val newsTitleTranslationEnabled = newsPreferences.titleTranslationEnabled
    fun setNewsTitleTranslationEnabled(enabled: Boolean) = newsPreferences.setTitleTranslationEnabled(enabled)

    suspend fun analyzeNews(
        article: com.macroresearch.data.remote.NewsArticle,
        reading: com.macroresearch.data.remote.ArticleReading?,
        history: List<com.macroresearch.data.remote.AiConversationMessage>,
        question: String,
        languageTag: String,
        includeQuotes: Boolean,
    ): com.macroresearch.data.remote.AiConversationMessage {
        check(newsReaderEnabled.value) { "News reader is disabled" }
        val settings = translationSettings.value
        val key = translationPreferences.apiKey() ?: error("Personal AI key is required")
        val quotes = if (includeQuotes) kotlinx.coroutines.withTimeoutOrNull(12_000) {
            try { marketQuotes(listOf("nasdaq100", "sp500", "gold", "dxy", "us2y", "us10y", "bitcoin")) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        } else null
        return aiAnalysisClient.analyzeNews(com.macroresearch.data.remote.NewsAnalysisInput(
            article, reading, languageTag, question, history, quotes, includeQuotes,
        ), settings, key)
    }

    private val _calendarWarning = MutableStateFlow<CalendarWarning?>(null)
    val calendarWarning = _calendarWarning.asStateFlow()

    fun saveCalendarProxy(address: String) {
        networkPreferences.save(address)
        historySyncedAt = 0L
    }

    // ---------------------------------------------------------------- data source mode

    /**
     * Switches between direct providers and the backend.
     *
     * The two modes use different event id spaces (a content digest versus a database key), so
     * the event-derived cache is dropped. The caller must confirm this with the user first.
     */
    suspend fun setDataSourceMode(mode: DataSourceMode) {
        releaseCommitMutex.withLock {
            val previousMode = dataSourceSettings.value.mode
            if (mode == previousMode) return
            withContext(kotlinx.coroutines.NonCancellable) {
                invalidateReleaseRefresh()
                if (previousMode == DataSourceMode.BACKEND) {
                    pushTopicManager.unsubscribeAll(dao.followedEventIds())
                }
                clearMacroSourceCache()
                backendPreferences.setMode(mode)
            }
        }
    }

    /** Changing servers changes the numeric event-id namespace, unlike rotating a Key. */
    suspend fun saveBackendSettings(baseUrl: String, token: String) {
        releaseCommitMutex.withLock {
            val previous = dataSourceSettings.value
            // Validate before invalidating or deleting anything; invalid edits preserve the cache.
            val normalized = BackendPreferences.normalizeBaseUrl(baseUrl, previous.allowCleartext)
            withContext(kotlinx.coroutines.NonCancellable) {
                invalidateReleaseRefresh()
                if (previous.requiresMacroCacheReset(normalized)) {
                    pushTopicManager.unsubscribeAll(dao.followedEventIds())
                    clearMacroSourceCache()
                }
                backendPreferences.save(normalized, token)
                marketCache.clear()
                backendPreferences.clearVerification()
                networkPreferences.clearUpcomingSync()
                historySyncedAt = 0L
                publishWarning(null)
            }
        }
    }

    /** Caller holds the source commit lock; preferences and earnings namespaces are preserved. */
    private suspend fun clearMacroSourceCache() {
        dao.clearCache()
        analysisDao.clearAll()
        analysisDao.clearAllConversations()
        analysisDao.clearAllAutoRefreshAttempts()
        withContext(Dispatchers.IO) { aiHistoryStore.clearAll() }
        marketCache.clear()
        historySyncedAt = 0L
        networkPreferences.clearUpcomingSync()
        _translationError.value = null
        publishWarning(null)
    }

    /** Enables the explicit plain-HTTP escape hatch for a backend address. */
    fun setAllowBackendCleartext(allowed: Boolean) {
        backendPreferences.setAllowCleartext(allowed)
        backendPreferences.clearVerification()
    }

    suspend fun clearBackendSettings() {
        releaseCommitMutex.withLock {
            withContext(kotlinx.coroutines.NonCancellable) {
                invalidateReleaseRefresh()
                if (dataSourceSettings.value.mode == DataSourceMode.BACKEND) {
                    pushTopicManager.unsubscribeAll(dao.followedEventIds())
                    clearMacroSourceCache()
                }
                backendPreferences.clear()
                backendPreferences.setMode(DataSourceMode.DIRECT)
            }
        }
    }

    /** Discover without saving settings, switching data modes, authorizing or binding the device. */
    suspend fun backendKeyRequestBotUrl(address: String): String? {
        val normalized = BackendPreferences.normalizeBaseUrl(
            address, allowsCleartext = dataSourceSettings.value.allowCleartext,
        )
        return backendSource.keyRequestBotUrl(normalized)
    }

    /** Marks verified only after live protocol and credential checks; no software version result. */
    suspend fun verifyBackend(): BackendVerificationResult {
        backendPreferences.clearVerification()
        val result = backendSource.verifyConnection(SUPPORTED_API_VERSION)
        backendPreferences.markVerified()
        return result
    }

    // ---------------------------------------------------------------- calendar

    private suspend fun fetchCalendar(start: LocalDate, end: LocalDate): List<EconomicEvent> {
        val result = source.calendar(start, end)
        publishWarning(result.warning)
        return result.events
    }

    /**
     * Keeps raw provider errors out of the UI: the reason is localized where it is rendered and
     * the exception text is written to the log instead.
     */
    private fun publishWarning(warning: CalendarWarning?) {
        if (warning != null) Log.w(TAG, "Calendar degraded (${warning.reason}): ${warning.detail}")
        _calendarWarning.value = warning
    }

    /** Builds a warning for a failure that prevented the calendar from refreshing at all. */
    private fun warningFor(error: Throwable): CalendarWarning = when (error) {
        is CalendarUnavailableException -> error.warning
        is BackendUnauthorizedException -> CalendarWarning(
            CalendarWarning.Reason.BACKEND_UNAVAILABLE,
            error.message,
        )
        is BackendException -> CalendarWarning(
            CalendarWarning.Reason.BACKEND_UNAVAILABLE,
            "${error.javaClass.simpleName}: ${error.message}",
        )
        else -> CalendarWarning(
            if (source.mode == DataSourceMode.BACKEND) {
                CalendarWarning.Reason.BACKEND_UNAVAILABLE
            } else {
                CalendarWarning.Reason.ALL_SOURCES_UNAVAILABLE
            },
            "${error.javaClass.simpleName}: ${error.message}",
        )
    }

    /** Emits after freshly reviewed translations are persisted so lists can re-read them. */
    private val _translationsUpdated = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val translationsUpdated: SharedFlow<Unit> = _translationsUpdated.asSharedFlow()

    private val releaseGate = ReleaseRefreshGate()
    private val releaseGeneration = releaseGate.generation
    private val releaseCommitMutex get() = releaseGate.commitMutex
    private var coordinatorGeneration = -1L
    private val releaseCoordinator = ForegroundReleaseCoordinator(
        candidates = { now ->
            releaseCommitMutex.withLock {
                dao.cachedRange(now.minusSeconds(30 * 60).toString(), now.toString())
                    .map { it.asExternalModel() }
            }
        },
        refresh = { events ->
            try {
                refreshReleaseGroup(events)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ReleaseRefreshOutcome(false, if (source.mode == DataSourceMode.DIRECT) calendarClient.nextRetryAt() else null)
            }
        },
        groupingZone = { if (source.mode == DataSourceMode.BACKEND) ZoneOffset.UTC else ZoneId.systemDefault() },
    )

    private fun invalidateReleaseRefresh() {
        releaseGate.invalidate()
        aiRunGate.cancelAll()
    }

    /** Owned by the Activity's STARTED lifecycle. Cancelling this also cancels in-flight requests. */
    suspend fun runForegroundReleaseRefresh() {
        releaseGeneration.collectLatest { generation ->
            if (coordinatorGeneration != generation) {
                releaseCoordinator.reset()
                coordinatorGeneration = generation
            }
            releaseCoordinator.run()
        }
    }

    /** Both manual and automatic retries share a date-level gate and a 15-second network cooldown. */
    private suspend fun refreshReleaseGroup(
        events: List<EconomicEvent>,
        expectedGeneration: Long = releaseGeneration.value,
    ): ReleaseRefreshOutcome {
        val generation = expectedGeneration
        val activeSource = releaseCommitMutex.withLock {
            if (generation != releaseGeneration.value) throw CancellationException("Data source changed")
            source
        }
        val zone = if (activeSource.mode == DataSourceMode.BACKEND) ZoneOffset.UTC else ZoneId.systemDefault()
        val day = Instant.parse(events.first().eventTime).atZone(zone).toLocalDate()
        return try {
            releaseGate.refresh(generation, day,
                fetch = { ReleaseBatchFetcher.fetch(events, activeSource, zone) },
                commit = { result ->
                    dao.mergeCalendar(mergeCachedTranslations(result.events).map(EconomicEvent::asEntity))
                    publishWarning(result.warning)
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (generation == releaseGeneration.value) publishWarning(warningFor(error))
            throw error
        }
    }
    private val marketCache = ConcurrentHashMap<Long, CachedMarket>()
    private val upcomingMutex = Mutex()
    private val historyMutex = Mutex()
    private val translationMutex = Mutex()
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var historySyncedAt = 0L

    fun observeUpcoming(): Flow<List<EconomicEvent>> =
        // Keep today's already released rows on Home so their instant labels remain visible.
        dao.observeUpcoming(LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toString()).map { events ->
            events.map { it.asExternalModel() }
        }

    fun observeCalendar(date: LocalDate): Flow<List<EconomicEvent>> = observeCalendar(date, date)

    fun observeCalendar(start: LocalDate, end: LocalDate): Flow<List<EconomicEvent>> {
        val zone = ZoneId.systemDefault()
        return dao.observeRange(
            start.atStartOfDay(zone).toInstant().toString(),
            end.plusDays(1).atStartOfDay(zone).toInstant().minusNanos(1).toString(),
        ).map { rows -> rows.map { it.asExternalModel() } }
    }

    /** One bounded range request for the month grid, not one request per date. */
    suspend fun refreshCalendarRange(start: LocalDate, end: LocalDate) {
        require(!end.isBefore(start) && java.time.temporal.ChronoUnit.DAYS.between(start, end) < 90)
        val (generation, activeSource) = releaseCommitMutex.withLock { releaseGeneration.value to source }
        val result = try {
            activeSource.calendar(start, end)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (generation == releaseGeneration.value) publishWarning(warningFor(error))
            throw error
        }
        val translated = mergeCachedTranslations(result.events)
        releaseCommitMutex.withLock {
            if (generation != releaseGeneration.value) throw CancellationException("Data source changed")
            dao.mergeCalendar(translated.map(EconomicEvent::asEntity))
            publishWarning(result.warning)
        }
        if (translated.isNotEmpty()) {
            repositoryScope.launch { runCatching { enrichTranslations(translated) } }
        }
    }

    fun observeEvent(id: Long): Flow<EconomicEvent?> =
        dao.observeEvent(id).map { it?.asExternalModel() }

    fun observeFollowed(id: Long): Flow<Boolean> = dao.observeFollowed(id)

    suspend fun isFollowed(id: Long): Boolean = dao.isFollowed(id)

    suspend fun refreshUpcoming(days: Int = 7) = upcomingMutex.withLock {
        val (generation, activeSource) = releaseCommitMutex.withLock { releaseGeneration.value to source }
        // Cached reads, legacy cleanup and the freshness marker share the switching boundary.
        val cached = releaseGate.commit(generation) {
            if (networkPreferences.upcomingSyncFresh(UPCOMING_CACHE_MS)) {
                val now = Instant.now()
                dao.cachedRange(now.toString(), now.plusSeconds(days * 86_400L).toString())
                    .map { it.asExternalModel() }
            } else {
                dao.deleteByProviders(LEGACY_CALENDAR_PROVIDERS)
                null
            }
        }
        if (cached != null) {
            enrichSourceTranslations(cached, generation)
            return@withLock
        }
        val today = LocalDate.now()
        val result = try {
            activeSource.upcoming(days)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            releaseGate.commit(generation) { publishWarning(warningFor(error)) }
            throw error
        }
        val merged = releaseGate.commit(generation) {
            val rows = dao.mergeCalendar(mergeCachedTranslations(result.events).map(EconomicEvent::asEntity))
                .map { it.asExternalModel() }
            dao.deleteOlderThan(
                today.minusDays(HISTORY_RETENTION_DAYS)
                    .atStartOfDay(ZoneId.systemDefault()).toInstant().toString(),
            )
            publishWarning(result.warning)
            // An old response cannot mark the new source fresh, even if it returned no rows.
            networkPreferences.markUpcomingSynced()
            rows
        }
        enrichSourceTranslations(merged, generation)
    }

    suspend fun calendar(
        date: LocalDate,
        country: String? = null,
        minimumImportance: Int? = null,
    ): List<EconomicEvent> {
        val zone = ZoneId.systemDefault()
        val from = date.atStartOfDay(zone).toInstant().toString()
        val to = date.plusDays(1).atStartOfDay(zone).toInstant().minusSeconds(1).toString()
        val cached = dao.cachedRange(from, to)
        val cacheCanAnswer = cached.isNotEmpty() &&
            (date != LocalDate.now() || networkPreferences.upcomingSyncFresh(UPCOMING_CACHE_MS))
        val rows = if (cacheCanAnswer) {
            cached
        } else {
            val fetched = try {
                mergeCachedTranslations(fetchCalendar(date, date))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                publishWarning(warningFor(error))
                emptyList()
            }
            if (fetched.isNotEmpty()) dao.mergeCalendar(fetched.map(EconomicEvent::asEntity)) else cached
        }
        val events = rows.map { it.asExternalModel() }
            .filter { country == null || it.country == country }
            .filter { minimumImportance == null || it.importance >= minimumImportance }
        // Translation is an enhancement and must never hold back the day's list.
        if (events.isNotEmpty()) {
            repositoryScope.launch { runCatching { enrichTranslations(events) } }
        }
        return events
    }

    suspend fun event(id: Long): EventDetailResponse {
        val (generation, activeSource) = releaseCommitMutex.withLock { releaseGeneration.value to source }
        if (activeSource.mode == DataSourceMode.BACKEND) {
            try {
                activeSource.eventDetail(id)?.let { detail ->
                    // Keep a translation already cached on the device if this server event was
                    // stored before the server-side backfill completed.
                    val translated = mergeCachedTranslations(listOf(detail.event)).single()
                    val event = releaseCommitMutex.withLock {
                        if (generation != releaseGeneration.value) throw CancellationException("Data source changed")
                        // A detail GET must not erase a value just published by the date-level poll.
                        dao.mergeCalendar(listOf(translated.asEntity())).single().asExternalModel()
                    }
                    if (event.eventZhCn.isNullOrBlank() || event.eventZhTw.isNullOrBlank()) {
                        repositoryScope.launch { runCatching { enrichTranslations(listOf(event)) } }
                    }
                    return detail.copy(event = event)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // A backend outage must still render the cached row with an explicit warning.
                publishWarning(warningFor(error))
            }
        }
        return localEvent(id)
    }

    private suspend fun localEvent(id: Long): EventDetailResponse {
        val event = dao.event(id)?.asExternalModel()
            ?: error("Event is not available in the local cache")
        if (event.eventZhCn.isNullOrBlank() || event.eventZhTw.isNullOrBlank()) {
            // Detail state is backed by observeEvent(), so this non-blocking lookup replaces the
            // English fallback in place as soon as the shared cache answers.
            repositoryScope.launch { runCatching { enrichTranslations(listOf(event)) } }
        }
        val observations = if (event.actual == null) emptyList() else listOf(
            EventObservation(
                id = stableEventId("observation|${event.id}|${event.actual}"),
                eventId = event.id,
                observedAt = Instant.now().toString(),
                actual = event.actual,
                previous = event.previous,
                consensus = event.consensus,
                forecast = event.forecast,
            ),
        )
        return EventDetailResponse(event, observations)
    }

    /**
     * User-triggered retry for one event whose release value is still missing. Bypasses the
     * history sync interval, keeps the cached row and its translated name, and reports the
     * source warning so the caller can explain why a value is still absent.
     */
    suspend fun refreshEventRelease(id: Long): EventDetailResponse {
        val generation = releaseGeneration.value
        val original = dao.event(id)?.asExternalModel()
            ?: error("Event is not available in the local cache")
        refreshReleaseGroup(listOf(original), generation)
        if (generation != releaseGeneration.value) throw CancellationException("Data source changed")
        // Preserve backend descriptions/observation history on a manual retry; labels themselves
        // already rendered from Room before this optional detail read finishes.
        return if (source.mode == DataSourceMode.BACKEND) event(id) else localEvent(id)
    }

    private fun aiEvidenceLoader(trace: AiExecutionTrace? = null, settings: BackendSettings = dataSourceSettings.value): AiEvidenceLoader = AiEvidenceLoader(
        directSource, backendSource, settings.baseUrl,
        settings.mode == DataSourceMode.BACKEND, trace,
    )

    suspend fun analysis(id: Long): AnalysisReport {
        val local = event(id).event
        val evidence = aiEvidenceLoader()
        val authoritative = evidence.event(local)
        val market = evidence.market(authoritative)
        return evidence.report(authoritative, market)
    }

    /** The cached briefing of the method the user currently has selected. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeAiAnalysis(eventId: Long): Flow<AiAnalysis?> =
        analysisPreferences.method.flatMapLatest { method ->
            analysisDao.observe(eventId, method.wireValue).map { it?.toModel() }
        }

    suspend fun aiAnalysis(eventId: Long): AiAnalysis? {
        val method = analysisPreferences.method.value
        return analysisDao.analysis(eventId, method.wireValue)?.toModel()
    }

    suspend fun aiConversation(eventId: Long): List<AiConversationMessage> {
        val row = analysisDao.conversation(eventId) ?: return emptyList()
        return aiAnalysisClient.decodeConversation(row.messagesJson)
    }

    suspend fun resetAiConversation(eventId: Long) {
        releaseCommitMutex.withLock {
            conversationResets[eventId] = (conversationResets[eventId] ?: 0L) + 1
            aiRunGate.cancel(conversationHistoryKey(eventId))
            withContext(kotlinx.coroutines.NonCancellable) {
                analysisDao.clearConversation(eventId)
                withContext(Dispatchers.IO) { aiHistoryStore.clear(conversationHistoryKey(eventId)) }
            }
        }
    }

    suspend fun autoRefreshAttempted(eventId: Long, method: Int, language: String): Boolean =
        analysisDao.autoRefreshAttempt(eventId, method, language) != null

    suspend fun recordAutoRefreshAttempt(eventId: Long, method: Int, language: String) {
        analysisDao.recordAutoRefreshAttempt(
            AiAutoRefreshEntity(eventId, method, language, System.currentTimeMillis()),
        )
    }

    private fun analysisHistoryKey(eventId: Long, method: Int, settings: BackendSettings = dataSourceSettings.value): String = "analysis|${settings.mode}|${settings.baseUrl}|$eventId|$method"
    private fun conversationHistoryKey(eventId: Long, settings: BackendSettings = dataSourceSettings.value): String = "conversation|${settings.mode}|${settings.baseUrl}|$eventId"

    private data class AiContext(
        val generation: Long,
        val settings: BackendSettings,
        val method: AnalysisMethod,
        val conversationId: Long? = null,
        val resetVersion: Long = 0,
    )

    private suspend fun aiContext(conversationId: Long? = null) = releaseCommitMutex.withLock {
        AiContext(releaseGeneration.value, dataSourceSettings.value, analysisPreferences.method.value,
            conversationId, conversationResets[conversationId] ?: 0L)
    }

    private suspend fun <T> commitAi(context: AiContext, run: AiRunGate.Run, block: suspend () -> T): T =
        releaseGate.commit(context.generation) {
            run.ensureActive()
            if (context.conversationId != null && (conversationResets[context.conversationId] ?: 0L) != context.resetVersion) {
                throw CancellationException("AI conversation reset")
            }
            block()
        }

    suspend fun aiHistory(eventId: Long): AiHistory = withContext(Dispatchers.IO) {
        aiHistoryStore.load(analysisHistoryKey(eventId, analysisPreferences.method.value.wireValue))
    }

    suspend fun aiConversationHistory(eventId: Long): AiHistory = withContext(Dispatchers.IO) {
        aiHistoryStore.load(conversationHistoryKey(eventId))
    }

    suspend fun askAiFollowUp(eventId: Long, languageTag: String, question: String,
        onRequests: (List<AiRequestRecord>) -> Unit = {},
    ): List<AiConversationMessage> {
        val context = aiContext(eventId)
        return aiRunGate.run(conversationHistoryKey(eventId, context.settings)) { run ->
            askAiFollowUpInternal(eventId, languageTag, question, onRequests, context, run)
        }
    }

    private suspend fun askAiFollowUpInternal(eventId: Long, languageTag: String, question: String,
        onRequests: (List<AiRequestRecord>) -> Unit,
        context: AiContext,
        run: AiRunGate.Run,
    ): List<AiConversationMessage> = withContext(Dispatchers.IO) {
        val historyKey = conversationHistoryKey(eventId, context.settings)
        var checkpoint = commitAi(context, run) { aiHistoryStore.begin(historyKey, appendRequests = true) }
        val requestLog = AiRequestLog(checkpoint.requests) { records ->
            checkpoint = checkpoint.copy(requests = records)
            aiHistoryStore.save(historyKey, checkpoint)
            if (run.isActive) onRequests(records)
        }
        try {
            require(translationPreferences.settings.value.configured) { "A personal AI key is required for conversation" }
            require(question.isNotBlank())
            val local = commitAi(context, run) {
                dao.event(eventId)?.asExternalModel() ?: error("Event is not available in the local cache")
            }
            val evidence = aiEvidenceLoader(settings = context.settings)
            val event = evidence.event(local)
            require(hasEventTimeArrived(event.eventTime)) { "The event time has not passed yet" }
            val market = evidence.market(event)
            val report = evidence.report(event, market)
            val history = commitAi(context, run) { aiConversation(eventId) }
            val settings = translationPreferences.settings.value
            val key = translationPreferences.apiKey() ?: error("Configure an API key in Settings first")
            commitAi(context, run) { Unit }
            val assistant = aiAnalysisClient.converse(
                AiAnalysisInput(event, report.macroSignal, report.rawSurprise, report.expectedReactions, report.observedReactions, languageTag, requestLog = requestLog),
                settings, key, history, question.trim(),
                currentBriefing = commitAi(context, run) { analysisDao.analysis(eventId, context.method.wireValue) }?.let { cached ->
                    "Data: ${cached.dataAnalysis}\nOutlook: ${cached.marketOutlook}\nRisks: ${cached.risks.orEmpty()}"
                },
            )
            val updated = history + AiConversationMessage("user", question.trim()) + assistant
            commitAi(context, run) {
                analysisDao.upsertConversation(
                    AiConversationEntity(eventId, aiAnalysisClient.encodeConversation(updated), System.currentTimeMillis()),
                )
                aiHistoryStore.finish(historyKey, checkpoint, AiStepStatus.SUCCEEDED)
            }
            updated
        } catch (cancelled: CancellationException) {
            withContext(kotlinx.coroutines.NonCancellable) { aiHistoryStore.finish(historyKey, checkpoint, AiStepStatus.CANCELLED) }
            throw cancelled
        } catch (error: Exception) {
            aiHistoryStore.finish(historyKey, checkpoint, AiStepStatus.FAILED)
            throw error
        }
    }

    suspend fun generateAiAnalysis(
        eventId: Long,
        languageTag: String,
        regenerate: Boolean = false,
        onProgress: (List<AiExecutionStep>) -> Unit = {},
        onRequests: (List<AiRequestRecord>) -> Unit = {},
        onArticles: (List<com.macroresearch.data.remote.NewsArticle>) -> Unit = {},
    ): AiAnalysis {
        val context = aiContext()
        return aiRunGate.run(analysisHistoryKey(eventId, context.method.wireValue, context.settings)) { run ->
            generateAiAnalysisInternal(eventId, languageTag, regenerate, onProgress, onRequests, onArticles, context, run)
        }
    }

    private suspend fun generateAiAnalysisInternal(
        eventId: Long,
        languageTag: String,
        regenerate: Boolean,
        onProgress: (List<AiExecutionStep>) -> Unit,
        onRequests: (List<AiRequestRecord>) -> Unit,
        onArticles: (List<com.macroresearch.data.remote.NewsArticle>) -> Unit,
        context: AiContext,
        run: AiRunGate.Run,
    ): AiAnalysis = withContext(Dispatchers.IO) {
        check(translationSettings.value.configured) { "A personal AI key is required to generate analysis" }
        val method = context.method
        val cached = commitAi(context, run) { analysisDao.analysis(eventId, method.wireValue) }
        val historyKey = analysisHistoryKey(eventId, method.wireValue, context.settings)
        var checkpoint = commitAi(context, run) { aiHistoryStore.begin(historyKey) }
        val trace = AiExecutionTrace { steps ->
            checkpoint = checkpoint.copy(steps = steps)
            aiHistoryStore.save(historyKey, checkpoint)
            if (run.isActive) onProgress(steps)
        }
        val requestLog = AiRequestLog { records ->
            checkpoint = checkpoint.copy(requests = records)
            aiHistoryStore.save(historyKey, checkpoint)
            if (run.isActive) onRequests(records)
        }
        try {
            val local = commitAi(context, run) {
                dao.event(eventId)?.asExternalModel() ?: error("Event is not available in the local cache")
            }
            val evidence = aiEvidenceLoader(trace, context.settings)
            val event = evidence.event(local)
            require(hasEventTimeArrived(event.eventTime)) { "The event time has not passed yet" }
            // Evidence follows the configured backend; only the model request stays personal.
            val market = evidence.market(event)
            val report = evidence.report(event, market)
            val newsSettings = newsPreferences.settings.value
            val newsStep = trace.start(AiOperation.LOAD_NEWS)
            val newsResult = if (newsSettings.enabled) {
                newsRssClient.relevantArticles(event, newsSettings.sourceUrls, includeBiquote = newsSettings.biquoteEnabled, trace = trace)
            } else null
            trace.finish(newsStep, detail = when {
                !newsSettings.enabled -> AiStepDetail.NEWS_DISABLED
                newsResult?.status == "search_unavailable" -> AiStepDetail.MISSING_DATA
                newsResult?.status == "partial_results" -> AiStepDetail.PARTIAL_NEWS
                newsResult?.articles.isNullOrEmpty() -> AiStepDetail.NO_NEWS
                context.settings.baseUrl.isNotBlank() -> AiStepDetail.INTERFACE_UNAVAILABLE
                else -> AiStepDetail.DIRECT_DATA
            }, count = newsResult?.articles?.size)
            val articles = newsResult?.articles.orEmpty()
            checkpoint = checkpoint.copy(articles = articles)
            aiHistoryStore.save(historyKey, checkpoint)
            if (run.isActive) onArticles(articles)
            commitAi(context, run) { Unit }
            val nextRevision = (cached?.revision ?: 0) + 1
            val analysis = directSource.aiAnalysis(
                AiAnalysisRequest(
                    event = event,
                    report = report,
                    languageTag = languageTag,
                    method = method,
                    revision = nextRevision,
                    regenerate = regenerate || cached != null,
                    newsSearchRequested = newsSettings.enabled,
                    newsSearchStatus = newsResult?.status,
                    newsArticles = newsResult?.articles.orEmpty(),
                    toolsEnabled = analysisPreferences.toolsEnabled.value,
                    articlePreviewEnabled = newsSettings.enabled && newsSettings.articlePreviewEnabled,
                    trace = trace,
                    requestLog = requestLog,
                ),
            )
                .let { fetched ->
                    // The generated row must carry the method it was requested with, even when a
                    // relay or server omits the field.
                    fetched.copy(
                        eventId = event.id,
                        method = method.wireValue,
                        revision = if (fetched.revision > 0) fetched.revision else nextRevision,
                    )
                }
            val saveStep = trace.start(AiOperation.SAVE)
            commitAi(context, run) {
                analysisDao.upsert(analysis.toEntity())
                trace.finish(saveStep)
                aiHistoryStore.finish(historyKey, checkpoint, AiStepStatus.SUCCEEDED)
            }
            analysis
        } catch (cancelled: CancellationException) {
            trace.finishRunning(AiStepStatus.CANCELLED)
            withContext(kotlinx.coroutines.NonCancellable) { aiHistoryStore.finish(historyKey, checkpoint, AiStepStatus.CANCELLED) }
            throw cancelled
        } catch (error: Exception) {
            trace.finishRunning(AiStepStatus.FAILED)
            aiHistoryStore.finish(historyKey, checkpoint, AiStepStatus.FAILED)
            throw error
        }
    }

    private fun AiAnalysisEntity.toModel(): AiAnalysis = AiAnalysis(
        eventId = eventId,
        method = method,
        revision = revision,
        chain = aiAnalysisClient.decodeChain(chainJson),
        dataAnalysis = dataAnalysis,
        marketOutlook = marketOutlook,
        risks = risks,
        model = model,
        generatedAt = generatedAt,
        usage = if (usageCalls > 0) {
            AiUsage(
                promptTokens = usagePromptTokens,
                completionTokens = usageCompletionTokens,
                totalTokens = usageTotalTokens,
                calls = usageCalls,
            )
        } else {
            null
        },
        fromCache = fromCache,
        rateLimited = false,
        retryAfterSeconds = null,
    )

    private fun AiAnalysis.toEntity(): AiAnalysisEntity = AiAnalysisEntity(
        eventId = eventId,
        method = method,
        revision = revision,
        chainJson = aiAnalysisClient.encodeChain(chain),
        dataAnalysis = dataAnalysis,
        marketOutlook = marketOutlook,
        risks = risks,
        model = model,
        generatedAt = generatedAt,
        generatedAtEpochMs = runCatching {
            java.time.Instant.parse(generatedAt).toEpochMilli()
        }.getOrElse { System.currentTimeMillis() },
        usagePromptTokens = usage?.promptTokens ?: 0,
        usageCompletionTokens = usage?.completionTokens ?: 0,
        usageTotalTokens = usage?.totalTokens ?: 0,
        usageCalls = usage?.calls ?: 0,
        fromCache = fromCache,
        rateLimited = rateLimited,
        retryAfterSeconds = retryAfterSeconds,
    )

    suspend fun market(id: Long): MarketResponse {
        val cached = marketCache[id]
        if (cached != null && System.currentTimeMillis() - cached.savedAt < MARKET_CACHE_MS) {
            return cached.value
        }
        val event = dao.event(id)?.asExternalModel()
            ?: source.eventDetail(id)?.event
            ?: error("Event is not available in the local cache")
        val evidence = aiEvidenceLoader()
        val response = evidence.market(event)
        marketCache[id] = CachedMarket(System.currentTimeMillis(), response)
        return response
    }

    suspend fun marketQuotes(symbols: List<String>? = null): MarketQuotesResponse =
        source.quotes(symbols ?: MarketPreferences.SUPPORTED_MARKETS)

    suspend fun history(
        countries: Collection<String>,
        category: String? = null,
        limit: Int = HISTORY_PAGE_SIZE,
        offset: Int = 0,
        forceRefresh: Boolean = false,
        importance: Collection<Int>? = null,
    ): List<EconomicEvent> {
        val selected = countries.distinct()
        val levels = importance?.distinct() ?: listOf(0, 1, 2, 3)
        if (selected.isEmpty() || levels.isEmpty()) return emptyList()
        val (generation, activeSource) = releaseCommitMutex.withLock { releaseGeneration.value to source }
        if (activeSource.mode == DataSourceMode.BACKEND) {
            return backendHistory(selected, category, limit, offset, importance, generation, activeSource)
        }
        var page = releaseGate.commit(generation) {
            dao.history(Instant.now().toString(), selected, category, limit, offset, levels)
                .map { it.asExternalModel() }
        }
        // Room is the source of truth. Only backfill the first page when it cannot provide a full
        // page, or when the user explicitly asks to refresh. Entering History no longer blocks on
        // the same unreliable network request every time.
        if (offset == 0 && (forceRefresh || (page.size < limit && historySyncDue()))) {
            try {
                syncRecentHistory(forceRefresh, generation, activeSource)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                releaseGate.commit(generation) { publishWarning(warningFor(error)) }
            }
            page = releaseGate.commit(generation) {
                dao.history(Instant.now().toString(), selected, category, limit, offset, levels)
                    .map { it.asExternalModel() }
            }
        }
        // Network failure preserves this source's cache; switching is cancellation, not fallback.
        enrichSourceTranslations(page, generation)
        return page
    }

    /**
     * Backend paging: the server stores the full history, so pages are fetched remotely and
     * mirrored into Room only so an offline entry still shows something.
     */
    private suspend fun backendHistory(
        countries: List<String>,
        category: String?,
        limit: Int,
        offset: Int,
        importance: Collection<Int>?,
        generation: Long,
        activeSource: DataSource,
    ): List<EconomicEvent> {
        // Room may contain only pages from a narrower filter, even when it fills a page.
        // Always ask the server first, including when selecting all levels again, so offsets
        // refer to the complete filtered dataset. Room remains the offline fallback.
        val page = try {
            activeSource.history(countries, category, limit, offset, importance)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return releaseGate.commit(generation) {
                publishWarning(warningFor(error))
                dao.history(
                    Instant.now().toString(), countries, category, limit, offset,
                    importance?.distinct() ?: listOf(0, 1, 2, 3),
                ).map { it.asExternalModel() }
            }
        }
        releaseGate.commit(generation) {
            if (page.isNotEmpty()) dao.upsert(page.map(EconomicEvent::asEntity))
            historySyncedAt = System.currentTimeMillis()
        }
        enrichSourceTranslations(page, generation)
        return page
    }

    suspend fun translationModels(baseUrl: String): List<String> {
        val apiKey = translationPreferences.apiKey()
            ?: error("Save an API key before fetching models")
        val normalizedUrl = TranslationPreferences.normalizeBaseUrl(baseUrl)
        return runCatching { translationClient.models(normalizedUrl, apiKey) }
            .onSuccess { _translationError.value = null }
            .onFailure { _translationError.value = it.message ?: "Unable to fetch models" }
            .getOrThrow()
    }

    fun saveTranslationSettings(apiKey: String, baseUrl: String, model: String) {
        translationPreferences.save(apiKey, baseUrl, model)
        _translationError.value = null
    }

    fun updateTranslationProvider(baseUrl: String, model: String) {
        translationPreferences.updateProvider(baseUrl, model)
        _translationError.value = null
    }

    fun clearTranslationSettings() {
        translationPreferences.clear()
        _translationError.value = null
    }

    fun observeFollowedEvents(): Flow<List<EconomicEvent>> =
        dao.observeFollowedEvents().map { rows -> rows.map { it.asExternalModel() } }

    suspend fun setFollowed(id: Long, followed: Boolean) = releaseCommitMutex.withLock {
        if (followed) {
            dao.follow(FollowedEventEntity(id))
            if (dataSourceSettings.value.mode == DataSourceMode.BACKEND) {
                pushTopicManager.subscribe(id)
            }
        } else {
            dao.unfollow(id)
            if (dataSourceSettings.value.mode == DataSourceMode.BACKEND) {
                pushTopicManager.unsubscribe(id)
            }
        }
    }

    suspend fun synchronizePushTopics() = releaseCommitMutex.withLock {
        if (dataSourceSettings.value.mode == DataSourceMode.BACKEND) {
            pushTopicManager.synchronize(dao.followedEventIds())
        }
    }

    fun setCountries(countries: Set<String>) = countryPreferences.setCountries(countries)

    fun setCountryEnabled(country: String, enabled: Boolean) =
        countryPreferences.setCountryEnabled(country, enabled)

    fun setMarketEnabled(market: String, enabled: Boolean) =
        marketPreferences.setMarketEnabled(market, enabled)

    private fun historySyncDue(): Boolean =
        System.currentTimeMillis() - historySyncedAt >= HISTORY_CACHE_MS

    private suspend fun syncRecentHistory(force: Boolean, generation: Long, activeSource: DataSource) = historyMutex.withLock {
        if (releaseGate.commit(generation) { !force && !historySyncDue() }) return@withLock
        val today = LocalDate.now()
        // The history screen only ever displays supported countries, and the calendar API
        // accepts a country filter: fetching worldwide rows would download several MB and
        // hit the endpoint's event cap for nothing.
        val countryCodes = EconomicCalendarClient.codesFor(CountryPreferences.SUPPORTED_COUNTRIES)
        // Include today's elapsed releases too; history queries still exclude future rows.
        val result = activeSource.calendar(today.minusDays(30), today, countryCodes)
        releaseGate.commit(generation) {
            val merged = mergeCachedTranslations(result.events)
            dao.mergeCalendar(merged.map(EconomicEvent::asEntity))
            publishWarning(result.warning)
            // Partial fallback syncs still observe the TTL; old-source syncs never advance it.
            historySyncedAt = System.currentTimeMillis()
        }
    }

    private fun enrichSourceTranslations(events: List<EconomicEvent>, generation: Long) {
        if (events.isNotEmpty()) repositoryScope.launch {
            runCatching { enrichTranslations(events, generation) }
        }
    }

    private suspend fun enrichTranslations(
        events: List<EconomicEvent>,
        generation: Long = releaseGeneration.value,
    ): List<EconomicEvent> =
        translationMutex.withLock {
            // Re-read cached translations only after acquiring the lock. This prevents a
            // slower failed refresh from overwriting translations saved by another screen.
            var merged = releaseGate.commit(generation) { mergeCachedTranslations(events) }

            // The server owns the shared translation cache, so ask it for every visible name.
            // The lookup is read-only and public: direct mode needs only a backend address, not
            // an access token. A backend row can also predate its translation. Failures are
            // reported instead of swallowed, otherwise the reader sees English with no reason.
            if (dataSourceSettings.value.baseUrl.isNotBlank()) {
                val missing = merged.missingNameTranslations()
                if (missing.isNotEmpty()) {
                    runCatching {
                        missing.chunked(TRANSLATION_SERVER_BATCH).flatMap { batch ->
                            backendSource.translations(batch).entries.map { it.key to it.value }
                        }
                    }.onSuccess { fetched ->
                        releaseGate.commit(generation) {
                            _translationError.value = null
                            if (fetched.isNotEmpty()) {
                                fetched.forEach { (name, pair) ->
                                    dao.updateTranslation(name, pair.first, pair.second)
                                }
                                _translationsUpdated.tryEmit(Unit)
                                merged = mergeCachedTranslations(merged)
                            }
                        }
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        releaseGate.commit(generation) {
                            Log.w(TAG, "Server event-name translations unavailable", error)
                            _translationError.value = error.message ?: "Server translation lookup failed"
                        }
                    }
                }
            }
            // In backend mode the server owns event names; the device must not spend the
            // reader's own key on data the server is responsible for.
            if (source.mode == DataSourceMode.BACKEND) return@withLock merged

            val settings = translationPreferences.settings.value
            val apiKey = translationPreferences.apiKey()
            if (!settings.configured || apiKey == null) return@withLock merged
            // The reader's key is billed per name, so a week of worldwide events is not sent
            // in one refresh; the server cache above already covers whatever it knows.
            val missingNames = merged.missingNameTranslations()
                .take(TRANSLATION_PAID_NAME_LIMIT)
            if (missingNames.isEmpty()) return@withLock merged

            // Batches run with bounded concurrency and fail independently: a single
            // slow or rejected batch no longer stalls the whole page load. Each batch is
            // AI-reviewed and only confirmed translations are returned, so the persistence
            // step below only ever stores reviewed text.
            val semaphore = Semaphore(TRANSLATION_MAX_CONCURRENT_BATCHES)
            val results = withContext(Dispatchers.IO) {
                coroutineScope {
                    missingNames.chunked(TRANSLATION_BATCH_SIZE).map { batch ->
                        async {
                            semaphore.withPermit {
                                runCatching { translationClient.translateVerified(batch, settings, apiKey) }
                            }
                        }
                    }.awaitAll()
                }
            }
            val translated = mutableMapOf<String, Pair<String, String>>()
            var firstError: String? = null
            results.forEach { result ->
                result.onSuccess { batchResult -> translated += batchResult }
                    .onFailure { error -> firstError = firstError ?: error.message ?: "Translation failed" }
            }
            releaseGate.commit(generation) {
                if (translated.isNotEmpty()) {
                    // Only name columns; neither late translations nor old sources may overwrite data.
                    translated.forEach { (name, translation) ->
                        dao.updateTranslation(name, translation.first, translation.second)
                    }
                    _translationsUpdated.tryEmit(Unit)
                }
                _translationError.value = firstError
            }
            merged.map { event ->
                translated[event.event]?.let { (zhCn, zhTw) ->
                    event.copy(eventZhCn = zhCn, eventZhTw = zhTw)
                } ?: event
            }
        }

    /** Distinct titles still lacking a translation. */
    private fun List<EconomicEvent>.missingNameTranslations(): List<String> =
        filter { it.eventZhCn.isNullOrBlank() || it.eventZhTw.isNullOrBlank() }
            .map(EconomicEvent::event)
            .distinct()

    /** Name-keyed translations already stored locally; also used to refresh rendered lists. */
    suspend fun cachedTranslations(names: Collection<String>): Map<String, Pair<String, String>> {
        val distinct = names.map(String::trim).filter(String::isNotEmpty).distinct()
        if (distinct.isEmpty()) return emptyMap()
        return distinct.chunked(500)
            .flatMap { dao.translations(it) }
            .associate { it.event to (it.eventZhCn to it.eventZhTw) }
    }

    private suspend fun mergeCachedTranslations(events: List<EconomicEvent>): List<EconomicEvent> {
        if (events.isEmpty()) return events
        val cached = cachedTranslations(events.map(EconomicEvent::event))
        if (cached.isEmpty()) return events
        return events.map { event ->
            val translation = cached[event.event]
            if (translation == null) event else event.copy(
                eventZhCn = event.eventZhCn ?: translation.first,
                eventZhTw = event.eventZhTw ?: translation.second,
            )
        }
    }

    private data class CachedMarket(val savedAt: Long, val value: MarketResponse)

    companion object {
        private const val TAG = "MacroRepository"
        private const val MARKET_CACHE_MS = 30_000L
        private const val UPCOMING_CACHE_MS = 5 * 60_000L
        private const val HISTORY_CACHE_MS = 10 * 60_000L
        const val HISTORY_PAGE_SIZE = 8
        const val SUPPORTED_API_VERSION = 1

        private const val HISTORY_RETENTION_DAYS = 730L
        private const val TRANSLATION_BATCH_SIZE = 5

        /** The server caps one lookup at 100 names. */
        private const val TRANSLATION_SERVER_BATCH = 100

        /** Ceiling for names translated with the reader's own key in a single refresh. */
        private const val TRANSLATION_PAID_NAME_LIMIT = 40
        private const val TRANSLATION_MAX_CONCURRENT_BATCHES = 4

        /** Providers replaced by EconomicCalendarClient; their cached rows are dropped on refresh. */
        private val LEGACY_CALENDAR_PROVIDERS = listOf("trading_economics")
    }
}
