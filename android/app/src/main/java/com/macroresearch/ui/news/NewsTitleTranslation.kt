package com.macroresearch.ui.news

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.macroresearch.R

@Composable
internal fun NewsTitleTranslationControl(
    enabled: Boolean, configured: Boolean, language: String, failed: Boolean, onEnabled: (Boolean) -> Unit,
) {
    val label = stringResource(R.string.news_title_translation, language)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall)
            Switch(checked = enabled, onCheckedChange = onEnabled,
                modifier = Modifier.testTag("news-title-translation").semantics { contentDescription = label })
        }
        Text(stringResource(R.string.news_title_translation_note), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (enabled && !configured) Text(stringResource(R.string.news_title_translation_key),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (enabled && configured && failed) Text(stringResource(R.string.news_title_translation_failed),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
internal fun NewsArticleTitle(original: String, translated: String? = null) {
    val translation = translated?.takeIf { it.isNotBlank() && it != original }
    var showOriginal by rememberSaveable(original, translation) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SelectionContainer {
            Text(if (showOriginal) original else translation ?: original,
                modifier = Modifier.testTag("news-reader-title"), style = MaterialTheme.typography.headlineMedium,
                fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold)
        }
        if (translation != null) {
            Text(stringResource(R.string.news_title_translated), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { showOriginal = !showOriginal }, modifier = Modifier.testTag("news-title-original")) {
                Text(stringResource(if (showOriginal) R.string.news_title_show_translation else R.string.news_title_show_original))
            }
        }
    }
}
