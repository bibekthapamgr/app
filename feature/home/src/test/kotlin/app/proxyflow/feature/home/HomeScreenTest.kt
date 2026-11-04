package app.proxyflow.feature.home

import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.proxyflow.core.designsystem.theme.ProxyFlowTheme
import app.proxyflow.core.model.ConnectStep
import app.proxyflow.core.model.ConnectionError
import app.proxyflow.core.model.ConnectionState
import app.proxyflow.core.model.ConnectionStatus
import app.proxyflow.core.model.DnsProblems
import app.proxyflow.core.model.ProxyProfile
import app.proxyflow.core.model.ProxyProtocol
import app.proxyflow.core.model.ReconnectInfo
import app.proxyflow.core.model.ReconnectReason
import app.proxyflow.core.model.RoutingDiagnostics
import app.proxyflow.core.model.TlsMode
import app.proxyflow.core.model.TlsProblems
import app.proxyflow.core.model.TrafficStats
import app.proxyflow.core.model.UnsupportedTraffic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val profile = ProxyProfile(id = "p1", name = "Office", protocol = ProxyProtocol.HTTP, host = "203.0.113.10", port = 3128)
    private val calls = mutableListOf<String>()
    private val actions = HomeActions(
        onAddProxy = { calls += "add" },
        onChangeProfile = { calls += "change" },
        onEditProfile = { calls += "edit $it" },
        onConnect = { calls += "connect" },
        onDisconnect = { calls += "disconnect" },
        onStop = { calls += "stop" },
        onConfirmStop = { calls += "confirm stop" },
        onExplainerContinue = { calls += "continue ${it.action}" },
        onDismissDialog = { calls += "dismiss" },
        onRetryNow = { calls += "retry now" },
        onOpenLogs = { calls += "logs" },
        onOpenDateSettings = { calls += "date settings" },
    )

    @Test
    fun emptyStateLeadsToAddProxy() {
        show(HomeUiState.NoProfile)
        compose.onNodeWithText("Add a proxy to get started").assertIsDisplayed()
        compose.onNodeWithText("Add proxy").performClick()
        assertEquals(listOf("add"), calls)
    }

    @Test
    fun connectIsDisabledAndExplainedWhileUnavailable() {
        show(ready(ConnectionState.Idle, canConnect = false))
        compose.onNodeWithText("Not connected").assertIsDisplayed()
        compose.onNodeWithText("Office").assertIsDisplayed()
        compose.onNodeWithText("HTTP · No TLS to proxy").assertIsDisplayed()
        compose.onNodeWithText("203.0.113.10:3128").assertIsDisplayed()
        scrolledTo("Connect").assertIsNotEnabled()
        scrolledTo("Connecting isn't available in this build. It saves your proxies but doesn't connect.").assertIsDisplayed()
    }

    @Test
    fun connectingShowsTheCheckBeforeAnythingChangesAndCanBeCancelled() {
        show(ready(ConnectionState(ConnectionStatus.Connecting, "p1", "Office", step = ConnectStep.ProxyHandshake)))
        compose.onNodeWithText("Connecting…").assertIsDisplayed()
        compose.onNodeWithText("Connecting to Office").assertIsDisplayed()
        // One row per stage (ADR-0012): the ones before the current stage done, the ones after not started.
        val rows = listOf("Getting ready", "VPN permission", "Checking proxy — TLS and sign-in", "Proxy accepted — starting the VPN")
        assertEquals(listOf("Done", "Done", "In progress", "Not started"), rows.map { stateOf(it) })
        scrolledTo("Checking proxy — TLS and sign-in").assertIsDisplayed()
        scrolledTo(
            "Your phone's network is unchanged until the VPN starts. If the proxy check fails, nothing is switched over.",
        ).assertIsDisplayed()
        scrolledTo("Cancel").performClick()
        assertEquals(listOf("disconnect"), calls)
    }

    @Test
    fun connectedShowsItsTimeAndDisconnect() {
        val since = 1_800_000_000_000L
        show(ready(ConnectionState(ConnectionStatus.Connected, "p1", "Office", connectedSinceMillis = since)), now = since + 3_723_000)
        compose.onNodeWithText("Connected").assertIsDisplayed()
        compose.onNodeWithText("01:02:03").assertIsDisplayed()
        scrolledTo("Apps' TCP connections go through your proxy").assertIsDisplayed()
        scrolledTo("Office · HTTP").assertIsDisplayed()
        // Before the first reading, what goes through the proxy and what doesn't.
        scrolledTo("Via proxy").assertIsDisplayed()
        scrolledTo("Resolved by proxy").assertIsDisplayed()
        scrolledTo("Blocked").assertIsDisplayed()
        scrolledTo(
            "HTTPS sites stay encrypted end to end: ProxyFlow never decrypts them. " +
                "TLS to proxy only encrypts the connection between this phone and your proxy.",
        ).assertIsDisplayed()
        scrolledTo("Disconnect").performClick()
        assertEquals(listOf("disconnect"), calls)
    }

    /** M6 diagnostics (ADR-0011): TLS to the proxy, DNS, unsupported traffic, proxy timeouts; counts only. */
    @Test
    fun connectedShowsTheDiagnostics() {
        val diagnostics = RoutingDiagnostics(
            connectionsOpened = 42,
            namesForProxy = 31,
            dns = DnsProblems(namesNotReached = 2, queriesRefused = 1),
            unsupported = UnsupportedTraffic(udpBlocked = 12, udpTopPorts = listOf(443, 3478), pingsDropped = 3),
            proxyTimeouts = 1,
            lastProblem = ConnectionError("TARGET_UNREACHABLE", "the proxy could not reach the host"),
        )
        show(ready(ConnectionState(ConnectionStatus.Connected, "p1", "Office", connectedSinceMillis = 0), diagnostics = diagnostics))
        scrolledTo("Via proxy · 42 connections").assertIsDisplayed()
        scrolledTo("Resolved by proxy · 31 names").assertIsDisplayed()
        scrolledTo("Not used").assertIsDisplayed() // the profile uses no TLS to the proxy
        scrolledTo("2 failed").assertIsDisplayed() // the refused lookup is shown, but is no failure
        scrolledTo("2 connections to names your proxy couldn't find or reach\n1 lookup of a kind ProxyFlow doesn't answer (TXT, MX…)")
            .assertIsDisplayed()
        scrolledTo("15 blocked").assertIsDisplayed()
        compose.onNodeWithText("12 UDP packets (ports 443, 3478) · 3 pings", substring = true).performScrollTo().assertIsDisplayed()
        scrolledTo("1 connection timed out").assertIsDisplayed()
        scrolledTo("Last problem: TARGET_UNREACHABLE · the proxy could not reach the host").assertIsDisplayed()
    }

    @Test
    fun lookupsRefusedByDesignAreShownWithoutAFailure() {
        val diagnostics = RoutingDiagnostics(dns = DnsProblems(queriesRefused = 2))
        show(ready(ConnectionState(ConnectionStatus.Connected, "p1", "Office", connectedSinceMillis = 0), diagnostics = diagnostics))
        scrolledTo("2 lookups of a kind ProxyFlow doesn't answer (TXT, MX…)").assertIsDisplayed()
        val failures = compose.onAllNodesWithText("failed", substring = true).fetchSemanticsNodes()
        assertTrue("no failure shown: $failures", failures.isEmpty())
    }

    @Test
    fun oneBlockedPortIsNamedInTheSingular() {
        val diagnostics = RoutingDiagnostics(unsupported = UnsupportedTraffic(udpBlocked = 5, udpTopPorts = listOf(443)))
        show(ready(ConnectionState(ConnectionStatus.Connected, "p1", "Office", connectedSinceMillis = 0), diagnostics = diagnostics))
        compose.onNodeWithText("5 UDP packets (port 443)", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun tlsFailuresWhileConnectedSayWhichCheckFailed() {
        val tlsProfile = profile.copy(tlsMode = TlsMode.Tls)
        val diagnostics = RoutingDiagnostics(tls = TlsProblems(failures = 2, lastCode = "TLS_UNTRUSTED"))
        show(HomeUiState.Ready(tlsProfile, ConnectionState(ConnectionStatus.Connected, "p1", "Office"), true, diagnostics = diagnostics))
        scrolledTo("Office · HTTP · TLS to proxy").assertIsDisplayed()
        scrolledTo("2 failed").assertIsDisplayed()
        scrolledTo("Certificate not trusted").assertIsDisplayed()
        scrolledTo("No failures").assertIsDisplayed() // DNS
        scrolledTo("None blocked").assertIsDisplayed()
        scrolledTo("None").assertIsDisplayed()
    }

    /** A refused certificate can only be fixed, never skipped: no button may accept it anyway (ADR-0005). */
    @Test
    fun aRefusedCertificateOffersNoWayAroundTheCheck() {
        var state by mutableStateOf(ready(ConnectionState(profileId = "p1", error = ConnectionError("TLS_UNTRUSTED"))))
        compose.setContent { ProxyFlowTheme { HomeScreen(state, actions) } }
        for (code in listOf("TLS_UNTRUSTED", "TLS_HOSTNAME", "TLS_EXPIRED", "TLS_HANDSHAKE")) {
            state = ready(ConnectionState(profileId = "p1", error = ConnectionError(code)))
            compose.waitForIdle()
            compose.onNodeWithText("Code: $code").performScrollTo().assertIsDisplayed()
            val buttons = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().map { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ").orEmpty()
            }
            val expected = setOf("Try again", "Edit profile", "Use another proxy", "Change", "View logs") +
                (if (code == "TLS_EXPIRED") setOf("Date & time settings") else emptySet())
            assertEquals("$code: $buttons", expected, buttons.toSet())
            assertTrue(compose.onAllNodesWithText("anyway", substring = true, ignoreCase = true).fetchSemanticsNodes().isEmpty())
        }
    }

    @Test
    fun aFailedCheckSaysWhyAndThatNothingChanged() {
        show(ready(ConnectionState(profileId = "p1", error = ConnectionError("PROXY_UNREACHABLE"))))
        compose.onNodeWithText("Can't reach your proxy").assertIsDisplayed()
        scrolledTo(
            "ProxyFlow couldn't connect to 203.0.113.10:3128. Check the address and port, and that the proxy is online.",
        ).assertIsDisplayed()
        scrolledTo("You're not connected — apps are still using your normal network.").assertIsDisplayed()
        scrolledTo("Code: PROXY_UNREACHABLE").assertIsDisplayed()
        scrolledTo("Edit profile").performClick()
        scrolledTo("Use another proxy").performClick()
        scrolledTo("Try again").performClick()
        assertEquals(listOf("edit p1", "change", "connect"), calls)
    }

    @Test
    fun blockedTrafficOffersRestartAndStop() {
        val stopped = ConnectionError(ConnectionError.ENGINE_STOPPED, "reading the TUN interface failed")
        show(ready(ConnectionState(ConnectionStatus.Blocked, "p1", "Office", error = stopped)))
        compose.onNodeWithText("Traffic blocked").assertIsDisplayed()
        scrolledTo("ProxyFlow stopped unexpectedly").assertIsDisplayed()
        scrolledTo("Apps can't go online until this is fixed — ProxyFlow sends nothing around your proxy.").assertIsDisplayed()
        scrolledTo("Code: ENGINE_STOPPED · reading the TUN interface failed").assertIsDisplayed()
        scrolledTo("Restart").performClick()
        scrolledTo("Stop and use normal network").performClick()
        assertEquals(listOf("connect", "stop"), calls)
    }

    @Test
    fun aDeletedProfileLeavesOnlyStop() {
        show(ready(ConnectionState(ConnectionStatus.Blocked, "p1", "Office", error = ConnectionError(ConnectionError.PROFILE_DELETED))))
        compose.onNodeWithText("Traffic blocked").assertIsDisplayed()
        scrolledTo("Profile deleted").assertIsDisplayed()
        scrolledTo("Code: PROFILE_DELETED").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertDoesNotExist()
        compose.onNodeWithText("Edit profile").assertDoesNotExist()
        scrolledTo("Stop and use normal network").performClick()
        assertEquals(listOf("stop"), calls)
    }

    /** What providers send for an expired plan or an address they don't allow fails the check, and says what to check. */
    @Test
    fun aProxyRefusingTheCheckSaysWhatToCheck() {
        show(
            ready(
                ConnectionState(
                    profileId = "p1",
                    error = ConnectionError("TARGET_NOT_ALLOWED", "proxy refused this destination (HTTP 403)"),
                ),
            ),
        )
        compose.onNodeWithText("Your proxy refused the connection").assertIsDisplayed()
        scrolledTo(
            "203.0.113.10:3128 refused to open a test connection. Providers answer this way when a plan has ended or " +
                "your network's address isn't allowed. Check your account with your proxy provider.",
        ).assertIsDisplayed()
        scrolledTo("You're not connected — apps are still using your normal network.").assertIsDisplayed()
    }

    @Test
    fun aProxyWithoutAWayOutSaysSo() {
        show(
            ready(
                ConnectionState(
                    profileId = "p1",
                    error = ConnectionError("TARGET_UNREACHABLE", "proxy could not reach the destination (HTTP 503)"),
                ),
            ),
        )
        compose.onNodeWithText("Your proxy can't reach the internet").assertIsDisplayed()
        scrolledTo(
            "203.0.113.10:3128 accepted the connection but couldn't open a test connection to connectivitycheck.gstatic.com. " +
                "The proxy may have no internet access right now, or it blocks that address. Check with your proxy provider.",
        ).assertIsDisplayed()
    }

    @Test
    fun stoppingWhileBlockedAsksFirst() {
        val blocked = ConnectionState(ConnectionStatus.Blocked, "p1", "Office", error = ConnectionError(ConnectionError.ENGINE_STOPPED))
        show(ready(blocked, dialog = HomeDialog.ConfirmStop))
        compose.onNodeWithText("Use your normal network?").assertIsDisplayed()
        compose.onNodeWithText("Apps will use your normal network, not your proxy, until you connect again.").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(listOf("dismiss"), calls)
    }

    @Test
    fun aRevokedVpnSaysAndroidRemovedIt() {
        show(ready(ConnectionState(profileId = "p1", error = ConnectionError(ConnectionError.VPN_REVOKED))))
        compose.onNodeWithText("Disconnected by Android").assertIsDisplayed()
        scrolledTo("Your apps are now using your normal network.").assertIsDisplayed()
        scrolledTo("Reconnect").performClick()
        assertEquals(listOf("connect"), calls)
    }

    @Test
    fun theExplainerComesBeforeAndroidsConsentScreen() {
        show(ready(ConnectionState.Idle, dialog = HomeDialog.VpnExplainer(Intent("android.net.vpn.SETUP_VPN"))))
        compose.onNodeWithText("Allow a VPN connection").assertIsDisplayed()
        compose.onNodeWithText("Continue").performClick()
        assertEquals(listOf("continue android.net.vpn.SETUP_VPN"), calls)
    }

    @Test
    fun aRefusedPermissionShowsH1() {
        show(ready(ConnectionState.Idle, dialog = HomeDialog.VpnPermissionDenied))
        compose.onNodeWithText("VPN permission needed").assertIsDisplayed()
        compose.onNodeWithText("Not now").performClick()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(listOf("dismiss", "connect"), calls)
    }

    /** B4's counters (ADR-0012): the session's data sent and received, and the connections open now. */
    @Test
    fun connectedShowsTheSessionsTraffic() {
        val since = 1_800_000_000_000L
        val traffic = TrafficStats(bytesUp = 1_234, bytesDown = 34_500_000, activeConnections = 12, sinceMillis = since)
        show(
            ready(ConnectionState(ConnectionStatus.Connected, "p1", "Office", connectedSinceMillis = since), traffic = traffic),
            now = since,
        )
        compose.onNodeWithContentDescription("Sent 1.2 kB").assertIsDisplayed()
        compose.onNodeWithContentDescription("Received 34.5 MB").assertIsDisplayed()
        compose.onNodeWithContentDescription("Active connections 12").assertIsDisplayed()
        scrolledTo("Approximate: what apps sent and received through your proxy since you connected.").assertIsDisplayed()
    }

    @Test
    fun trafficNotReadYetShowsADash() {
        show(ready(ConnectionState(ConnectionStatus.Connected, "p1", "Office", connectedSinceMillis = 0)))
        compose.onNodeWithContentDescription("Sent –").assertIsDisplayed()
        compose.onNodeWithContentDescription("Active connections –").assertIsDisplayed()
    }

    /** Board B5 (ADR-0012): the attempt, the wait before the next one, and every way on. */
    @Test
    fun reconnectingOnItsOwnShowsTheAttemptAndEveryWayOn() {
        val now = 1_800_000_000_000L
        val retry = ReconnectInfo(
            ReconnectReason.ProxyFailures,
            attempt = 2,
            maxAttempts = 8,
            nextAttemptAtMillis = now + 3_200,
            lastError = ConnectionError("PROXY_UNREACHABLE", "no route to the proxy"),
        )
        show(
            ready(ConnectionState(ConnectionStatus.Reconnecting, "p1", "Office", connectedSinceMillis = now - 60_000, reconnect = retry)),
            now,
        )
        compose.onNodeWithText("Reconnecting").assertIsDisplayed()
        compose.onNodeWithText("Traffic paused — ProxyFlow sends nothing around your proxy").assertIsDisplayed()
        scrolledTo("Can't reach your proxy").assertIsDisplayed()
        scrolledTo("ProxyFlow is trying your proxy again. Apps stay paused until it answers.").assertIsDisplayed()
        scrolledTo("Attempt 2 of 8 · next try in 4 s").assertIsDisplayed()
        scrolledTo("Code: PROXY_UNREACHABLE · no route to the proxy").assertIsDisplayed()
        scrolledTo("Retry now").assertIsEnabled().performClick()
        scrolledTo("Edit profile").performClick()
        scrolledTo("Stop and use normal network").performClick()
        scrolledTo("View logs").performClick()
        assertEquals(listOf("retry now", "edit p1", "stop", "logs"), calls)
    }

    @Test
    fun anAttemptThatRunsCannotBeRunSooner() {
        val retry = ReconnectInfo(ReconnectReason.NetworkBack, attempt = 1, maxAttempts = 8, nextAttemptAtMillis = null, lastError = null)
        show(ready(ConnectionState(ConnectionStatus.Reconnecting, "p1", "Office", connectedSinceMillis = 0, reconnect = retry)))
        scrolledTo("Back online").assertIsDisplayed()
        scrolledTo("Your phone has a network again. ProxyFlow is reconnecting to your proxy.").assertIsDisplayed()
        scrolledTo("Attempt 1 of 8 · trying now…").assertIsDisplayed()
        scrolledTo("Retry now").assertIsNotEnabled()
    }

    /** Board B7: no network, traffic held until one is back; stopping is still the user's choice. */
    @Test
    fun offlineWaitsForANetworkWithTrafficHeld() {
        val offline = ConnectionState(ConnectionStatus.Offline, "p1", "Office", error = ConnectionError(ConnectionError.OFFLINE))
        show(ready(offline))
        compose.onNodeWithText("Waiting for network").assertIsDisplayed()
        scrolledTo("You're offline").assertIsDisplayed()
        scrolledTo("ProxyFlow will connect when a network is available. Traffic stays blocked meanwhile.").assertIsDisplayed()
        scrolledTo("Code: OFFLINE").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertDoesNotExist()
        scrolledTo("Stop and use normal network").performClick()
        assertEquals(listOf("stop"), calls)
    }

    @Test
    fun reconnectingThatStoppedSaysAfterHowManyAttempts() {
        val gaveUp = ConnectionError("PROXY_UNREACHABLE", "reconnecting stopped after 8 attempts")
        show(ready(ConnectionState(ConnectionStatus.Blocked, "p1", "Office", error = gaveUp)))
        compose.onNodeWithText("Traffic blocked").assertIsDisplayed()
        scrolledTo("Can't reach your proxy").assertIsDisplayed()
        scrolledTo("Code: PROXY_UNREACHABLE · reconnecting stopped after 8 attempts").assertIsDisplayed()
        scrolledTo("Try again").performClick()
        scrolledTo("View logs").performClick()
        assertEquals(listOf("connect", "logs"), calls)
    }

    /** What the profile fixes offers Edit profile first; what may pass by itself offers Try again first (docs/m1/04 §4). */
    @Test
    fun theFirstActionIsWhatFixesTheError() {
        var state by mutableStateOf(ready(ConnectionState(profileId = "p1", error = ConnectionError("AUTH_FAILED"))))
        compose.setContent { ProxyFlowTheme { HomeScreen(state, actions) } }
        val editFirst =
            listOf("AUTH_REQUIRED", "AUTH_FAILED", "TLS_UNTRUSTED", "TLS_HOSTNAME", "TLS_HANDSHAKE", "PROTOCOL_ERROR", "PROXY_DNS_FAILED")
        val retryFirst = listOf("PROXY_UNREACHABLE", "HANDSHAKE_TIMEOUT", "TLS_EXPIRED", "TARGET_UNREACHABLE")
        for (code in editFirst + retryFirst) {
            for (status in listOf(ConnectionStatus.Disconnected, ConnectionStatus.Blocked)) {
                state = ready(ConnectionState(status, "p1", "Office", error = ConnectionError(code)))
                compose.waitForIdle()
                val buttons = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().map { node ->
                    node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ").orEmpty()
                }
                val edit = buttons.indexOf("Edit profile")
                val retry = buttons.indexOf("Try again")
                assertTrue("$code $status: $buttons", edit >= 0 && retry >= 0)
                assertEquals("$code $status: $buttons", code in editFirst, edit < retry)
            }
        }
    }

    @Test
    fun anExpiredLookingCertificateOffersTheDateSettings() {
        show(ready(ConnectionState(profileId = "p1", error = ConnectionError("TLS_EXPIRED"))))
        scrolledTo("Date & time settings").performClick()
        assertEquals(listOf("date settings"), calls)
    }

    /** The catalogue's variants: a sign-in method ProxyFlow lacks, and an HTTP proxy that allows no tunnels at all. */
    @Test
    fun anUnsupportedSignInOrNoTunnelsAreSaidAsSuch() {
        var state by mutableStateOf(
            ready(
                ConnectionState(
                    profileId = "p1",
                    error = ConnectionError("AUTH_FAILED", "proxy offers only digest authentication; V1 supports Basic"),
                ),
            ),
        )
        compose.setContent { ProxyFlowTheme { HomeScreen(state, actions) } }
        scrolledTo(
            "This proxy only offers a sign-in method ProxyFlow doesn't support. " +
                "ProxyFlow signs in to HTTP proxies with Basic sign-in: ask your provider for it, or use a SOCKS5 proxy.",
        ).assertIsDisplayed()
        state =
            ready(ConnectionState(profileId = "p1", error = ConnectionError("PROTOCOL_ERROR", "proxy does not support CONNECT (HTTP 405)")))
        compose.waitForIdle()
        scrolledTo("This doesn't look like a HTTP proxy").assertIsDisplayed()
        scrolledTo(
            "This HTTP proxy doesn't allow tunnels (CONNECT), which ProxyFlow needs. " +
                "Check the port, or ask your provider for a proxy that allows them.",
        ).assertIsDisplayed()
    }

    private fun stateOf(text: String): String? =
        compose.onNodeWithText(text, useUnmergedTree = false).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    private fun ready(
        connection: ConnectionState,
        canConnect: Boolean = true,
        dialog: HomeDialog? = null,
        diagnostics: RoutingDiagnostics? = null,
        traffic: TrafficStats? = null,
    ) = HomeUiState.Ready(profile, connection, canConnect, dialog, diagnostics, traffic)

    private fun show(state: HomeUiState, now: Long = 0L) {
        compose.setContent { ProxyFlowTheme { HomeScreen(state, actions, nowMillis = now) } }
    }

    private fun scrolledTo(text: String): SemanticsNodeInteraction = compose.onNodeWithText(text).performScrollTo()
}
