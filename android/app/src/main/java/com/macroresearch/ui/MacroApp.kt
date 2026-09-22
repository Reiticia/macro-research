package com.macroresearch.ui

import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import com.macroresearch.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.macroresearch.data.MacroRepository
import com.macroresearch.ui.analysis.AnalysisScreen
import com.macroresearch.ui.calendar.CalendarScreen
import com.macroresearch.ui.event.EventDetailScreen
import com.macroresearch.ui.history.HistoryScreen
import com.macroresearch.ui.home.HomeScreen
import com.macroresearch.ui.followed.FollowedEventsScreen
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
) {
    val navController = rememberNavController()
    LaunchedEffect(notificationEventId) {
        notificationEventId?.let { eventId ->
            navController.navigate("event/$eventId") { launchSingleTop = true }
            onNotificationHandled()
        }
    }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val showBottomBar = destinations.any { destination ->
        currentDestination?.hierarchy?.any { it.route == destination.route } == true
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                ) {
                    destinations.forEach { destination ->
                        val selected = currentDestination?.hierarchy?.any {
                            it.route == destination.route
                        } == true
                        NavigationBarItem(
                            selected = selected,
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
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
                            icon = { Icon(destination.icon, stringResource(destination.label)) },
                            label = { Text(stringResource(destination.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(navController, startDestination = "home") {
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
            composable("settings") { SettingsScreen(repository, padding) }
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
                    onBack = navController::popBackStack,
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

