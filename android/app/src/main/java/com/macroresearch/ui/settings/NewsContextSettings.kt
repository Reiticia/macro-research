package com.macroresearch.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macroresearch.R
import com.macroresearch.data.MacroRepository
import com.macroresearch.data.NewsPreferences
import com.macroresearch.data.validNewsSources

/** Opt-in news retrieval is only used for personal AI requests, never shared backend analysis. */
@Composable
fun NewsContextSettings(repository: MacroRepository) {
    val settings by repository.newsSettings.collectAsStateWithLifecycle()
    var enabled by rememberSaveable(settings.enabled) { mutableStateOf(settings.enabled) }
    var urlsText by rememberSaveable(settings.sourceUrls) { mutableStateOf(settings.sourceUrls.joinToString("\n")) }
    var biquoteEnabled by rememberSaveable(settings.biquoteEnabled) { mutableStateOf(settings.biquoteEnabled) }
    var articlePreviewEnabled by rememberSaveable(settings.articlePreviewEnabled) { mutableStateOf(settings.articlePreviewEnabled) }
    val toolsEnabled by repository.aiToolsEnabled.collectAsStateWithLifecycle()
    var saved by remember { mutableStateOf(false) }
    val urls = urlsText.lines().map(String::trim).filter(String::isNotEmpty).distinct()
    val valid = validNewsSources(enabled, biquoteEnabled, urls)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.ai_news_context), fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.ai_news_context_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.ai_news_context_toggle))
                Switch(checked = enabled, onCheckedChange = { enabled = it; saved = false })
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.ai_news_biquote), modifier = Modifier.weight(1f))
                Switch(checked = biquoteEnabled, onCheckedChange = { biquoteEnabled = it; saved = false })
            }
            Text(
                stringResource(R.string.ai_news_biquote_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ai_article_preview_toggle), modifier = Modifier.weight(1f))
                Switch(checked = articlePreviewEnabled, onCheckedChange = { articlePreviewEnabled = it; saved = false })
            }
            Text(stringResource(R.string.ai_article_preview_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (articlePreviewEnabled && !toolsEnabled) Text(stringResource(R.string.ai_article_preview_tools_required), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            OutlinedTextField(
                value = urlsText,
                onValueChange = { urlsText = it; saved = false },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.ai_news_sources)) },
                supportingText = { Text(stringResource(R.string.ai_news_sources_note)) },
                minLines = 3,
                maxLines = 8,
            )
            if (!valid) {
                Text(
                    stringResource(R.string.ai_news_sources_invalid),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        repository.setNewsSources(urls)
                        repository.setBiquoteNewsEnabled(biquoteEnabled)
                        repository.setArticlePreviewEnabled(articlePreviewEnabled)
                        repository.setNewsSearchEnabled(enabled)
                        saved = true
                    },
                    enabled = valid,
                ) { Text(stringResource(R.string.save)) }
                OutlinedButton(
                    onClick = {
                        urlsText = NewsPreferences.DEFAULT_NEWS_SOURCES.joinToString("\n")
                        saved = false
                    },
                ) { Text(stringResource(R.string.ai_news_sources_reset)) }
            }
            if (saved) {
                Text(
                    stringResource(R.string.ai_news_saved),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
