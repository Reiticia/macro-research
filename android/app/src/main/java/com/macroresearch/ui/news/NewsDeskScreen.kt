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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.macroresearch.R
import com.macroresearch.data.MacroRepository
import com.macroresearch.data.ReaderNewsSources
import com.macroresearch.data.remote.NewsArticle
import com.macroresearch.data.remote.newsPublishedInstant
import com.macroresearch.ui.viewModelFactory
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
    val settings by repository.translationSettings.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val article = state.article
    BackHandler(article != null) { vm.closeArticle() }
    if (article != null && enabled) {
        NewsReaderScreen(state, settings.configured, vm::closeArticle, { vm.open(article) },
            vm::ask, vm::cancelAi, vm::clearConversation)
        return
    }
    val sources = state.headlines.articles.map { it.source }.distinct()
    val articles = state.headlines.articles.filter {
        (source == null || it.source == source) && (query.isBlank() ||
            it.title.contains(query.trim(), true) || it.summary.orEmpty().contains(query.trim(), true))
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
                            if (state.headlines.status in setOf("search_unavailable", "partial_results")) {
                                Text(stringResource(if (state.headlines.status == "search_unavailable") R.string.news_failed else R.string.news_partial),
                                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (articles.isEmpty() && !state.loading) item { Text(stringResource(R.string.news_empty), Modifier.padding(vertical = 24.dp)) }
                    items(articles, key = { it.url }) { item -> NewsHeadline(item) { vm.open(item) } }
                    item { TextButton(onClick = { repository.setNewsReaderEnabled(false) }) { Text(stringResource(R.string.news_disable)) } }
                }
            }
        }
    }
}

@Composable
private fun NewsHeadline(article: NewsArticle, onClick: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(ReaderNewsSources.label(article.source), color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        TextButton(onClick = onClick, contentPadding = PaddingValues(0.dp)) {
            Text(article.title, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        }
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
