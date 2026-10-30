package app.proxyflow.feature.home

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

/** Home: the start destination and the first bottom-navigation item. */
@Serializable
data object HomeDestination

fun NavGraphBuilder.homeScreen(
    viewModelFactory: ViewModelProvider.Factory,
    onAddProxy: () -> Unit,
    onChangeProfile: () -> Unit,
) {
    composable<HomeDestination> {
        HomeRoute(
            viewModel = viewModel(factory = viewModelFactory),
            onAddProxy = onAddProxy,
            onChangeProfile = onChangeProfile,
        )
    }
}
