package com.macroresearch.ui.news

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.macroresearch.R
import com.macroresearch.data.ReaderNewsSources
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NewsReaderScreen(
    state: NewsDeskState,
    configured: Boolean,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onAsk: (String, String, Boolean) -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
) {
    val article = state.article ?: return
    var webMode by rememberSaveable(article.url) { mutableStateOf(false) }
    var fontStep by rememberSaveable { mutableIntStateOf(1) }
    var sideBySide by rememberSaveable { mutableStateOf(false) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val direction = LocalLayoutDirection.current
    val fontDescription = stringResource(R.string.news_font)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        val openAi = { if (wide) sideBySide = true else scope.launch { drawer.open() }; Unit }
        val closeAi = { if (wide) sideBySide = false else scope.launch { drawer.close() }; Unit }
        BackHandler(!wide && drawer.isOpen) { scope.launch { drawer.close() } }
        val panel: @Composable () -> Unit = {
            NewsAiPanel(state, configured, closeAi, onAsk, onStop, onClear)
        }
        val reader: @Composable () -> Unit = {
            Scaffold(
                topBar = {
                    TopAppBar(title = { Text(stringResource(R.string.news_reader), style = MaterialTheme.typography.titleMedium) },
                        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) } },
                        actions = {
                            if (!webMode) TextButton(onClick = { fontStep = (fontStep + 1) % 3 },
                                modifier = Modifier.semantics { contentDescription = fontDescription }) { Text("Aa") }
                            IconButton(onClick = openAi) { Icon(Icons.Outlined.AutoAwesome, stringResource(R.string.news_ai)) }
                        })
                },
                floatingActionButton = {
                    if (!(wide && sideBySide)) ExtendedFloatingActionButton(onClick = openAi,
                        icon = { Icon(Icons.Outlined.AutoAwesome, null) }, text = { Text(stringResource(R.string.news_ai)) })
                },
            ) { padding ->
                if (webMode) {
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        TextButton(onClick = { webMode = false }) { Text(stringResource(R.string.news_text_mode)) }
                        NewsWebPage(state.reading?.url ?: article.url, Modifier.weight(1f))
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(24.dp, 12.dp, 24.dp, 100.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(stringResource(R.string.news_provider, ReaderNewsSources.label(article.source)),
                                    color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                SelectionContainer { Text(article.title, style = MaterialTheme.typography.headlineMedium,
                                    fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold) }
                                Text(newsTime(article.publishedAt, LocalConfiguration.current.locales[0]),
                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                HorizontalDivider(color = MaterialTheme.colorScheme.primary)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource(if (state.reading == null) R.string.news_summary else R.string.news_static),
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    TextButton(onClick = { webMode = true }) { Text(stringResource(R.string.news_web)) }
                                }
                            }
                        }
                        if (state.readingLoading) item {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                Text(stringResource(R.string.news_loading_body), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        item {
                            val text = state.reading?.text ?: article.summary ?: stringResource(R.string.news_no_summary)
                            SelectionContainer { Text(text, fontSize = (16 + fontStep * 2).sp,
                                lineHeight = (27 + fontStep * 3).sp, fontFamily = FontFamily.Serif) }
                        }
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                HorizontalDivider()
                                Text(stringResource(if (state.reading == null && !state.readingLoading) R.string.news_body_failed else R.string.news_reading_note),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (state.reading?.truncated == true) Text(stringResource(R.string.news_truncated), style = MaterialTheme.typography.bodySmall)
                                state.reading?.fetchedAt?.let {
                                    Text(stringResource(R.string.news_fetched, newsTime(it, LocalConfiguration.current.locales[0])), style = MaterialTheme.typography.labelSmall)
                                }
                                SelectionContainer { Text(state.reading?.url ?: article.url, style = MaterialTheme.typography.bodySmall) }
                                if (state.reading == null && !state.readingLoading) TextButton(onClick = onRetry) { Text(stringResource(R.string.news_retry_body)) }
                            }
                        }
                    }
                }
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) { reader() }
                if (sideBySide) {
                    VerticalDivider()
                    Box(Modifier.width(380.dp).fillMaxHeight()) { panel() }
                }
            }
        } else {
            // Material's drawer follows layout direction. Only its container is mirrored;
            // both reading and research keep the user's actual text direction.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = drawer.isOpen, drawerContent = {
                    ModalDrawerSheet(Modifier.widthIn(max = 380.dp).fillMaxWidth(0.94f)) {
                        CompositionLocalProvider(LocalLayoutDirection provides direction) { panel() }
                    }
                }) {
                    CompositionLocalProvider(LocalLayoutDirection provides direction) { reader() }
                }
            }
        }
    }
}
