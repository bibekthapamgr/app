package app.proxyflow.feature.about

import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable

/** About & privacy, opened from Settings (full screen, no bottom bar). */
@Serializable
data object AboutDestination

/** The privacy policy, from About (full screen). */
@Serializable
data object PrivacyPolicyDestination

/** Help, from Settings, About and "Learn more" on Home (full screen), opened at [topic] ([HelpTopics]) when given. */
@Serializable
data class HelpDestination(val topic: String? = null)

fun NavController.navigateToAbout(navOptions: NavOptions? = null) = navigate(AboutDestination, navOptions)

fun NavController.navigateToPrivacyPolicy(navOptions: NavOptions? = null) = navigate(PrivacyPolicyDestination, navOptions)

fun NavController.navigateToHelp(topic: String? = null, navOptions: NavOptions? = null) = navigate(HelpDestination(topic), navOptions)

fun NavGraphBuilder.aboutScreen(
    version: String,
    onBack: () -> Unit,
    onOpenHowItWorks: () -> Unit = {},
    onOpenPrivacyPolicy: () -> Unit = {},
    onOpenHelp: () -> Unit = {},
    onOpenLicences: (() -> Unit)? = null,
) {
    composable<AboutDestination> {
        AboutScreen(
            version = version,
            onBack = dropUnlessResumed(block = onBack),
            onOpenHowItWorks = dropUnlessResumed(block = onOpenHowItWorks),
            onOpenPrivacyPolicy = dropUnlessResumed(block = onOpenPrivacyPolicy),
            onOpenHelp = dropUnlessResumed(block = onOpenHelp),
            onOpenLicences = onOpenLicences?.let { dropUnlessResumed(block = it) },
        )
    }
}

fun NavGraphBuilder.privacyPolicyScreen(version: String, onBack: () -> Unit) {
    composable<PrivacyPolicyDestination> {
        PrivacyPolicyScreen(version = version, onBack = dropUnlessResumed(block = onBack))
    }
}

fun NavGraphBuilder.helpScreen(onBack: () -> Unit, onOpenKillSwitchGuide: () -> Unit = {}) {
    composable<HelpDestination> { entry ->
        HelpScreen(
            onBack = dropUnlessResumed(block = onBack),
            topic = entry.toRoute<HelpDestination>().topic,
            onOpenKillSwitchGuide = dropUnlessResumed(block = onOpenKillSwitchGuide),
        )
    }
}
