package com.macroresearch.ui.earnings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macroresearch.R
import com.macroresearch.data.EarningsRepository
import com.macroresearch.data.model.EarningsEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
internal fun EarningsFavoriteButton(date: LocalDate, event: EarningsEvent, repository: EarningsRepository) {
    val favorites by repository.favorites.collectAsStateWithLifecycle(emptyList())
    val selected = favorites.any { it.date == date && it.event.symbol == event.symbol }
    val scope = rememberCoroutineScope()
    var saving by remember(date, event.symbol) { mutableStateOf(false) }
    var failed by remember(date, event.symbol) { mutableStateOf(false) }
    IconButton(onClick = {
        saving = true
        scope.launch {
            try { repository.setFavorite(date, event, !selected); failed = false }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
            finally { saving = false }
        }
    }, enabled = !saving, modifier = Modifier.testTag("earnings-favorite-${event.symbol}")) {
        Icon(if (selected) Icons.Filled.Star else Icons.Outlined.StarBorder,
            stringResource(if (selected) R.string.earnings_unfavorite else R.string.earnings_favorite),
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (failed) Text(stringResource(R.string.earnings_favorite_failed), color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.labelSmall)
}
