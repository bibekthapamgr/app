package app.proxyflow.feature.settings

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

/** Settings (bottom-navigation item). */
@Serializable
data object SettingsDestination

/** The system kill switch guide (board E2), from Settings and from Home (full screen, no bottom bar). */
@Serializable
data object KillSwitchGuideDestination

fun NavController.navigateToKillSwitchGuide(navOptions: NavOptions? = null) = navigate(KillSwitchGuideDestination, navOptions)

fun NavGraphBuilder.settingsScreen(
    viewModelFactory: ViewModelProvider.Factory,
    onOpenAbout: () -> Unit,
    onOpenKillSwitchGuide: () -> Unit = {},
    onOpenHelp: () -> Unit = {},
) {
    composable<SettingsDestination> {
        // A second tap while the next screen opens does nothing (it would open it twice).
        SettingsRoute(
            viewModel = viewModel(factory = viewModelFactory),
            onOpenAbout = dropUnlessResumed(block = onOpenAbout),
            onOpenKillSwitchGuide = dropUnlessResumed(block = onOpenKillSwitchGuide),
            onOpenHelp = dropUnlessResumed(block = onOpenHelp),
        )
    }
}

fun NavGraphBuilder.killSwitchGuideScreen(viewModelFactory: ViewModelProvider.Factory, onBack: () -> Unit) {
    composable<KillSwitchGuideDestination> {
        KillSwitchGuideRoute(viewModel = viewModel(factory = viewModelFactory), onBack = dropUnlessResumed(block = onBack))
    }
}
