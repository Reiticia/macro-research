package com.macroresearch.ui.followed

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macroresearch.R
import com.macroresearch.data.MacroRepository
import com.macroresearch.ui.common.terminal.DetailTopBar
import com.macroresearch.ui.common.terminal.EmptyHint
import com.macroresearch.ui.common.terminal.EventRow
import com.macroresearch.ui.common.terminal.EventTableHeader
import com.macroresearch.ui.theme.ResearchLayout

@OptIn(ExperimentalFoundationApi::class)
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
        DetailTopBar(stringResource(R.string.followed_events), onBack)
        if (events.isEmpty()) {
            EmptyHint(stringResource(R.string.no_followed_events), icon = Icons.Outlined.StarBorder)
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = ResearchLayout.gap)) {
                stickyHeader(key = "header") { EventTableHeader() }
                items(events, key = { it.id }) { event ->
                    EventRow(event, { onEvent(event.id) }, showDate = true)
                }
            }
        }
    }
}
