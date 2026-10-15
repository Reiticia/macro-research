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
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.activity.compose.BackHandler
import androidx.compose.ui.graphics.vector.ImageVector
import com.macroresearch.ui.common.terminal.TerminalNavItem
import com.macroresearch.ui.common.terminal.TerminalNavigationBar
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
import com.macroresearch.ui.settings.SettingsDetailScreen
import com.macroresearch.ui.settings.SettingsScreen
import com.macroresearch.ui.settings.SettingsSection

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
    // Wait until NavHost has installed its graph and produced a back-stack entry. On a cold
    // start the notification intent can arrive before navigation is ready; navigating earlier
    // can be dropped or fail because the destination is not registered yet.
    LaunchedEffect(notificationEventId, backStackEntry?.destination?.route) {
        val eventId = notificationEventId ?: return@LaunchedEffect
        val entry = backStackEntry ?: return@LaunchedEffect
        if (entry.arguments?.getString("eventId") != eventId.toString()) {
            navController.navigate("event/$eventId") { launchSingleTop = true }
        }
        onNotificationHandled()
    }
    val currentDestination = backStackEntry?.destination
    // A notification-launched event is the start destination and therefore has no nav entry
    // behind it. Intercept the system Back key and return Home rather than finishing the app.
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
        bottomBar = {
            if (showBottomBar) {
                TerminalNavigationBar(
                    items = destinations.map { destination ->
                        TerminalNavItem(
                            label = stringResource(destination.label),
                            icon = destination.icon,
                            selected = currentDestination?.hierarchy?.any {
                                it.route == destination.route
                            } == true,
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
                        )
                    },
                )
            }
        },
    ) { padding ->
        // Use the notification destination as the graph's initial route on cold start. This
        // avoids racing an early navigate() against NavHost graph installation.
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
            composable("settings") {
                SettingsScreen(
                    repository = repository,
                    padding = padding,
                    updates = updates,
                    onShowWhatsNew = onShowWhatsNew,
                    onOpen = { navController.navigate("settings/${it.route}") },
                )
            }
            composable("settings/{section}") { entry ->
                val section = SettingsSection.fromRoute(entry.arguments?.getString("section")) ?: return@composable
                SettingsDetailScreen(
                    section = section,
                    repository = repository,
                    padding = padding,
                    updates = updates,
                    onShowWhatsNew = onShowWhatsNew,
                    onInstallUpdate = onInstallUpdate,
                    onBack = navController::popBackStack,
                )
            }
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
                        // A notification can make the event the graph's initial destination,
                        // leaving no previous entry. In that case Back should return home
                        // instead of exiting the app.
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

