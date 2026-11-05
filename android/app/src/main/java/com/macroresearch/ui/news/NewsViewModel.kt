package com.macroresearch.ui.news

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macroresearch.data.MacroRepository
import com.macroresearch.data.ReaderHeadlines
import com.macroresearch.data.DataSourceMode
import com.macroresearch.data.remote.BackendException
import com.macroresearch.data.remote.BackendUnauthorizedException
import com.macroresearch.data.remote.AiConversationMessage
import com.macroresearch.data.remote.ArticleReading
import com.macroresearch.data.remote.NewsArticle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class NewsTitleKey(val language: String, val original: String)

internal data class NewsDeskState(
    val headlines: ReaderHeadlines = ReaderHeadlines(),
    val loading: Boolean = false,
    val article: NewsArticle? = null,
    val reading: ArticleReading? = null,
    val readingLoading: Boolean = false,
    val messages: List<AiConversationMessage> = emptyList(),
    val aiLoading: Boolean = false,
    val aiError: Boolean = false,
    val backendNews: Boolean = false,
    val loadingMore: Boolean = false,
    val moreFailed: Boolean = false,
    val titleTranslations: Map<NewsTitleKey, String> = emptyMap(),
    val titleTranslationFailed: Boolean = false,
) {
    fun title(article: NewsArticle, language: String): String =
        titleTranslations[NewsTitleKey(language, article.title)] ?: article.title
}

internal class NewsViewModel(private val repository: MacroRepository) : ViewModel() {
    private val _state = MutableStateFlow(NewsDeskState())
    val state = _state.asStateFlow()
    private var refreshJob: Job? = null
    private var moreJob: Job? = null
    private var generation = 0
    private var articleJob: Job? = null
    private var aiJob: Job? = null
    private val readings = linkedMapOf<String, ArticleReading>()
    private val conversations = linkedMapOf<String, List<AiConversationMessage>>()

    init {
        viewModelScope.launch {
            combine(repository.newsTitleTranslationEnabled, repository.translationSettings) { enabled, settings -> enabled to settings }
                .collect { _state.update { it.copy(titleTranslations = emptyMap(), titleTranslationFailed = false) } }
        }
        viewModelScope.launch {
            combine(repository.newsReaderEnabled, repository.newsReaderInternationalEnabled,
                repository.newsReader.settings) { enabled, international, settings ->
                Triple(enabled, international, settings)
            }.collectLatest { (enabled, _, settings) ->
                generation++
                moreJob?.cancel()
                moreJob?.join()
                refreshJob?.cancel()
                refreshJob?.join()
                closeArticle()
                readings.clear()
                conversations.clear()
                _state.value = NewsDeskState(backendNews = settings.mode == DataSourceMode.BACKEND)
                val cached = repository.newsReader.cached()
                _state.update { it.copy(headlines = cached) }
                if (enabled) refresh()
            }
        }
    }

