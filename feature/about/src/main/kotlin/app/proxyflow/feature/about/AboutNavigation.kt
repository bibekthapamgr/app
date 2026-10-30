package app.proxyflow.feature.about

import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

/** About & privacy, opened from Settings (full screen, no bottom bar). */
@Serializable
data object AboutDestination

fun NavController.navigateToAbout(navOptions: NavOptions? = null) = navigate(AboutDestination, navOptions)

fun NavGraphBuilder.aboutScreen(
    version: String,
    onBack: () -> Unit,
) {
    composable<AboutDestination> {
        AboutScreen(version = version, onBack = dropUnlessResumed(block = onBack))
    }
}
