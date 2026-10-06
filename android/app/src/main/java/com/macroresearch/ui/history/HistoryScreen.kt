package com.macroresearch.ui.history

import androidx.compose.ui.res.stringResource
import com.macroresearch.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.macroresearch.data.MacroRepository
import com.macroresearch.data.model.EconomicEvent
import com.macroresearch.ui.HistoryViewModel
import com.macroresearch.ui.common.EventCard
import com.macroresearch.ui.common.LoadingHint
import com.macroresearch.ui.common.calendarWarningMessage
import com.macroresearch.ui.common.importanceLabel
import com.macroresearch.ui.common.surprise
import com.macroresearch.ui.theme.AssetDown
import com.macroresearch.ui.theme.AssetUp
import com.macroresearch.ui.theme.ResearchLayout
import com.macroresearch.ui.viewModelFactory
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HistoryScreen(repository: MacroRepository, padding: PaddingValues, onEvent: (Long) -> Unit) {
    val vm: HistoryViewModel = viewModel(factory = viewModelFactory { HistoryViewModel(repository) })
    val state by vm.state.collectAsStateWithLifecycle()
    val selected by vm.category.collectAsStateWithLifecycle()
    val selectedImportance by vm.importance.collectAsStateWithLifecycle()
    val hasMore by vm.hasMore.collectAsStateWithLifecycle()
    val warning by repository.calendarWarning.collectAsStateWithLifecycle()
    val events = state.value.orEmpty()
    val listState = rememberLazyListState()
    // Top-level navigation restores destination state. Reset it deliberately: every visit starts
    // at the newest eight rows, and older pages are appended only after the user scrolls.
    LaunchedEffect(Unit) {
        listState.scrollToItem(0)
        vm.refresh()
        vm.refreshTranslations()
    }
    LaunchedEffect(selected, selectedImportance) {
        listState.scrollToItem(0)
    }
    // Loading is driven by actual viewport position, not a button: once the final rendered row
    // reaches the viewport, request the next eight rows from Room.
    LaunchedEffect(listState, hasMore, state.loading, state.error, events.size) {
        snapshotFlow {
            shouldLoadOlder(
                lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1,
                totalItems = listState.layoutInfo.totalItemsCount,
                scrolledFromTop = listState.firstVisibleItemIndex > 0 ||
                    listState.firstVisibleItemScrollOffset > 0,
                hasMore = hasMore,
                loading = state.loading,
                failed = state.error != null,
            )
        }
            .distinctUntilChanged()
            .filter { it }
            .collect { vm.loadMore() }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = ResearchLayout.pagePadding, vertical = ResearchLayout.gap),
        verticalArrangement = Arrangement.spacedBy(ResearchLayout.gap),
    ) {
        Column {
            Text(stringResource(R.string.history_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.history_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(verticalArrangement = Arrangement.spacedBy(ResearchLayout.smallGap)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(null to stringResource(R.string.all), "inflation" to stringResource(R.string.category_inflation), "employment" to stringResource(R.string.category_employment)).forEach { (category, label) ->
                    FilterChip(
                        selected = selected == category,
                        onClick = { if (selected != category) vm.refresh(category) },
                        label = { Text(label) },
                    )
                }
            }
            Text(
                stringResource(R.string.importance),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(null, 3, 2, 1).forEach { level ->
                    FilterChip(
                        selected = selectedImportance == level,
                        onClick = { if (selectedImportance != level) vm.refresh(importance = level) },
                        label = { Text(if (level == null) stringResource(R.string.all) else importanceLabel(level)) },
                    )
                }
            }
        }
        TextButton(onClick = { vm.refresh(forceNetwork = true) }, enabled = !state.loading) {
            Text(stringResource(R.string.history_refresh))
        }
        SummaryCards(events)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.history_records), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.recent_count, events.size), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(ResearchLayout.gap),
            contentPadding = PaddingValues(bottom = 4.dp),
        ) {
            warning?.let { item { Text(calendarWarningMessage(it), color = MaterialTheme.colorScheme.error) } }
            state.error?.let { item { Text(stringResource(R.string.load_failed, it), color = MaterialTheme.colorScheme.error) } }
            if (events.isEmpty() && !state.loading && state.error == null) item {
                Text(stringResource(R.string.history_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(events, key = { it.id }) { event -> EventCard(event, { onEvent(event.id) }, showDate = true) }
            if (state.loading) item { LoadingHint() }
            // Normal pagination is automatic. Keep an explicit action only after a failed page,
            // otherwise an unchanged bottom position would repeatedly retry a broken network.
            if (hasMore && !state.loading && state.error != null) item {
                Button(onClick = vm::loadMore, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.retry))
                }
            }
        }
    }
}

internal fun shouldLoadOlder(
    lastVisibleIndex: Int,
    totalItems: Int,
    scrolledFromTop: Boolean,
    hasMore: Boolean,
    loading: Boolean,
    failed: Boolean,
): Boolean = scrolledFromTop && hasMore && !loading && !failed && totalItems > 0 &&
    lastVisibleIndex >= totalItems - 1

@Composable
private fun SummaryCards(events: List<EconomicEvent>) {
    val above = events.count { (it.surprise()?.signum() ?: 0) > 0 }
    val below = events.count { (it.surprise()?.signum() ?: 0) < 0 }
    val equal = events.count { it.actual != null && it.consensus != null && it.surprise()?.signum() == 0 }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SummaryCard(stringResource(R.string.above_consensus), above, AssetUp, Modifier.weight(1f))
        SummaryCard(stringResource(R.string.below_consensus), below, AssetDown, Modifier.weight(1f))
        SummaryCard(stringResource(R.string.equal_consensus), equal, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.weight(1f))
    }
}

@Composable
private fun SummaryCard(label: String, count: Int, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.padding(ResearchLayout.gap), verticalArrangement = Arrangement.spacedBy(ResearchLayout.smallGap)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = color)
            Text(stringResource(R.string.occurrence_count, count), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
    }
}

