package app.proxyflow.feature.profiles

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

/** The profiles list (bottom-navigation item). */
@Serializable
data object ProfilesDestination

/** Full-screen Add/Edit form; [profileId] is null when adding. */
@Serializable
data class AddEditProxyDestination(val profileId: String? = null)

fun NavController.navigateToAddEditProxy(profileId: String? = null, navOptions: NavOptions? = null) =
    navigate(AddEditProxyDestination(profileId), navOptions)

fun NavGraphBuilder.profilesScreen(
    viewModelFactory: ViewModelProvider.Factory,
    onAddProxy: () -> Unit,
    onEditProxy: (String) -> Unit,
) {
    composable<ProfilesDestination> {
        ProfilesRoute(viewModel = viewModel(factory = viewModelFactory), onAddProxy = onAddProxy, onEditProxy = onEditProxy)
    }
}

fun NavGraphBuilder.addEditProxyScreen(
    viewModelFactory: ViewModelProvider.Factory,
    onDone: () -> Unit,
) {
    composable<AddEditProxyDestination> {
        // dropUnlessResumed: a second tap (Save, then Back) during the exit animation cannot pop a second screen.
        AddEditProxyRoute(viewModel = viewModel(factory = viewModelFactory), onDone = dropUnlessResumed(block = onDone))
    }
}
