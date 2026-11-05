package com.macroresearch.ui.news

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import com.macroresearch.data.DataSourceMode
import androidx.lifecycle.viewmodel.compose.viewModel
import com.macroresearch.R
import com.macroresearch.data.MacroRepository
import com.macroresearch.data.ReaderNewsSources
import com.macroresearch.data.remote.NewsArticle
import com.macroresearch.data.remote.newsPublishedInstant
import com.macroresearch.ui.viewModelFactory
import com.macroresearch.ui.common.appLocale
import com.macroresearch.ui.settings.AppLanguage
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewsDeskScreen(repository: MacroRepository, onBack: () -> Unit) {
    val vm: NewsViewModel = viewModel(factory = viewModelFactory { NewsViewModel(repository) })
    val state by vm.state.collectAsStateWithLifecycle()
    val enabled by repository.newsReaderEnabled.collectAsStateWithLifecycle()
    val internationalEnabled by repository.newsReaderInternationalEnabled.collectAsStateWithLifecycle()
    val dataSettings by repository.newsReader.settings.collectAsStateWithLifecycle()
    val backendNews = dataSettings.mode == DataSourceMode.BACKEND
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(vm, lifecycle, enabled, backendNews) {
        if (enabled && backendNews) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(60_000)
                if (vm.state.value.headlines.articles.size <= 100 && vm.state.value.headlines.status !in setOf("backend_unauthorized", "backend_unsupported", "backend_rate_limited"))
                    vm.refresh()
            }
        }
    }
    val settings by repository.translationSettings.collectAsStateWithLifecycle()
    val titleTranslationEnabled by repository.newsTitleTranslationEnabled.collectAsStateWithLifecycle()
    val language = AppLanguage.fromLocale(appLocale())
    val translateTitles = titleTranslationEnabled && settings.configured
    var query by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val article = state.article
    BackHandler(article != null) { vm.closeArticle() }
    val sources = state.headlines.articles.map { it.source }.distinct()
    LaunchedEffect(sources) { if (source != null && source !in sources) source = null }
    fun title(item: NewsArticle) = if (translateTitles) state.title(item, language.tag) else item.title
    val articles = state.headlines.articles.filter {
        (source == null || it.source == source) && (query.isBlank() ||
            it.title.contains(query.trim(), true) || title(it).contains(query.trim(), true) ||
            it.summary.orEmpty().contains(query.trim(), true))
    }
    LaunchedEffect(vm, lifecycle, enabled, translateTitles, settings, dataSettings, language.tag, articles, article) {
        if (enabled && translateTitles) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (article != null) vm.translateTitles(listOf(article), language.tag)
            else snapshotFlow { listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String } }
                .distinctUntilChanged().collectLatest { urls ->
                    delay(250) // Coalesce scrolling; never translate an entire archive in the background.
                    vm.translateTitles(articles.filter { it.url in urls }, language.tag)
                }
        }
    }
    if (article != null && enabled) {
        NewsReaderScreen(state, settings.configured, vm::closeArticle, { vm.open(article) },
            vm::ask, vm::cancelAi, vm::clearConversation, translatedTitle = title(article))
        return
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.news_title), fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) } },
            actions = {
                if (enabled) IconButton(onClick = vm::refresh, enabled = !state.loading) {
                    Icon(Icons.Outlined.Refresh, stringResource(R.string.news_refresh))
                }
            })
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 900.dp).fillMaxSize(), state = listState, contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.news_kicker), color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.news_subtitle), style = MaterialTheme.typography.headlineMedium,
                            fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium)
                        HorizontalDivider(Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.primary)
                    }
                }
                if (!enabled) {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text(stringResource(R.string.news_enable_title), style = MaterialTheme.typography.titleLarge)
                                Text(stringResource(R.string.news_enable_note), style = MaterialTheme.typography.bodyMedium)
                                Button(onClick = { repository.setNewsReaderEnabled(true) }) { Text(stringResource(R.string.news_enable)) }
                            }
                        }
                    }
                } else {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (!backendNews) FilterChip(selected = internationalEnabled,
                                onClick = { repository.setNewsReaderInternationalEnabled(!internationalEnabled) },
                                label = { Text(stringResource(R.string.news_international)) })
                            Text(stringResource(if (backendNews) R.string.news_backend_note else R.string.news_direct_note), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    item {
                        NewsTitleTranslationControl(titleTranslationEnabled, settings.configured, language.nativeName,
                            state.titleTranslationFailed, repository::setNewsTitleTranslationEnabled)
                    }
                    item {
                        OutlinedTextField(query, { if (it.length <= 200) query = it }, singleLine = true,
                            label = { Text(stringResource(R.string.news_search)) }, modifier = Modifier.fillMaxWidth())
                    }
                    item {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = source == null, onClick = { source = null }, label = { Text(stringResource(R.string.news_all)) })
                            sources.forEach { item -> FilterChip(selected = source == item, onClick = { source = item },
                                label = { Text(ReaderNewsSources.label(item)) }) }
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(stringResource(R.string.news_count, articles.size), style = MaterialTheme.typography.labelMedium)
                                Text(stringResource(R.string.news_cached).takeIf { state.headlines.status == "cached" }
                                    ?: state.headlines.fetchedAt?.let { stringResource(R.string.news_updated, newsTime(it, LocalConfiguration.current.locales[0])) }.orEmpty(),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (state.headlines.stale && state.headlines.status != "cached")
                                Text(stringResource(R.string.news_cached), style = MaterialTheme.typography.bodySmall)
                            state.headlines.sources.forEach { item ->
                                val status = if (state.headlines.status !in setOf("articles_found", "partial_results", "no_articles_found") &&
                                    item.status == "fresh") "stale" else item.status
                                Text(item.name + " · " + stringResource(when (status) {
                                    "fresh" -> R.string.news_source_fresh
                                    "stale" -> R.string.news_source_stale
                                    else -> R.string.news_source_unavailable
                                }), style = MaterialTheme.typography.labelSmall)
                            }
                            if (state.headlines.status in setOf("search_unavailable", "partial_results",
                                "backend_unauthorized", "backend_unsupported", "backend_rate_limited")) {
                                Text(stringResource(when (state.headlines.status) {
                                    "partial_results" -> R.string.news_partial
                                    "backend_unauthorized" -> R.string.news_backend_unauthorized
                                    "backend_unsupported" -> R.string.news_backend_unsupported
                                    "backend_rate_limited" -> R.string.news_backend_rate_limited
                                    else -> R.string.news_failed
                                }),
                                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (articles.isEmpty() && !state.loading) item { Text(stringResource(R.string.news_empty), Modifier.padding(vertical = 24.dp)) }
                    items(articles, key = { it.url }) { item -> NewsHeadline(item, title(item)) { vm.open(item) } }
                    if (backendNews && state.headlines.nextOffset != null && state.headlines.articles.size < 5000) item {
                        Column {
                            if (state.moreFailed) Text(stringResource(R.string.news_more_failed), color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = vm::loadMore, enabled = !state.loadingMore && !state.loading) {
                                Text(stringResource(if (state.loadingMore) R.string.news_more_loading else R.string.news_more))
                            }
                        }
                    }
                    if (backendNews && state.headlines.articles.size >= 5000) item { Text(stringResource(R.string.news_archive_limit)) }
                    item { TextButton(onClick = { repository.setNewsReaderEnabled(false) }) { Text(stringResource(R.string.news_disable)) } }
                }
            }
        }
    }
}

@Composable
internal fun NewsHeadline(article: NewsArticle, title: String = article.title, onClick: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(ReaderNewsSources.label(article.source), color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        TextButton(onClick = onClick, contentPadding = PaddingValues(0.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        }
        if (title != article.title) Text(stringResource(R.string.news_title_translated),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        article.summary?.takeIf(String::isNotBlank)?.let {
            Text(it.take(140), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(newsTime(article.publishedAt, locale), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onClick) { Text(stringResource(R.string.news_read)) }
        }
        HorizontalDivider()
    }
}

@Composable
internal fun newsTime(value: String?, locale: Locale): String {
    val time = newsPublishedInstant(value) ?: return stringResource(R.string.news_time_unknown)
    return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale).withZone(ZoneId.systemDefault()).format(time)
}
