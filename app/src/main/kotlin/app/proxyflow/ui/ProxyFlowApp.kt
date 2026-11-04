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
import app.proxyflow.feature.about.HelpTopics
import app.proxyflow.feature.about.aboutScreen
import app.proxyflow.feature.about.helpScreen
import app.proxyflow.feature.about.navigateToAbout
import app.proxyflow.feature.about.navigateToHelp
import app.proxyflow.feature.about.navigateToPrivacyPolicy
import app.proxyflow.feature.about.privacyPolicyScreen
import app.proxyflow.feature.home.HomeDestination
import app.proxyflow.feature.home.homeScreen
import app.proxyflow.feature.logs.logsScreen
import app.proxyflow.feature.onboarding.howItWorksScreen
import app.proxyflow.feature.onboarding.navigateToHowItWorks
import app.proxyflow.feature.profiles.AddEditProxyDestination
import app.proxyflow.feature.profiles.addEditProxyScreen
import app.proxyflow.feature.profiles.navigateToAddEditProxy
import app.proxyflow.feature.profiles.profilesScreen
import app.proxyflow.feature.settings.killSwitchGuideScreen
import app.proxyflow.feature.settings.navigateToKillSwitchGuide
import app.proxyflow.feature.settings.settingsScreen
import app.proxyflow.navigation.TopLevelDestination
import app.proxyflow.navigation.isTopLevel
import app.proxyflow.navigation.navigateToTopLevel

/**
 * The app shell: bottom navigation for the four top-level screens and one
 * navigation graph. Add/Edit proxy, About and the pages it opens (how ProxyFlow
 * works, the privacy policy, Help) and the system kill switch guide are full
 * screen, without the bar.
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
                onEditProfile = { id -> navController.navigateToAddEditProxy(id) },
                onOpenLogs = { navController.navigateToTopLevel(TopLevelDestination.Logs) },
                onOpenKillSwitchGuide = { navController.navigateToKillSwitchGuide() },
                onLearnAboutCertificates = { navController.navigateToHelp(HelpTopics.CERTIFICATES) },
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
            logsScreen(viewModelFactory = viewModelFactory, appVersion = version)
            settingsScreen(
                viewModelFactory = viewModelFactory,
                onOpenAbout = { navController.navigateToAbout() },
                onOpenKillSwitchGuide = { navController.navigateToKillSwitchGuide() },
                onOpenHelp = { navController.navigateToHelp() },
            )
            killSwitchGuideScreen(viewModelFactory = viewModelFactory, onBack = { navController.popBackStack() })
            aboutScreen(
                version = version,
                onBack = { navController.popBackStack() },
                onOpenHowItWorks = { navController.navigateToHowItWorks() },
                onOpenPrivacyPolicy = { navController.navigateToPrivacyPolicy() },
                onOpenHelp = { navController.navigateToHelp() },
            )
            howItWorksScreen(onBack = { navController.popBackStack() })
            privacyPolicyScreen(version = version, onBack = { navController.popBackStack() })
            helpScreen(
                onBack = { navController.popBackStack() },
                onOpenKillSwitchGuide = { navController.navigateToKillSwitchGuide() },
            )
        }
    }
}
