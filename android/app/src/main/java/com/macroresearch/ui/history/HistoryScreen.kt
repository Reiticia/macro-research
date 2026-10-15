package com.macroresearch.ui.history

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.macroresearch.R
import com.macroresearch.data.MacroRepository
import com.macroresearch.data.model.EconomicEvent
import com.macroresearch.ui.HistoryViewModel
import com.macroresearch.ui.common.EventCard
import com.macroresearch.ui.common.ImportanceFilters
import com.macroresearch.ui.common.LoadingHint
import com.macroresearch.ui.common.calendarWarningMessage
import com.macroresearch.ui.common.surprise
import com.macroresearch.ui.theme.AssetDown
import com.macroresearch.ui.theme.AssetUp
import com.macroresearch.ui.theme.ResearchLayout
import com.macroresearch.ui.theme.TabularStyle
import com.macroresearch.ui.viewModelFactory
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

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

    LaunchedEffect(Unit) {
        listState.scrollToItem(0)
        vm.refresh()
        vm.refreshTranslations()
    }
    LaunchedEffect(selected, selectedImportance) {
        listState.scrollToItem(0)
    }
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

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(
            horizontal = ResearchLayout.pagePadding,
            vertical = ResearchLayout.gap,
        ),
        verticalArrangement = Arrangement.spacedBy(ResearchLayout.gap),
    ) {
        // 1. Architectural Masthead
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "HISTORICAL ARCHIVE",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            stringResource(R.string.history_title),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.ExtraBold,
                        )
                        Text(
                            stringResource(R.string.history_subtitle),
                            modifier = Modifier.padding(bottom = 2.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Surface(
                    onClick = { vm.refresh(forceNetwork = true) },
                    enabled = !state.loading,
                    shape = RoundedCornerShape(ResearchLayout.innerCorner),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Text(
                        text = stringResource(R.string.history_refresh),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        // 2. Segmented Category Control + Importance Filter Track
        item {
            Column(verticalArrangement = Arrangement.spacedBy(ResearchLayout.smallGap)) {
                CategorySegmentedBar(
                    selected = selected,
                    onSelect = { category -> if (selected != category) vm.refresh(category) },
                )
                ImportanceFilters(selectedImportance, vm::toggleImportance)
            }
        }

        // 3. Surprise Distribution Dashboard
        item {
            SurpriseDistributionBoard(events)
        }

        // 4. Section Header
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .width(4.dp)
                            .height(16.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(MaterialTheme.colorScheme.primary),
                    )
                    Text(
                        stringResource(R.string.history_records),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        stringResource(R.string.recent_count, events.size),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall.merge(TabularStyle),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }

        warning?.let {
            item {
                Surface(
                    shape = RoundedCornerShape(ResearchLayout.innerCorner),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f)),
                ) {
                    Text(
                        calendarWarningMessage(it),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        state.error?.let {
            item {
                Text(
                    stringResource(R.string.load_failed, it),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (events.isEmpty() && !state.loading && state.error == null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(ResearchLayout.cardCorner),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Text(
                        stringResource(R.string.history_empty),
                        Modifier.padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        items(events, key = { it.id }) { event ->
            EventCard(event, { onEvent(event.id) }, showDate = true)
        }

        if (state.loading) item { LoadingHint() }

        if (hasMore && !state.loading && state.error != null) {
            item {
                Button(onClick = vm::loadMore, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.retry))
                }
            }
        }
    }
}

@Composable
private fun CategorySegmentedBar(
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(ResearchLayout.innerCorner),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            listOf(
                null to stringResource(R.string.all),
                "inflation" to stringResource(R.string.category_inflation),
                "employment" to stringResource(R.string.category_employment),
            ).forEach { (category, label) ->
                val isSelected = selected == category
                Surface(
                    onClick = { onSelect(category) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(4.dp),
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.Transparent
                    },
                ) {
                    Text(
                        text = label,
                        modifier = Modifier.padding(vertical = 7.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
private fun SurpriseDistributionBoard(events: List<EconomicEvent>) {
    val above = events.count { (it.surprise()?.signum() ?: 0) > 0 }
    val below = events.count { (it.surprise()?.signum() ?: 0) < 0 }
    val equal = events.count { it.actual != null && it.consensus != null && it.surprise()?.signum() == 0 }
    val total = (above + below + equal).coerceAtLeast(1)

    Card(
        shape = RoundedCornerShape(ResearchLayout.cardCorner),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Top Proportional Distribution Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp),
            ) {
                if (above == 0 && below == 0 && equal == 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    )
                } else {
                    if (above > 0) {
                        Box(
                            modifier = Modifier
                                .weight(above.toFloat() / total)
                                .fillMaxHeight()
                                .background(AssetUp),
                        )
                    }
                    if (equal > 0) {
                        Box(
                            modifier = Modifier
                                .weight(equal.toFloat() / total)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.outline),
                        )
                    }
                    if (below > 0) {
                        Box(
                            modifier = Modifier
                                .weight(below.toFloat() / total)
                                .fillMaxHeight()
                                .background(AssetDown),
                        )
                    }
                }
            }

            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
            ) {
                ScoreboardCell(
                    label = stringResource(R.string.above_consensus),
                    count = above,
                    color = AssetUp,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .width(0.5.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                ScoreboardCell(
                    label = stringResource(R.string.below_consensus),
                    count = below,
                    color = AssetDown,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .width(0.5.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                ScoreboardCell(
                    label = stringResource(R.string.equal_consensus),
                    count = equal,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ScoreboardCell(
    label: String,
    count: Int,
    color: Color,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(R.string.occurrence_count, count),
            style = MaterialTheme.typography.titleLarge.merge(TabularStyle),
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
