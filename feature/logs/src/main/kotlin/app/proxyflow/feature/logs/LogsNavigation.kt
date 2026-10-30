package app.proxyflow.feature.logs

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

/** Logs (bottom-navigation item). */
@Serializable
data object LogsDestination

fun NavGraphBuilder.logsScreen(
    viewModelFactory: ViewModelProvider.Factory,
    onOpenSettings: () -> Unit,
) {
    composable<LogsDestination> {
        LogsRoute(viewModel = viewModel(factory = viewModelFactory), onOpenSettings = onOpenSettings)
    }
}
