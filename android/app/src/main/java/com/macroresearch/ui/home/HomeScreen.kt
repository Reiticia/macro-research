package com.macroresearch.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
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
import com.macroresearch.data.model.LiveMarketQuote
import com.macroresearch.ui.HomeViewModel
import com.macroresearch.ui.common.EventCard
import com.macroresearch.ui.common.ImportanceDots
import com.macroresearch.ui.common.assetLabel
import com.macroresearch.ui.common.calendarWarningMessage
import com.macroresearch.ui.common.countryLabel
import com.macroresearch.ui.common.flag
import com.macroresearch.ui.common.importanceLabel
import com.macroresearch.ui.common.localTime
import com.macroresearch.ui.common.localizedCountdown as countdown
import com.macroresearch.ui.common.localizedDate as localDate
import com.macroresearch.ui.common.localizedName
import com.macroresearch.ui.common.localizedValue as value
import com.macroresearch.ui.market.formatMarketPrice
import com.macroresearch.ui.theme.AssetUp
import com.macroresearch.ui.theme.ResearchLayout
import com.macroresearch.ui.theme.TabularStyle
import com.macroresearch.ui.theme.Upcoming
import com.macroresearch.ui.viewModelFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    repository: MacroRepository,
    padding: PaddingValues,
    onEvent: (Long) -> Unit,
    onFollowedEvents: () -> Unit,
) {
    val vm: HomeViewModel = viewModel(factory = viewModelFactory { HomeViewModel(repository) })
    val events by vm.events.collectAsStateWithLifecycle()
    val refresh by vm.refresh.collectAsStateWithLifecycle()
    val warning by repository.calendarWarning.collectAsStateWithLifecycle()
    val notice = warning?.let { calendarWarningMessage(it) }
        ?: if (refresh.error != null) stringResource(R.string.calendar_warning_offline) else null
    val liveMarket by vm.liveMarket.collectAsStateWithLifecycle()
    val selectedMarkets by repository.selectedMarkets.collectAsStateWithLifecycle()
    val now = Instant.now()
    val next = events.firstOrNull { it.importance == 3 && runCatching { Instant.parse(it.eventTime) > now }.getOrDefault(false) }
        ?: events.firstOrNull { runCatching { Instant.parse(it.eventTime) > now }.getOrDefault(false) }
    val today = events.filter {
        runCatching { Instant.parse(it.eventTime).atZone(ZoneId.systemDefault()).toLocalDate() == LocalDate.now() }
            .getOrDefault(false)
    }
    LaunchedEffect(selectedMarkets) {
        while (true) {
            vm.loadLiveMarketAndWait(selectedMarkets)
            delay(5_000)
        }
    }

    LazyColumn(
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
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(width = 10.dp, height = 10.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.primary),
                        )
                        Text(
                            text = "MACRO DESK",
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        text = next?.localDate() ?: stringResource(R.string.research_tagline),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Surface(
                    onClick = onFollowedEvents,
                    shape = RoundedCornerShape(ResearchLayout.innerCorner),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Notifications,
                            contentDescription = stringResource(R.string.followed_events),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = stringResource(R.string.followed_events),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }

        // 2. Unified Live Market Ticker Ribbon
        item {
            MarketOverview(
                liveQuotes = liveMarket?.quotes.orEmpty(),
                selectedMarkets = selectedMarkets,
            )
        }

        // 3. Asymmetric Spotlight Dossier for Next Key Release
        if (next != null) {
            item {
                NextEventCard(next) { onEvent(next.id) }
            }
        }

        // 4. Today's Schedule Section Header
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
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
                        stringResource(R.string.today_events),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        stringResource(R.string.event_count, today.size),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall.merge(TabularStyle),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }

        if (refresh.loading && events.isEmpty()) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                }
            }
        }

        notice?.let {
            item {
                Surface(
                    shape = RoundedCornerShape(ResearchLayout.innerCorner),
                    color = Upcoming.copy(alpha = 0.10f),
                    border = BorderStroke(1.dp, Upcoming.copy(alpha = 0.35f)),
                ) {
                    Text(
                        it,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        color = Upcoming,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        items(today, key = { it.id }) { event ->
            EventCard(event, { onEvent(event.id) })
        }
    }
}

@Composable
private fun MarketOverview(
    liveQuotes: List<LiveMarketQuote>,
    selectedMarkets: List<String>,
) {
    val live = liveQuotes.associateBy { it.symbol }
    val rows = marketOverviewRows(selectedMarkets)
    Card(
        shape = RoundedCornerShape(ResearchLayout.cardCorner),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.market_overview),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (liveQuotes.isNotEmpty()) AssetUp else MaterialTheme.colorScheme.outline),
                    )
                    Text(
                        stringResource(
                            if (liveQuotes.isNotEmpty()) R.string.live_snapshot
                            else R.string.waiting_quotes,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            rows.forEachIndexed { rowIndex, rowMarkets ->
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                ) {
                    rowMarkets.forEachIndexed { colIndex, symbol ->
                        if (colIndex > 0) {
                            Box(
                                modifier = Modifier
                                    .width(0.5.dp)
                                    .fillMaxHeight()
                                    .background(MaterialTheme.colorScheme.outlineVariant),
                            )
                        }
                        MarketMiniTile(
                            label = assetLabel(symbol),
                            liveQuote = live[symbol],
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

internal fun marketOverviewRows(selectedMarkets: List<String>): List<List<String>> {
    val columns = if (selectedMarkets.size == 4) 2 else selectedMarkets.size.coerceAtLeast(1)
    return selectedMarkets.chunked(columns)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NextEventCard(event: EconomicEvent, onClick: () -> Unit) {
    var now by remember { mutableStateOf(Instant.now()) }
    val locale = LocalConfiguration.current.locales[0]
    LaunchedEffect(event.id) {
        while (true) { now = Instant.now(); delay(1_000) }
    }
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(ResearchLayout.cardCorner),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Top Accent Header Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.next_event),
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelMedium,
                )
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    Text(
                        countdown(event.eventTime, now),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelLarge.merge(TabularStyle),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
            }

            // Main Spotlight Body
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            "${flag(event.country)} ${countryLabel(event.country)}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "· ${event.localTime()}",
                            style = MaterialTheme.typography.labelMedium.merge(TabularStyle),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            importanceLabel(event.importance),
                            color = Upcoming,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        ImportanceDots(event.importance)
                    }
                }

                Text(
                    event.localizedName(locale),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            )

            // Bottom 3-Column Metric Matrix
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                maxItemsInEachRow = if (ResearchLayout.stackMetadata) 1 else Int.MAX_VALUE,
            ) {
                InlineValue(stringResource(R.string.previous), event.value(event.previous))
                InlineValue(stringResource(R.string.consensus), event.value(event.consensus))
                InlineValue(stringResource(R.string.forecast), event.value(event.forecast))
                if (!event.actual.isNullOrBlank()) {
                    InlineValue(stringResource(R.string.actual), event.value(event.actual))
                }
            }
        }
    }
}

@Composable
private fun InlineValue(label: String, value: String) = Column(
    verticalArrangement = Arrangement.spacedBy(1.dp),
) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        value,
        style = MaterialTheme.typography.bodyMedium.merge(TabularStyle),
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun MarketMiniTile(
    label: String,
    liveQuote: LiveMarketQuote?,
    modifier: Modifier = Modifier,
) {
    val locale = LocalConfiguration.current.locales[0]
    val price = liveQuote?.price
    val isLive = price != null && liveQuote.stale != true
    Column(
        modifier = modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            isLive -> AssetUp
                            price != null -> Upcoming
                            else -> MaterialTheme.colorScheme.outlineVariant
                        },
                    ),
            )
        }
        Text(
            price?.let { formatMarketPrice(it, locale) } ?: "--",
            style = MaterialTheme.typography.titleSmall.merge(TabularStyle),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}
