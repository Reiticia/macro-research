package com.macroresearch.ui.news

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macroresearch.data.MacroRepository
import com.macroresearch.data.ReaderHeadlines
import com.macroresearch.data.remote.AiConversationMessage
import com.macroresearch.data.remote.ArticleReading
import com.macroresearch.data.remote.NewsArticle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class NewsDeskState(
    val headlines: ReaderHeadlines = ReaderHeadlines(),
    val loading: Boolean = false,
    val article: NewsArticle? = null,
    val reading: ArticleReading? = null,
    val readingLoading: Boolean = false,
    val messages: List<AiConversationMessage> = emptyList(),
    val aiLoading: Boolean = false,
    val aiError: Boolean = false,
)

internal class NewsViewModel(private val repository: MacroRepository) : ViewModel() {
    private val _state = MutableStateFlow(NewsDeskState())
    val state = _state.asStateFlow()
    private var refreshJob: Job? = null
    private var articleJob: Job? = null
    private var aiJob: Job? = null
    private val readings = linkedMapOf<String, ArticleReading>()
    private val conversations = linkedMapOf<String, List<AiConversationMessage>>()

    init {
        viewModelScope.launch {
            val cached = repository.newsReader.cached()
            _state.update { it.copy(headlines = cached) }
            repository.newsReaderEnabled.collectLatest { enabled ->
                if (enabled) refresh() else {
                    refreshJob?.cancel()
                    closeArticle()
                    _state.update { it.copy(loading = false) }
                }
            }
        }
    }

    fun refresh() {
        if (!repository.newsReaderEnabled.value || refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            try {
                val result = repository.newsReader.refresh()
                _state.update { it.copy(headlines = result, loading = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                _state.update { it.copy(loading = false, headlines = it.headlines.copy(status = "search_unavailable")) }
            }
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
