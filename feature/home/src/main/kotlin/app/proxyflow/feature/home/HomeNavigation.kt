package app.proxyflow.feature.home

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.dropUnlessResumed
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
    onManageProfiles: () -> Unit,
    onEditProfile: (String) -> Unit,
    onOpenLogs: () -> Unit,
    onOpenKillSwitchGuide: () -> Unit = {},
    onLearnAboutCertificates: () -> Unit = {},
) {
    composable<HomeDestination> {
        HomeRoute(
            viewModel = viewModel(factory = viewModelFactory),
            onAddProxy = onAddProxy,
            onManageProfiles = onManageProfiles,
            onEditProfile = onEditProfile,
            onOpenLogs = onOpenLogs,
            // Screens pushed on top of Home: a second tap while one opens does nothing (it would open it twice). The others
            // open single-top (the form, the bottom bar's destinations).
            onOpenKillSwitchGuide = dropUnlessResumed(block = onOpenKillSwitchGuide),
            onLearnAboutCertificates = dropUnlessResumed(block = onLearnAboutCertificates),
        )
    }
}
