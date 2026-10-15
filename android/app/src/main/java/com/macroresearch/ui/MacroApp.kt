package com.macroresearch.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.macroresearch.R
import com.macroresearch.data.MacroRepository
import com.macroresearch.ui.analysis.AnalysisScreen
import com.macroresearch.ui.calendar.CalendarScreen
import com.macroresearch.ui.event.EventDetailScreen
import com.macroresearch.ui.followed.FollowedEventsScreen
import com.macroresearch.ui.history.HistoryScreen
import com.macroresearch.ui.home.HomeScreen
import com.macroresearch.ui.market.MarketScreen
import com.macroresearch.ui.settings.SettingsScreen

private data class TopDestination(
    val route: String,
    @StringRes val label: Int,
    val icon: ImageVector,
)

private val destinations = listOf(
    TopDestination("home", R.string.nav_home, Icons.Outlined.Home),
    TopDestination("calendar", R.string.nav_calendar, Icons.Outlined.CalendarMonth),
    TopDestination("market", R.string.nav_market, Icons.Outlined.QueryStats),
    TopDestination("history", R.string.nav_history, Icons.Outlined.Insights),
    TopDestination("settings", R.string.nav_settings, Icons.Outlined.Settings),
)

@Composable
fun MacroApp(
    repository: MacroRepository,
    notificationEventId: Long? = null,
    onNotificationHandled: () -> Unit = {},
    updates: com.macroresearch.update.UpdateCoordinator? = null,
    onShowWhatsNew: () -> Unit = {},
    onInstallUpdate: () -> Unit = {},
) {
    val navController = rememberNavController()
    val initialNotificationEventId = remember { notificationEventId }
    val backStackEntry by navController.currentBackStackEntryAsState()
    LaunchedEffect(notificationEventId, backStackEntry?.destination?.route) {
        val eventId = notificationEventId ?: return@LaunchedEffect
        val entry = backStackEntry ?: return@LaunchedEffect
        if (entry.arguments?.getString("eventId") != eventId.toString()) {
            navController.navigate("event/$eventId") { launchSingleTop = true }
        }
        onNotificationHandled()
    }
    val currentDestination = backStackEntry?.destination
    BackHandler(
        enabled = initialNotificationEventId != null &&
            currentDestination?.route == "event/{eventId}",
    ) {
        navController.navigate("home") { launchSingleTop = true }
    }
    val showBottomBar = destinations.any { destination ->
        currentDestination?.hierarchy?.any { it.route == destination.route } == true
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBottomBar) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    tonalElevation = 0.dp,
                ) {
                    Column(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
                        HorizontalDivider(
                            thickness = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(58.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            destinations.forEach { destination ->
                                val isSelected = currentDestination?.hierarchy?.any {
                                    it.route == destination.route
                                } == true
                                val label = stringResource(destination.label)
                                val tint = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(58.dp)
                                        .semantics { selected = isSelected }
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            role = Role.Tab,
                                            onClick = {
                                                val startDestination = navController.graph.findStartDestination()
                                                if (destination.route == startDestination.route) {
                                                    navController.popBackStack(startDestination.id, inclusive = false)
                                                } else {
                                                    navController.navigate(destination.route) {
                                                        popUpTo(startDestination.id) {
                                                            saveState = true
                                                        }
                                                        launchSingleTop = true
                                                        restoreState = true
                                                    }
                                                }
                                            },
                                        ),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .width(28.dp)
                                            .height(3.dp)
                                            .clip(RoundedCornerShape(bottomStart = 2.dp, bottomEnd = 2.dp))
                                            .background(
                                                if (isSelected) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.surfaceContainerLowest,
                                            ),
                                    )
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Icon(
                                            imageVector = destination.icon,
                                            contentDescription = label,
                                            modifier = Modifier.size(20.dp),
                                            tint = tint,
                                        )
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = tint,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Spacer(Modifier.height(4.dp))
                                }
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController,
            startDestination = initialNotificationEventId?.let { "event/$it" } ?: "home",
        ) {
            composable("home") {
                HomeScreen(
                    repository = repository,
                    padding = padding,
                    onEvent = { navController.navigate("event/$it") },
                    onFollowedEvents = { navController.navigate("followed") },
                )
            }
            composable("calendar") {
                CalendarScreen(repository, padding) { navController.navigate("event/$it") }
            }
            composable("market") { MarketScreen(repository, padding) }
            composable("history") {
                HistoryScreen(repository, padding) { navController.navigate("event/$it") }
            }
            composable("settings") { SettingsScreen(repository, padding, updates, onShowWhatsNew, onInstallUpdate) }
            composable("followed") {
                FollowedEventsScreen(
                    repository = repository,
                    padding = padding,
                    onBack = navController::popBackStack,
                    onEvent = { navController.navigate("event/$it") },
                )
            }
            composable("event/{eventId}") { entry ->
                val id = entry.arguments?.getString("eventId")?.toLongOrNull() ?: return@composable
                EventDetailScreen(
                    id = id,
                    repository = repository,
                    onBack = {
                        if (!navController.popBackStack()) {
                            navController.navigate("home") { launchSingleTop = true }
                        }
                    },
                    onAnalysis = { navController.navigate("analysis/$id") },
                    onHistory = {
                        navController.navigate("history") {
                            popUpTo(navController.graph.findStartDestination().id)
                        }
                    },
                )
            }
            composable("analysis/{eventId}") { entry ->
                val id = entry.arguments?.getString("eventId")?.toLongOrNull() ?: return@composable
                AnalysisScreen(id, repository, navController::popBackStack)
            }
        }
    }
}
