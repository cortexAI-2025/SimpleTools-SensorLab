package com.simpletools.sensorlab.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.simpletools.sensorlab.AppContainer
import com.simpletools.sensorlab.R
import com.simpletools.sensorlab.core.sensors.SensorKind
import com.simpletools.sensorlab.feature.comparison.ComparisonScreen
import com.simpletools.sensorlab.feature.dashboard.DashboardScreen
import com.simpletools.sensorlab.feature.sensor.SensorScreen
import com.simpletools.sensorlab.feature.sessions.SessionDetailScreen
import com.simpletools.sensorlab.feature.sessions.SessionsScreen
import com.simpletools.sensorlab.feature.settings.SettingsScreen

private object Routes {
    const val HOME = "home"
    const val SESSIONS = "sessions"
    const val COMPARE = "compare"
    const val SETTINGS = "settings"
    const val SENSOR = "sensor/{key}"
    const val SESSION = "session/{id}"
    const val COMPARE_WITH = "compare/{a}"
    fun sensor(kind: SensorKind) = "sensor/${kind.key}"
    fun session(id: String) = "session/$id"
    fun compareWith(id: String) = "compare/$id"
}

private class Tab(val route: String, val label: Int, val icon: ImageVector)

@Composable
fun SensorLabNavHost(container: AppContainer) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    val tabs = listOf(
        Tab(Routes.HOME, R.string.tab_home, Icons.Filled.Home),
        Tab(Routes.SESSIONS, R.string.tab_sessions, Icons.AutoMirrored.Filled.List),
        Tab(Routes.COMPARE, R.string.tab_compare, SensorIcons.compare),
        Tab(Routes.SETTINGS, R.string.tab_settings, Icons.Filled.Settings),
    )
    val showBar = tabs.any { it.route == current }

    Scaffold(
        contentWindowInsets = if (showBar) ScaffoldDefaults.contentWindowInsets else WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBar) NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = current == t.route,
                        onClick = { navigateTab(nav, t.route) },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(stringResource(t.label)) },
                    )
                }
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = Routes.HOME, modifier = Modifier.padding(pad)) {
            composable(Routes.HOME) {
                DashboardScreen(
                    container,
                    onOpenSensor = { nav.navigate(Routes.sensor(it)) },
                    onOpenSessions = { navigateTab(nav, Routes.SESSIONS) },
                    onOpenSession = { nav.navigate(Routes.session(it)) },
                )
            }
            composable(Routes.SESSIONS) {
                SessionsScreen(container, onOpen = { nav.navigate(Routes.session(it)) }, onCompare = { navigateTab(nav, Routes.COMPARE) })
            }
            composable(Routes.COMPARE) { ComparisonScreen(container, initialA = null) }
            composable(Routes.SETTINGS) { SettingsScreen(container) }
            composable(Routes.SENSOR, listOf(navArgument("key") { type = NavType.StringType })) { entry ->
                val kind = SensorKind.fromKey(entry.arguments?.getString("key").orEmpty())
                if (kind != null) {
                    ScreenScaffold(stringResource(kind.nameRes), onBack = { nav.popBackStack() }) { inner ->
                        androidx.compose.foundation.layout.Box(Modifier.padding(inner)) {
                            SensorScreen(kind, container, onOpenSession = { nav.navigate(Routes.session(it)) })
                        }
                    }
                }
            }
            composable(Routes.SESSION, listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                val id = entry.arguments?.getString("id").orEmpty()
                SessionDetailScreen(id, container, onBack = { nav.popBackStack() }, onCompare = { nav.navigate(Routes.compareWith(it)) })
            }
            composable(Routes.COMPARE_WITH, listOf(navArgument("a") { type = NavType.StringType })) { entry ->
                val id = entry.arguments?.getString("a").orEmpty()
                ScreenScaffold(stringResource(R.string.tab_compare), onBack = { nav.popBackStack() }) { inner ->
                    androidx.compose.foundation.layout.Box(Modifier.padding(inner)) { ComparisonScreen(container, initialA = id) }
                }
            }
        }
    }
}

private fun navigateTab(nav: NavHostController, route: String) {
    nav.navigate(route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
