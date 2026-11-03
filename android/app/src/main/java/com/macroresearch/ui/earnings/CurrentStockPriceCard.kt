package com.macroresearch.ui.earnings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.macroresearch.R
import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun CurrentStockPriceCard(state: EarningsQuoteState, now: Instant, locale: Locale) {
    val quote = state.quote
    Card(Modifier.fillMaxWidth().testTag("earnings-current-price")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.earnings_quote_title), fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val price = quote?.price?.toBigDecimalOrNull()?.let {
                    NumberFormat.getNumberInstance(locale).apply { minimumFractionDigits = 2; maximumFractionDigits = 6 }.format(it)
                } ?: "—"
                Text(price, style = MaterialTheme.typography.headlineLarge, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.testTag("earnings-current-price-value"))
                Text(quote?.currency ?: "—", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.loading && quote == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            quote?.let {
                Text(stringResource(R.string.earnings_quote_time, DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", locale)
                    .withZone(ZoneId.systemDefault()).format(Instant.parse(it.timestamp))), style = MaterialTheme.typography.bodySmall)
                if (it.stale || state.error != null || Duration.between(Instant.parse(it.timestamp), now).seconds > 90) {
                    Text(stringResource(R.string.earnings_quote_stale), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                if (it.timestampBasis == "minute_bar_close") Text(stringResource(R.string.earnings_quote_bar),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.error?.let { reason ->
                Text(stringResource(when (reason) {
                    "backend_required" -> R.string.earnings_quote_backend_required
                    "unauthorized" -> R.string.earnings_unauthorized
                    "unsupported" -> R.string.earnings_quote_unsupported
                    "rate_limited" -> R.string.earnings_market_rate_limited
                    else -> R.string.earnings_quote_failed
                }), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Text(stringResource(R.string.earnings_quote_note), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
