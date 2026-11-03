package app.proxyflow.feature.logs

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

/** Logs (bottom-navigation item). */
@Serializable
data object LogsDestination

/** @param appVersion the app's version, for the header of what Copy and Export hand over. */
fun NavGraphBuilder.logsScreen(viewModelFactory: ViewModelProvider.Factory, appVersion: String) {
    composable<LogsDestination> {
        LogsRoute(viewModel = viewModel(factory = viewModelFactory), appVersion = appVersion)
    }
}
