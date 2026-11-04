package app.proxyflow.feature.onboarding

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

/** "How ProxyFlow works" (A2) on its own, from About (full screen, no bottom bar). */
@Serializable
data object HowItWorksDestination

fun NavController.navigateToHowItWorks(navOptions: NavOptions? = null) = navigate(HowItWorksDestination, navOptions)

/**
 * The first-launch pages (A1–A3), shown instead of the app until they were gone through once, so they are never on the
 * app's back stack. [onFinished] runs once that is saved (or couldn't be): the app then starts at Home.
 */
@Composable
fun OnboardingFlow(viewModelFactory: ViewModelProvider.Factory, onFinished: () -> Unit) {
    OnboardingRoute(viewModel = viewModel(factory = viewModelFactory), onFinished = onFinished)
}

fun NavGraphBuilder.howItWorksScreen(onBack: () -> Unit) {
    composable<HowItWorksDestination> {
        HowItWorksScreen(onBack = dropUnlessResumed(block = onBack))
    }
}
