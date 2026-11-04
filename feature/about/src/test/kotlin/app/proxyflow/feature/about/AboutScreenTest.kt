package app.proxyflow.feature.about

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.proxyflow.core.designsystem.theme.ProxyFlowTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AboutScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun showsVersionScopeAndLimits() {
        compose.setContent { ProxyFlowTheme { AboutScreen(version = "0.2.0 (2)", onBack = {}) } }
        compose.onNodeWithText("Version 0.2.0 (2) · free, no account").assertIsDisplayed()
        compose.onNodeWithText("It doesn't make you anonymous", substring = true).assertIsDisplayed()
        compose.onNodeWithText("encrypted with an Android Keystore key that is never exported or backed up", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("UDP is blocked", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("its built-in apps, such as Google Play services, don't use the VPN", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("or devices tethered to it", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("HTTPS sites stay encrypted end to end whether you use it or not", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    /** Board G1 (M9): how it works, the privacy policy, help and the licences, each opened by its row. */
    @Test
    fun eachLinkOpensItsPage() {
        val opened = mutableListOf<String>()
        compose.setContent {
            ProxyFlowTheme {
                AboutScreen(
                    version = "0.2.0 (2)",
                    onBack = {},
                    onOpenHowItWorks = { opened += "how" },
                    onOpenPrivacyPolicy = { opened += "privacy" },
                    onOpenHelp = { opened += "help" },
                    onOpenLicences = { opened += "licences" },
                )
            }
        }
        compose.onNodeWithText("How ProxyFlow works").performScrollTo().performClick()
        compose.onNodeWithText("Privacy policy").performScrollTo().performClick()
        compose.onNodeWithText("Help").performScrollTo().performClick()
        compose.onNodeWithText("Open-source licences").performScrollTo().performClick()
        assertEquals(listOf("how", "privacy", "help", "licences"), opened)
    }

    /** The privacy policy says what stays on the phone, what is sent where, and that nothing goes to the developer. */
    @Test
    fun thePrivacyPolicySaysWhatIsKeptAndWhatIsSent() {
        var backs = 0
        compose.setContent { ProxyFlowTheme { PrivacyPolicyScreen(version = "0.9.0 (9)", onBack = { backs++ }) } }
        compose.onNodeWithText("ProxyFlow has no servers, no account, no ads and no analytics", substring = true).assertIsDisplayed()
        for (text in listOf(
            "encrypted with AES-256-GCM",
            "never includes passwords, server or site names, addresses or page content",
            "None of this is included in backups",
            "sent only to that profile's proxy",
            "connectivitycheck.gstatic.com on port 443",
            "Nothing is sent to ProxyFlow's developer",
            "Profile names are hidden on the lock screen",
            "This policy describes ProxyFlow version 0.9.0 (9).",
        )) {
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(text, substring = true))
            compose.onNodeWithText(text, substring = true).assertIsDisplayed()
        }
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs)
    }

    /** Help opens at the topic a link names ("Learn more" on Home: certificates), and its kill switch topic opens the guide. */
    @Test
    fun helpOpensAtTheTopicALinkNames() {
        var guides = 0
        compose.setContent {
            ProxyFlowTheme { HelpScreen(onBack = {}, topic = HelpTopics.CERTIFICATES, onOpenKillSwitchGuide = { guides++ }) }
        }
        compose.onNodeWithText("Certificates and TLS to your proxy").assertIsDisplayed()
        compose.onNodeWithText("doesn't accept self-signed certificates", substring = true).performScrollTo().assertIsDisplayed()
        // A lazy list composes only what is near the screen: scrolled to by the list itself.
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Set up the system kill switch"))
        compose.onNodeWithText("Set up the system kill switch").performClick()
        assertEquals(1, guides)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Getting help"))
        compose.onNodeWithText("Getting help").assertIsDisplayed()
    }

    @Test
    fun backLeavesThePage() {
        var backs = 0
        compose.setContent { ProxyFlowTheme { AboutScreen(version = "0.2.0 (2)", onBack = { backs++ }) } }
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs)
    }
}
