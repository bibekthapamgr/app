package app.proxyflow.feature.settings

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

/** Settings (bottom-navigation item). */
@Serializable
data object SettingsDestination

fun NavGraphBuilder.settingsScreen(viewModelFactory: ViewModelProvider.Factory, onOpenAbout: () -> Unit) {
    composable<SettingsDestination> {
        SettingsRoute(viewModel = viewModel(factory = viewModelFactory), onOpenAbout = onOpenAbout)
    }
}
