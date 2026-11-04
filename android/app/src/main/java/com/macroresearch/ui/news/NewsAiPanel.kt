package com.macroresearch.ui.news

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.macroresearch.R

@Composable
internal fun NewsAiPanel(
    state: NewsDeskState,
    configured: Boolean,
    onClose: () -> Unit,
    onAsk: (String, String, Boolean) -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
) {
    val article = state.article ?: return
    val language = LocalConfiguration.current.locales[0].toLanguageTag()
    var question by rememberSaveable(article.url) { mutableStateOf("") }
    var quotes by rememberSaveable { mutableStateOf(false) }
    val initialPrompt = stringResource(R.string.news_ai_prompt)
    val goldQuestion = stringResource(R.string.news_ai_gold)
    val riskQuestion = stringResource(R.string.news_ai_risks)
    val canAsk = configured && !state.aiLoading && !state.readingLoading
    val scroll = rememberLazyListState()
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            val sentQuestion = state.messages.dropLast(1).lastOrNull { it.role == "user" }?.content
            if (sentQuestion == question.trim()) question = ""
            scroll.animateScrollToItem(state.messages.size)
        }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.news_ai), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.news_ai_subtitle), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, stringResource(R.string.news_close)) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.primary)
            LazyColumn(Modifier.weight(1f), state = scroll, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(article.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.news_ai_evidence,
                            stringResource(if (state.reading == null) R.string.news_summary else R.string.news_static)),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        if (state.reading?.truncated == true || (state.reading?.text?.length ?: 0) > 18_000) {
                            Text(stringResource(R.string.news_truncated), style = MaterialTheme.typography.bodySmall)
                        }
                        Text(stringResource(R.string.news_ai_privacy), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!configured) Text(stringResource(R.string.news_ai_requires_key), color = MaterialTheme.colorScheme.error)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = quotes, onCheckedChange = { quotes = it }, enabled = !state.aiLoading)
                            Text(stringResource(R.string.news_ai_quotes), style = MaterialTheme.typography.bodySmall)
                        }
                        if (quotes) Text(stringResource(R.string.news_ai_quotes_note), style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { onAsk(initialPrompt, language, quotes) }, enabled = canAsk, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.news_ai_analyze))
                        }
                    }
                }
                itemsIndexed(state.messages) { _, message ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (message.role == "user") stringResource(R.string.news_ai_you) else "AI",
                            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                            color = if (message.role == "user") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
                        SelectionContainer { Text(message.content, style = MaterialTheme.typography.bodyMedium) }
                        HorizontalDivider(Modifier.padding(top = 8.dp))
                    }
                }
                if (state.aiLoading) item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        TextButton(onClick = onStop) { Text(stringResource(R.string.news_ai_stop)) }
                    }
                }
                if (state.aiError) item { Text(stringResource(R.string.news_ai_failed), color = MaterialTheme.colorScheme.error) }
                item { Text(stringResource(R.string.news_ai_disclaimer), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (state.messages.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { question = goldQuestion }, enabled = !state.aiLoading) { Text("XAU · ?") }
                    TextButton(onClick = { question = riskQuestion }, enabled = !state.aiLoading) { Text(riskQuestion) }
                }
                TextButton(onClick = onClear, enabled = !state.aiLoading) { Text(stringResource(R.string.news_ai_clear)) }
            }
            OutlinedTextField(question, { if (it.length <= 1000) question = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.news_ai_question)) }, minLines = 2, maxLines = 4, enabled = configured && !state.aiLoading)
            Button(onClick = { onAsk(question, language, quotes) }, enabled = canAsk && question.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.news_ai_send))
            }
        }
    }
}
