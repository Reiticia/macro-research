package com.macroresearch.ui.followed

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macroresearch.R
import com.macroresearch.data.EarningsRepository
import com.macroresearch.data.FavoriteEarnings
import com.macroresearch.data.MacroRepository
import com.macroresearch.ui.common.EventCard
import com.macroresearch.ui.common.dateLabel
import com.macroresearch.ui.earnings.EarningsCard
import com.macroresearch.ui.earnings.EarningsFavoriteButton
import com.macroresearch.ui.earnings.EarningsStockDialog
import com.macroresearch.ui.theme.ResearchLayout
import kotlinx.coroutines.flow.flowOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowedEventsScreen(
    repository: MacroRepository,
    padding: PaddingValues,
    onBack: () -> Unit,
    onEvent: (Long) -> Unit,
    earningsRepository: EarningsRepository? = null,
) {
    val events by repository.observeFollowedEvents().collectAsStateWithLifecycle(emptyList())
    val favorites by (earningsRepository?.favorites ?: flowOf(emptyList())).collectAsStateWithLifecycle(emptyList())
    val source by repository.dataSourceSettings.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<FavoriteEarnings?>(null) }

    Column(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
        TopAppBar(
            title = { Text(stringResource(R.string.followed_events)) },
            navigationIcon = { IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
            } },
        )
        if (events.isEmpty() && favorites.isEmpty()) {
            Text(stringResource(R.string.no_followed_events), Modifier.padding(ResearchLayout.pagePadding),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = ResearchLayout.pagePadding, vertical = ResearchLayout.gap),
                verticalArrangement = Arrangement.spacedBy(ResearchLayout.gap),
            ) {
                if (favorites.isNotEmpty()) item(key = "earnings-heading") {
                    Column(verticalArrangement = Arrangement.spacedBy(ResearchLayout.gap)) {
                        Text(stringResource(R.string.earnings_favorites))
                        Text(stringResource(R.string.earnings_favorite_snapshot), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(favorites, key = { "earnings:${it.date}:${it.event.symbol}" }) { favorite ->
                    Column(verticalArrangement = Arrangement.spacedBy(ResearchLayout.gap)) {
                        Text(dateLabel(favorite.date), style = MaterialTheme.typography.labelLarge)
                        EarningsCard(favorite.event, earningsRepository?.logos, source,
                            onStock = { selected = favorite }, favorite = earningsRepository?.let { repo -> {
                                EarningsFavoriteButton(favorite.date, favorite.event, repo)
                            } })
                    }
                }
                if (events.isNotEmpty()) item(key = "macro-heading") { Text(stringResource(R.string.earnings_macro_tab)) }
                items(events, key = { "macro:${it.id}" }) { event -> EventCard(event, { onEvent(event.id) }, showDate = true) }
            }
        }
    }
    selected?.let { favorite -> earningsRepository?.let { repo ->
        EarningsStockDialog(favorite.date, favorite.event, repo) { selected = null }
    } }
}