    fun refresh() {
        if (!repository.newsReaderEnabled.value || refreshJob?.isActive == true) return
        generation++
        moreJob?.cancel()
        refreshJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, loadingMore = false, moreFailed = false) }
            try {
                val result = repository.newsReader.refresh(repository.newsReaderInternationalEnabled.value)
                _state.update { it.copy(headlines = result, loading = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                val status = when {
                    error is BackendUnauthorizedException -> "backend_unauthorized"
                    error is BackendException && error.statusCode in listOf(404,501) -> "backend_unsupported"
                    error is BackendException && error.statusCode == 429 -> "backend_rate_limited"
                    else -> "search_unavailable"
                }
                _state.update { it.copy(loading = false, headlines = it.headlines.copy(status = status, stale = true)) }
            }
        }
    }

    fun loadMore() {
        val current = _state.value
        val offset = current.headlines.nextOffset ?: return
        if (!current.backendNews || current.loading || current.loadingMore || current.headlines.articles.size >= 5000 ||
            !repository.newsReaderEnabled.value) return
        val revision = generation
        moreJob = viewModelScope.launch {
            _state.update { it.copy(loadingMore = true, moreFailed = false) }
            try {
                val page = repository.newsReader.more(offset)
                if (revision != generation) return@launch
                _state.update { state -> state.copy(loadingMore = false, headlines = state.headlines.copy(
                    articles = (state.headlines.articles + page.articles).distinctBy { it.url }.take(5000),
                    nextOffset = page.nextOffset)) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (revision == generation) _state.update { it.copy(loadingMore = false, moreFailed = true) } }
        }
    }

    /** Called only for visible rows (or the open article), within the UI's foreground lifecycle. */
    suspend fun translateTitles(articles: List<NewsArticle>, language: String) {
        if (!repository.newsTitleTranslationEnabled.value || !repository.translationSettings.value.configured) return
        val pending = articles.map { it.title }.distinct()
            .filterNot { NewsTitleKey(language, it) in _state.value.titleTranslations }
            .take(com.macroresearch.data.NewsTitleTranslator.BATCH_SIZE)
        if (pending.isEmpty()) return
        val source = repository.newsReader.settings.value
        val result = try { repository.translateNewsTitles(pending, language) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            _state.update { it.copy(titleTranslationFailed = true) }
            return
        }
        if (source != repository.newsReader.settings.value) return
        _state.update { current ->
            val translated = current.titleTranslations + result.titles.mapKeys { NewsTitleKey(language, it.key) }
            current.copy(titleTranslations = translated.entries.toList().takeLast(512).associate { it.toPair() },
                titleTranslationFailed = result.failed)
        }
    }

    fun open(article: NewsArticle) {
        if (!repository.newsReaderEnabled.value) return
        articleJob?.cancel()
        aiJob?.cancel()
        _state.update { it.copy(article = article, reading = readings[article.url],
            readingLoading = readings[article.url] == null, messages = conversations[article.url].orEmpty(),
            aiLoading = false, aiError = false) }
        if (readings.containsKey(article.url)) return
        articleJob = viewModelScope.launch {
            try {
                val reading = repository.newsReader.read(article)
                if (_state.value.article?.url != article.url) return@launch
                if (reading != null) {
                    readings[article.url] = reading
                    if (readings.size > 5) readings.remove(readings.keys.first())
                }
                _state.update { it.copy(reading = reading, readingLoading = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _state.update { it.copy(readingLoading = false) } }
        }
    }

    fun closeArticle() {
        articleJob?.cancel()
        aiJob?.cancel()
        _state.update { it.copy(article = null, reading = null, readingLoading = false, aiLoading = false, aiError = false) }
    }

    fun clearConversation() {
        if (_state.value.aiLoading) return
        _state.value.article?.let { conversations.remove(it.url) }
        _state.update { it.copy(messages = emptyList(), aiError = false) }
    }

    fun cancelAi() {
        aiJob?.cancel()
        _state.update { it.copy(aiLoading = false) }
    }

    fun ask(question: String, languageTag: String, includeQuotes: Boolean) {
        val current = _state.value
        val article = current.article ?: return
        if (current.aiLoading || current.readingLoading || question.isBlank() || question.length > 1000 ||
            !repository.translationSettings.value.configured || !repository.newsReaderEnabled.value) return
        aiJob = viewModelScope.launch {
            _state.update { it.copy(aiLoading = true, aiError = false) }
            try {
                val answer = repository.analyzeNews(article, current.reading, current.messages,
                    question.trim(), languageTag, includeQuotes)
                val messages = (current.messages + AiConversationMessage("user", question.trim()) + answer).takeLast(16)
                conversations[article.url] = messages
                if (conversations.size > 10) conversations.remove(conversations.keys.first())
                _state.update { it.copy(messages = messages, aiLoading = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _state.update { it.copy(aiLoading = false, aiError = true) } }
        }
    }
}
