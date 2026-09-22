package com.macroresearch.ui.followed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macroresearch.R
import com.macroresearch.data.MacroRepository
import com.macroresearch.ui.common.EventCard
import com.macroresearch.ui.theme.ResearchLayout

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowedEventsScreen(
    repository: MacroRepository,
    padding: PaddingValues,
    onBack: () -> Unit,
    onEvent: (Long) -> Unit,
) {
    val events by repository.observeFollowedEvents().collectAsStateWithLifecycle(emptyList())

    Column(
        modifier = Modifier
            .fillMaxSize()
            // TopAppBar consumes the status-bar inset itself; applying the outer top padding too
            // would leave an empty strip above the toolbar.
            .padding(bottom = padding.calculateBottomPadding()),
    ) {
        TopAppBar(
            title = { Text(stringResource(R.string.followed_events)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
                }
            },
        )
        if (events.isEmpty()) {
            Text(
                text = stringResource(R.string.no_followed_events),
                modifier = Modifier.padding(ResearchLayout.pagePadding),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(
                    horizontal = ResearchLayout.pagePadding,
                    vertical = ResearchLayout.gap,
                ),
                verticalArrangement = Arrangement.spacedBy(ResearchLayout.gap),
            ) {
                items(events, key = { it.id }) { event ->
                    EventCard(event, { onEvent(event.id) }, showDate = true)
                }
            }
        }
    }
}
