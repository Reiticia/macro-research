package com.macroresearch.ui.earnings

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.macroresearch.R
import com.macroresearch.data.model.EarningsEvent
import com.macroresearch.data.model.earningsPage

internal fun openEarningsPage(context: Context, event: EarningsEvent): Boolean {
    val page = event.earningsPage() ?: return false
    return try {
        // A user-initiated browser visit, independent of direct/backend data fetching.
        // No WebView, custom headers, credentials, device identity or background page access.
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(page.url)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

@Composable
internal fun EarningsPageLink(event: EarningsEvent) {
    val page = remember(event) { event.earningsPage() }
    val context = LocalContext.current
    var failed by remember(event) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(
            onClick = { failed = !openEarningsPage(context, event) },
            enabled = page != null,
            modifier = Modifier.fillMaxWidth().testTag("earnings-page-open-${event.symbol}"),
        ) {
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (page != null) stringResource(R.string.earnings_page_open, page.provider)
                else stringResource(R.string.earnings_page_unavailable))
        }
        if (page != null) Text(stringResource(R.string.earnings_page_note),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (failed) Text(stringResource(R.string.earnings_page_open_failed),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}
