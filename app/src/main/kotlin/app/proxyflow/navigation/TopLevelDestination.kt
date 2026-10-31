package app.proxyflow.navigation

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import app.proxyflow.R
import app.proxyflow.core.designsystem.icon.PfIcons
import app.proxyflow.feature.home.HomeDestination
import app.proxyflow.feature.logs.LogsDestination
import app.proxyflow.feature.profiles.ProfilesDestination
import app.proxyflow.feature.settings.SettingsDestination

/** The four bottom-navigation items (docs/m1/04 §2). */
enum class TopLevelDestination(val route: Any, val icon: ImageVector, @StringRes val label: Int) {
    Home(HomeDestination, PfIcons.Home, R.string.nav_home),
    Profiles(ProfilesDestination, PfIcons.Profiles, R.string.nav_profiles),
    Logs(LogsDestination, PfIcons.Logs, R.string.nav_logs),
    Settings(SettingsDestination, PfIcons.Settings, R.string.nav_settings),
}

/** True when [destination] (or one of its parents) is this item's screen. */
fun NavDestination?.isTopLevel(destination: TopLevelDestination): Boolean =
    this?.hierarchy?.any { it.hasRoute(destination.route::class) } == true

/**
 * Switches tabs the standard way: one copy of each tab on the back stack, Back
 * from any tab returns to Home, and each tab keeps its scroll and state.
 */
fun NavController.navigateToTopLevel(destination: TopLevelDestination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
