package com.macroresearch.ui.analysis

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.macroresearch.R
import com.macroresearch.data.remote.NewsArticle
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun reviewableNewsUrl(value: String): String? = value.toHttpUrlOrNull()?.takeIf {
    it.isHttps && it.username.isEmpty() && it.password.isEmpty()
}?.toString()

internal fun manualNewsMessage(article: NewsArticle, excerpt: String, question: String): String {
    require(excerpt.isNotBlank() && excerpt.length <= 6000)
    require(question.length <= 1000)
    val url = requireNotNull(reviewableNewsUrl(article.url))
    return "User-provided article notes (not independently verified by the app):\n" +
        "Title: ${article.title.take(300)}\nPublisher: ${article.source.take(120)}\n" +
        "Published: ${article.publishedAt.orEmpty().take(100)}\nURL: $url\n" +
        "Excerpt/notes:\n${excerpt.trim()}\n\nQuestion: ${question.trim().ifEmpty { "Please reassess this event using these notes, distinguishing reported claims from observed market evidence." }}"
}

private fun openNews(context: Context, url: String): Boolean {
    val safe = reviewableNewsUrl(url) ?: return false
    return try {
        // Let Android use the user's default browser; no embedded tab, headers or page access.
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(safe)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        })
        true
    } catch (_: Exception) { false }
}

@Composable
internal fun NewsLinkButton(url: String) {
    val context = LocalContext.current
    var failed by remember(url) { mutableStateOf(false) }
    TextButton(onClick = { failed = !openNews(context, url) }, enabled = reviewableNewsUrl(url) != null) {
        Text(stringResource(R.string.ai_news_read))
    }
    if (failed) Text(stringResource(R.string.ai_news_open_failed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

@Composable
internal fun NewsReview(articles: List<NewsArticle>, canAsk: Boolean, onAsk: (String) -> Unit) {
    if (articles.isEmpty()) return
    val context = LocalContext.current
    var selected by remember { mutableStateOf<NewsArticle?>(null) }
    var error by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text(stringResource(R.string.ai_related_news), fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.ai_manual_news_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (error) Text(stringResource(R.string.ai_news_open_failed), color = MaterialTheme.colorScheme.error)
        articles.forEach { article ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(article.title, style = MaterialTheme.typography.bodyMedium)
                Text(listOfNotNull(article.source, article.publishedAt).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SelectionContainer { Text(article.url, style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { error = !openNews(context, article.url) }, enabled = reviewableNewsUrl(article.url) != null) {
                        Text(stringResource(R.string.ai_news_read))
                    }
                    TextButton(onClick = { selected = article }, enabled = canAsk && reviewableNewsUrl(article.url) != null) {
                        Text(stringResource(R.string.ai_news_supply))
                    }
                }
            }
        }
    }
    selected?.let { article ->
        var excerpt by rememberSaveable(article.url) { mutableStateOf("") }
        var question by rememberSaveable(article.url) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(stringResource(R.string.ai_news_supply)) },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(article.title, fontWeight = FontWeight.SemiBold)
                    Text(article.url, style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.ai_manual_news_privacy), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(excerpt, { if (it.length <= 6000) excerpt = it }, modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.ai_news_excerpt)) }, minLines = 4, maxLines = 8)
                    OutlinedTextField(question, { if (it.length <= 1000) question = it }, modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.ai_news_question)) }, minLines = 2, maxLines = 4)
                }
            },
            confirmButton = { TextButton(onClick = { onAsk(manualNewsMessage(article, excerpt, question)); selected = null }, enabled = canAsk && excerpt.isNotBlank()) {
                Text(stringResource(R.string.ai_followup_send))
            } },
            dismissButton = { TextButton(onClick = { selected = null }) { Text(stringResource(R.string.ai_request_close)) } },
        )
    }
}
