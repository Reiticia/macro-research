package com.macroresearch.ui.history

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.macroresearch.R
import com.macroresearch.data.MacroRepository
import com.macroresearch.data.model.EconomicEvent
import com.macroresearch.ui.HistoryViewModel
import com.macroresearch.ui.common.ImportanceFilters
import com.macroresearch.ui.common.LoadingHint
import com.macroresearch.ui.common.calendarWarningMessage
import com.macroresearch.ui.common.surprise
import com.macroresearch.ui.common.terminal.EmptyHint
import com.macroresearch.ui.common.terminal.EventRow
import com.macroresearch.ui.common.terminal.EventTableHeader
import com.macroresearch.ui.common.terminal.Metric
import com.macroresearch.ui.common.terminal.MetricGrid
import com.macroresearch.ui.common.terminal.NoticeStrip
import com.macroresearch.ui.common.terminal.NoticeTone
import com.macroresearch.ui.common.terminal.PageHeader
import com.macroresearch.ui.common.terminal.SectionHeader
import com.macroresearch.ui.common.terminal.TerminalButton
import com.macroresearch.ui.common.terminal.TerminalFilterChip
import com.macroresearch.ui.theme.AssetDown
import com.macroresearch.ui.theme.AssetUp
import com.macroresearch.ui.theme.ResearchLayout
import com.macroresearch.ui.viewModelFactory
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
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
                scrolledFromTop = reachedListEnd(
                    firstVisibleIndex = listState.firstVisibleItemIndex,
                    firstVisibleOffset = listState.firstVisibleItemScrollOffset,
                    canScrollForward = listState.canScrollForward,
                    hasRows = events.isNotEmpty(),
                ),
                hasMore = hasMore,
                loading = state.loading,
                failed = state.error != null,
            )
        }
            .distinctUntilChanged()
            .filter { it }
            .collect { vm.loadMore() }
    }
    Column(Modifier.fillMaxSize().padding(padding)) {
        PageHeader(
            title = stringResource(R.string.history_title),
            subtitle = stringResource(R.string.history_subtitle),
            actions = {
                IconButton(onClick = { vm.refresh(forceNetwork = true) }, enabled = !state.loading) {
                    Icon(Icons.Outlined.Refresh, stringResource(R.string.history_refresh), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
        Column(
            Modifier.padding(horizontal = ResearchLayout.pagePadding, vertical = ResearchLayout.denseGap),
            verticalArrangement = Arrangement.spacedBy(ResearchLayout.denseGap),
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    null to stringResource(R.string.all),
                    "inflation" to stringResource(R.string.category_inflation),
                    "employment" to stringResource(R.string.category_employment),
                ).forEach { (category, label) ->
                    TerminalFilterChip(
                        selected = selected == category,
                        onClick = { if (selected != category) vm.refresh(category) },
                        label = label,
                    )
                }
            }
            ImportanceFilters(selectedImportance, vm::toggleImportance)
        }
        SummaryCards(events)
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = ResearchLayout.gap),
        ) {
            stickyHeader(key = "header") {
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
                    SectionHeader(
                        stringResource(R.string.history_records),
                        trailing = stringResource(R.string.recent_count, events.size),
                    )
                    EventTableHeader()
                }
            }
            warning?.let { item(key = "warning") { NoticeStrip(calendarWarningMessage(it), tone = NoticeTone.Error) } }
            state.error?.let { item(key = "error") { NoticeStrip(stringResource(R.string.load_failed, it), tone = NoticeTone.Error) } }
            if (events.isEmpty() && !state.loading && state.error == null) item(key = "empty") {
                EmptyHint(stringResource(R.string.history_empty))
            }
            items(events, key = { it.id }) { event -> EventRow(event, { onEvent(event.id) }, showDate = true) }
            if (state.loading) item(key = "loading") { LoadingHint() }
            // Normal pagination is automatic. Keep an explicit action only after a failed page,
            // otherwise an unchanged bottom position would repeatedly retry a broken network.
            if (hasMore && !state.loading && state.error != null) item(key = "retry") {
                TerminalButton(
                    onClick = vm::loadMore,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = ResearchLayout.pagePadding, vertical = ResearchLayout.gap),
                ) { Text(stringResource(R.string.retry)) }
            }
        }
    }
}

// 已滚离顶部，或内容不足一屏无法继续向下滚动（有数据时）都视为到达列表末端，允许加载更早记录。
// hasRows 防止空列表（仅吸顶表头）误触发。
internal fun reachedListEnd(
    firstVisibleIndex: Int,
    firstVisibleOffset: Int,
    canScrollForward: Boolean,
    hasRows: Boolean,
): Boolean = firstVisibleIndex > 0 || firstVisibleOffset > 0 || (hasRows && !canScrollForward)

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
    MetricGrid(
        listOf(
            Metric(stringResource(R.string.above_consensus), stringResource(R.string.occurrence_count, above), labelColor = AssetUp),
            Metric(stringResource(R.string.below_consensus), stringResource(R.string.occurrence_count, below), labelColor = AssetDown),
            Metric(stringResource(R.string.equal_consensus), stringResource(R.string.occurrence_count, equal)),
        ),
    )
}
