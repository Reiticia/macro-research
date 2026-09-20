package com.macroresearch.data

import android.util.Log
import com.macroresearch.data.local.AiAnalysisEntity
import com.macroresearch.data.local.AnalysisDao
import com.macroresearch.data.local.EventDao
import com.macroresearch.data.local.FollowedEventEntity
import com.macroresearch.data.local.asEntity
import com.macroresearch.data.local.asExternalModel
import com.macroresearch.data.model.AiAnalysis
import com.macroresearch.data.model.AiUsage
import com.macroresearch.data.model.AnalysisReport
import com.macroresearch.data.model.EconomicEvent
import com.macroresearch.data.model.EventDetailResponse
import com.macroresearch.data.model.EventObservation
import com.macroresearch.data.model.MarketQuotesResponse
import com.macroresearch.data.model.MarketResponse
import com.macroresearch.data.remote.AiAnalysisClient
import com.macroresearch.data.remote.BackendException
import com.macroresearch.data.remote.BackendMeta
import com.macroresearch.data.remote.BackendUnauthorizedException
import com.macroresearch.data.remote.EconomicCalendarClient
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
    private val backendPreferences: BackendPreferences,
    private val translationClient: TranslationClient,
    private val aiAnalysisClient: AiAnalysisClient,
    private val dao: EventDao,
    private val analysisDao: AnalysisDao,
    private val countryPreferences: CountryPreferences,
    private val marketPreferences: MarketPreferences,
    private val translationPreferences: TranslationPreferences,
) {
    val selectedCountries: StateFlow<Set<String>> = countryPreferences.selectedCountries
    val selectedMarkets: StateFlow<List<String>> = marketPreferences.selectedMarkets
    val translationSettings: StateFlow<TranslationSettings> = translationPreferences.settings
    val dataSourceSettings: StateFlow<BackendSettings> = backendPreferences.settings
    private val _translationError = MutableStateFlow<String?>(null)
    val translationError = _translationError.asStateFlow()
    val proxyAddress = networkPreferences.address
    val analysisMethod: StateFlow<AnalysisMethod> = analysisPreferences.method

    /** The active plane. Read per call so a settings change applies immediately. */
    private val source: DataSource
        get() = if (dataSourceSettings.value.mode == DataSourceMode.BACKEND) {
            backendSource
        } else {
            directSource
        }

    fun setAnalysisMethod(method: AnalysisMethod) = analysisPreferences.setMethod(method)

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
        if (mode == dataSourceSettings.value.mode) return
        backendPreferences.setMode(mode)
        dao.clearCache()
        analysisDao.clearAll()
        marketCache.clear()
        historySyncedAt = 0L
        networkPreferences.clearUpcomingSync()
        publishWarning(null)
    }

    fun saveBackendSettings(baseUrl: String, token: String) {
        backendPreferences.save(baseUrl, token)
        backendPreferences.clearVerification()
        networkPreferences.clearUpcomingSync()
        historySyncedAt = 0L
    }

    /** Enables the explicit plain-HTTP escape hatch for a backend address. */
    fun setAllowBackendCleartext(allowed: Boolean) {
        backendPreferences.setAllowCleartext(allowed)
        backendPreferences.clearVerification()
    }

    fun clearBackendSettings() {
        backendPreferences.clear()
        backendPreferences.setMode(DataSourceMode.DIRECT)
    }

    /** Validates the saved backend address and token against `/api/v1/meta`. */
    suspend fun verifyBackend(): BackendMeta {
        val meta = backendSource.meta()
        require(meta.apiVersion == SUPPORTED_API_VERSION) {
            "Backend speaks API version ${meta.apiVersion}; this app needs $SUPPORTED_API_VERSION"
        }
        backendPreferences.markVerified(meta.version)
        return meta
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

    private val releaseRefresher = EventReleaseRefresher(calendarClient, dao)
    private val marketCache = ConcurrentHashMap<Long, CachedMarket>()
    private val upcomingMutex = Mutex()
    private val historyMutex = Mutex()
    private val translationMutex = Mutex()
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var historySyncedAt = 0L

    fun observeUpcoming(): Flow<List<EconomicEvent>> =
        dao.observeUpcoming(Instant.now().toString()).map { events ->
            events.map { it.asExternalModel() }
        }

    fun observeEvent(id: Long): Flow<EconomicEvent?> =
        dao.observeEvent(id).map { it?.asExternalModel() }

    fun observeFollowed(id: Long): Flow<Boolean> = dao.observeFollowed(id)

    suspend fun isFollowed(id: Long): Boolean = dao.isFollowed(id)

    suspend fun refreshUpcoming(days: Int = 7) = upcomingMutex.withLock {
        // Successful events are already in Room. Reopening the app inside this window should
        // render them immediately instead of repeating the same fragile calendar request. Name
        // enrichment still needs a retry: the previous process may have stopped after saving the
        // rows but before its background translation lookup completed.
        if (networkPreferences.upcomingSyncFresh(UPCOMING_CACHE_MS)) {
            val now = Instant.now()
            val cached = dao.cachedRange(now.toString(), now.plusSeconds(days * 86_400L).toString())
                .map { it.asExternalModel() }
            if (cached.isNotEmpty()) {
                repositoryScope.launch { runCatching { enrichTranslations(cached) } }
            }
            return@withLock
        }
        // Drop rows cached by calendar providers that are no longer used so the
        // upcoming list cannot show the same event twice after an app update.
        dao.deleteByProviders(LEGACY_CALENDAR_PROVIDERS)
        // Days follow the device zone so the refresh window matches the times shown on cards.
        val today = LocalDate.now()
        val events = try {
            fetchCalendar(today.minusDays(1), today.plusDays(days.toLong()))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Publish before propagating: the list falls back to the cache, and the UI needs the
            // reason to explain why, especially when the backend is unreachable.
            publishWarning(warningFor(error))
            throw error
        }
        val merged = dao.mergeCalendar(mergeCachedTranslations(events).map(EconomicEvent::asEntity))
            .map { it.asExternalModel() }
        val selected = selectedCountries.value
        val localToday = LocalDate.now()
        val now = Instant.now()
        val priorityEvents = merged
            .filter { it.country in selected }
            .filter { event ->
                runCatching { !Instant.parse(event.eventTime).isBefore(now) }.getOrDefault(false)
            }
            .sortedWith(
                compareBy<EconomicEvent> { event ->
                    val date = runCatching {
                        Instant.parse(event.eventTime).atZone(ZoneId.systemDefault()).toLocalDate()
                    }.getOrNull()
                    if (date == localToday) 0 else 1
                }.thenBy { it.eventTime },
            )
            .take(30)
        if (merged.isNotEmpty()) {
            // Home renders from Room, so translations may arrive after the list is shown.
            // The server lookup is free and covers every visible name; the reader's own key
            // is only spent on the most relevant titles (see TRANSLATION_PAID_NAME_LIMIT).
            repositoryScope.launch { runCatching { enrichTranslations(merged) } }
        }
        dao.deleteOlderThan(
            today.minusDays(HISTORY_RETENTION_DAYS)
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toString(),
        )
        // Mark only after the event rows are committed, so a crash cannot leave an empty cache
        // advertised as fresh.
        networkPreferences.markUpcomingSynced()
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
        if (source.mode == DataSourceMode.BACKEND) {
            try {
                source.eventDetail(id)?.let { detail ->
                    // Keep a translation already cached on the device if this server event was
                    // stored before the server-side backfill completed.
                    val event = mergeCachedTranslations(listOf(detail.event)).single()
                    dao.upsert(listOf(event.asEntity()))
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
        if (source.mode == DataSourceMode.BACKEND) {
            val detail = source.refreshRelease(id)
            if (detail != null) {
                dao.upsert(listOf(detail.event.asEntity()))
                return detail
            }
        }
        val warning = releaseRefresher.refresh(id).warning
        publishWarning(warning)
        return event(id)
    }

    suspend fun analysis(id: Long): AnalysisReport {
        val event = event(id).event
        return if (translationSettings.value.configured) {
            directSource.ruleAnalysis(event) { directSource.eventMarket(event) }
        } else {
            source.ruleAnalysis(event) { market(id) }
        }
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

    /**
     * Builds the AI briefing for a released event and stores it under its own method, so the
     * three methods never overwrite each other.
     *
     * Rate limiting mirrors the server: a regeneration inside the cooldown window does not hit
     * the network, it returns the stored result marked `rateLimited`, and a backend that is
     * itself throttled does the same for a device with an empty cache.
     */
    suspend fun sharedAiAnalysis(eventId: Long, languageTag: String): AiAnalysis? {
        check(dataSourceSettings.value.baseUrl.isNotBlank()) {
            "Configure a backend address to read shared analysis"
        }
        val event = localEvent(eventId).event
        return backendSource.cachedAiAnalysis(event, languageTag, analysisMethod.value)
    }

    suspend fun submitAnalysisFeedback(language: String, analysis: AiAnalysis, message: String) {
        check(!translationSettings.value.configured && dataSourceSettings.value.configured) {
            "Feedback is only available for shared server analysis"
        }
        backendSource.feedback(analysis.eventId, language, analysis.method, analysis.revision, message)
    }

    suspend fun generateAiAnalysis(
        eventId: Long,
        languageTag: String,
        regenerate: Boolean = false,
    ): AiAnalysis {
        check(translationSettings.value.configured) { "A personal AI key is required to generate analysis" }
        val method = analysisPreferences.method.value
        val cached = analysisDao.analysis(eventId, method.wireValue)
        if (regenerate && cached != null) {
            val elapsed = System.currentTimeMillis() - cached.generatedAtEpochMs
            val remaining = AI_REGENERATE_COOLDOWN_MS - elapsed
            if (remaining > 0) {
                return cached.toModel().copy(
                    fromCache = true,
                    rateLimited = true,
                    retryAfterSeconds = remaining / 1000,
                )
            }
        }
        val event = dao.event(eventId)?.asExternalModel()
            ?: error("Event is not available in the local cache")
        require(event.actual != null) { "The release has no published value yet" }
        // Personal model payloads/results never go through the backend, in either data mode.
        val report = directSource.ruleAnalysis(event) { directSource.eventMarket(event) }
        val nextRevision = (cached?.revision ?: 0) + 1
        val analysis = directSource.aiAnalysis(
            AiAnalysisRequest(
                event = event,
                report = report,
                languageTag = languageTag,
                method = method,
                revision = nextRevision,
                regenerate = regenerate || cached != null,
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
        analysisDao.upsert(analysis.toEntity())
        return analysis
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
        rateLimited = rateLimited,
        retryAfterSeconds = retryAfterSeconds,
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
        val response = source.eventMarket(event)
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
    ): List<EconomicEvent> {
        val selected = countries.distinct()
        if (selected.isEmpty()) return emptyList()
        if (source.mode == DataSourceMode.BACKEND) {
            return backendHistory(selected, category, limit, offset, forceRefresh)
        }
        var page = dao.history(Instant.now().toString(), selected, category, limit, offset)
            .map { it.asExternalModel() }
        // Room is the source of truth. Only backfill the first page when it cannot provide a full
        // page, or when the user explicitly asks to refresh. Entering History no longer blocks on
        // the same unreliable network request every time.
        if (offset == 0 && (forceRefresh || (page.size < limit && historySyncDue()))) {
            try {
                syncRecentHistory(forceRefresh)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                publishWarning(warningFor(error))
            }
            page = dao.history(Instant.now().toString(), selected, category, limit, offset)
                .map { it.asExternalModel() }
        }
        // Network failure never hides or invalidates locally stored history, including an empty
        // database on first run. The typed warning explains the offline state without a spinner.
        if (page.isNotEmpty()) {
            repositoryScope.launch { runCatching { enrichTranslations(page) } }
        }
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
        forceRefresh: Boolean,
    ): List<EconomicEvent> {
        if (!forceRefresh && offset == 0 && !historySyncDue()) {
            val cached = dao.history(Instant.now().toString(), countries, category, limit, offset)
                .map { it.asExternalModel() }
            if (cached.size >= limit) return cached
        }
        val page = try {
            source.history(countries, category, limit, offset)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            publishWarning(warningFor(error))
            return dao.history(Instant.now().toString(), countries, category, limit, offset)
                .map { it.asExternalModel() }
        }
        if (page.isNotEmpty()) {
            dao.upsert(page.map(EconomicEvent::asEntity))
            // A server row can predate its translation; ask the shared cache for the rest.
            repositoryScope.launch { runCatching { enrichTranslations(page) } }
        }
        historySyncedAt = System.currentTimeMillis()
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

    suspend fun setFollowed(id: Long, followed: Boolean) {
        if (followed) dao.follow(FollowedEventEntity(id)) else dao.unfollow(id)
    }

    fun setCountries(countries: Set<String>) = countryPreferences.setCountries(countries)

    fun setCountryEnabled(country: String, enabled: Boolean) =
        countryPreferences.setCountryEnabled(country, enabled)

    fun setMarketEnabled(market: String, enabled: Boolean) =
        marketPreferences.setMarketEnabled(market, enabled)

    private fun historySyncDue(): Boolean =
        System.currentTimeMillis() - historySyncedAt >= HISTORY_CACHE_MS

    private suspend fun syncRecentHistory(force: Boolean = false) = historyMutex.withLock {
        if (!force && !historySyncDue()) return@withLock
        val today = LocalDate.now()
        // The history screen only ever displays supported countries, and the calendar API
        // accepts a country filter: fetching worldwide rows would download several MB and
        // hit the endpoint's event cap for nothing.
        val countryCodes = EconomicCalendarClient.codesFor(CountryPreferences.SUPPORTED_COUNTRIES)
        // Include today's elapsed releases too; history queries still exclude future rows.
        val result = source.calendar(today.minusDays(30), today, countryCodes)
        publishWarning(result.warning)
        val merged = mergeCachedTranslations(result.events)
        dao.mergeCalendar(merged.map(EconomicEvent::asEntity))
        // Even a weekly fallback is a completed network sync. Observe the normal TTL instead of
        // immediately hitting the rate-limited feed again; manual refresh can still force it.
        historySyncedAt = System.currentTimeMillis()
    }

    private suspend fun enrichTranslations(events: List<EconomicEvent>): List<EconomicEvent> =
        translationMutex.withLock {
            // Re-read cached translations only after acquiring the lock. This prevents a
            // slower failed refresh from overwriting translations saved by another screen.
            var merged = mergeCachedTranslations(events)

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
                        _translationError.value = null
                        if (fetched.isNotEmpty()) {
                            fetched.forEach { (name, pair) ->
                                dao.updateTranslation(name, pair.first, pair.second)
                            }
                            _translationsUpdated.tryEmit(Unit)
                            merged = mergeCachedTranslations(merged)
                        }
                    }.onFailure { error ->
                        Log.w(TAG, "Server event-name translations unavailable", error)
                        _translationError.value =
                            error.message ?: "Server translation lookup failed"
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
            if (translated.isNotEmpty()) {
                // Only update name columns. A slow AI response must never overwrite newly
                // published actuals/status with the pre-release snapshot it started from.
                translated.forEach { (name, translation) ->
                    dao.updateTranslation(name, translation.first, translation.second)
                }
                // Lists render before enrichment finishes; this tells them to re-read.
                _translationsUpdated.tryEmit(Unit)
            }
            _translationError.value = firstError
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

        /** Mirrors the server's per-(event, method) cooldown; see [limits] in the backend config. */
        internal const val AI_REGENERATE_COOLDOWN_MS = 10 * 60_000L
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
