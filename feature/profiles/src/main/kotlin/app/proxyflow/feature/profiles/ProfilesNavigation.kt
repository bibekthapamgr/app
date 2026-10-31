package app.proxyflow.feature.profiles

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import androidx.navigation.navOptions
import kotlinx.serialization.Serializable

/** The profiles list (bottom-navigation item). */
@Serializable
data object ProfilesDestination

/** Full-screen Add/Edit form; [profileId] is null when adding. */
@Serializable
data class AddEditProxyDestination(val profileId: String? = null)

/** Opens the form once: a double tap on "Add proxy" or on a profile does not stack a second copy. */
fun NavController.navigateToAddEditProxy(profileId: String? = null, navOptions: NavOptions? = null) =
    navigate(AddEditProxyDestination(profileId), navOptions ?: navOptions { launchSingleTop = true })

fun NavGraphBuilder.profilesScreen(viewModelFactory: ViewModelProvider.Factory, onAddProxy: () -> Unit, onEditProxy: (String) -> Unit) {
    composable<ProfilesDestination> {
        ProfilesRoute(viewModel = viewModel(factory = viewModelFactory), onAddProxy = onAddProxy, onEditProxy = onEditProxy)
    }
}

/**
 * The Add/Edit form. [onDone] closes it and must be safe to call more than once
 * (pop this destination by its route, not "pop whatever is on top"): Save
 * finishes asynchronously and can land while an animation or a second tap is in
 * flight, and dropping the call instead would leave a saved form on screen.
 */
fun NavGraphBuilder.addEditProxyScreen(viewModelFactory: ViewModelProvider.Factory, onDone: () -> Unit) {
    composable<AddEditProxyDestination> {
        AddEditProxyRoute(viewModel = viewModel(factory = viewModelFactory), onDone = onDone)
    }
}
