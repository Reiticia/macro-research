package com.macroresearch.ui.news

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.macroresearch.R
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Pure validation, no DNS lookup or network access. Keep the original path, query and fragment. */
internal fun originalNewsBrowserUrl(value: String): String? {
    if (value.length > 4096) return null
    val url = value.toHttpUrlOrNull() ?: return null
    val host = url.host.trimEnd('.')
    if (!url.isHttps || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty() ||
        !host.contains('.') || host.contains(':') || host.all { it.isDigit() || it == '.' } ||
        host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal")) return null
    return url.toString().takeIf { it.length <= 4096 }
}

internal fun openOriginalNewsInBrowser(context: Context, originalUrl: String): Boolean {
    val safe = originalNewsBrowserUrl(originalUrl) ?: return false
    return try {
        // Explicit user action only. No WebView/custom tab, headers, identity or page read-back.
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(safe)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        true
    } catch (_: ActivityNotFoundException) { false }
    catch (_: SecurityException) { false }
}

@Composable
internal fun NewsOriginalLink(originalUrl: String) {
    val context = LocalContext.current
    val safe = remember(originalUrl) { originalNewsBrowserUrl(originalUrl) }
    var failed by remember(originalUrl) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = { failed = !openOriginalNewsInBrowser(context, originalUrl) },
            enabled = safe != null, modifier = Modifier.fillMaxWidth().testTag("news-original-browser")) {
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.news_browser_open))
        }
        Text(stringResource(R.string.news_browser_note), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (safe == null || failed) Text(stringResource(if (safe == null) R.string.news_browser_invalid else R.string.news_browser_failed),
            modifier = Modifier.testTag("news-browser-error").semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}
