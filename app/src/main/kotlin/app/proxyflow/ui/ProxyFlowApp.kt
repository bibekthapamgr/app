package app.proxyflow.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.proxyflow.feature.about.aboutScreen
import app.proxyflow.feature.about.navigateToAbout
import app.proxyflow.feature.home.HomeDestination
import app.proxyflow.feature.home.homeScreen
import app.proxyflow.feature.logs.logsScreen
import app.proxyflow.feature.profiles.AddEditProxyDestination
import app.proxyflow.feature.profiles.addEditProxyScreen
import app.proxyflow.feature.profiles.navigateToAddEditProxy
import app.proxyflow.feature.profiles.profilesScreen
import app.proxyflow.feature.settings.settingsScreen
import app.proxyflow.navigation.TopLevelDestination
import app.proxyflow.navigation.isTopLevel
import app.proxyflow.navigation.navigateToTopLevel

/**
 * The app shell: bottom navigation for the four top-level screens and one
 * navigation graph. Add/Edit proxy and About are full screen, without the bar.
 */
@Composable
fun ProxyFlowApp(
    viewModelFactory: ViewModelProvider.Factory,
    version: String,
    navController: NavHostController = rememberNavController(),
) {
    val currentDestination = navController.currentBackStackEntryAsState().value?.destination
    val showBottomBar = currentDestination == null || TopLevelDestination.entries.any { currentDestination.isTopLevel(it) }
    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    TopLevelDestination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = currentDestination.isTopLevel(destination),
                            onClick = { navController.navigateToTopLevel(destination) },
                            icon = { Icon(destination.icon, contentDescription = null) },
                            label = { Text(stringResource(destination.label)) },
                        )
                    }
                }
            }
        },
        // Each screen handles the status bar itself; this Scaffold only reserves the bottom bar.
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = HomeDestination,
            modifier = Modifier
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            homeScreen(
                viewModelFactory = viewModelFactory,
                onAddProxy = { navController.navigateToAddEditProxy() },
                onChangeProfile = { navController.navigateToTopLevel(TopLevelDestination.Profiles) },
            )
            profilesScreen(
                viewModelFactory = viewModelFactory,
                onAddProxy = { navController.navigateToAddEditProxy() },
                onEditProxy = { id -> navController.navigateToAddEditProxy(id) },
            )
            // Pops the form by its route, so a second call (Save landing after Back) is a no-op.
            addEditProxyScreen(
                viewModelFactory = viewModelFactory,
                onDone = { navController.popBackStack<AddEditProxyDestination>(inclusive = true) },
            )
            logsScreen(
                viewModelFactory = viewModelFactory,
                onOpenSettings = { navController.navigateToTopLevel(TopLevelDestination.Settings) },
            )
            settingsScreen(viewModelFactory = viewModelFactory, onOpenAbout = { navController.navigateToAbout() })
            aboutScreen(version = version, onBack = { navController.popBackStack() })
        }
    }
}
