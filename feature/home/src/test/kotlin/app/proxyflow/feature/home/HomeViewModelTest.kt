package app.proxyflow.feature.home

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.proxyflow.core.data.ConnectionController
import app.proxyflow.core.data.InMemoryProtectionStore
import app.proxyflow.core.data.ProtectionStore
import app.proxyflow.core.data.UnavailableConnectionController
import app.proxyflow.core.model.ConnectionError
import app.proxyflow.core.model.ConnectionState
import app.proxyflow.core.model.ConnectionStatus
import app.proxyflow.core.model.ExitReason
import app.proxyflow.core.model.Interruption
import app.proxyflow.core.model.ProxyProfileInput
import app.proxyflow.core.model.ReconnectInfo
import app.proxyflow.core.model.ReconnectReason
import app.proxyflow.core.model.RoutingDiagnostics
import app.proxyflow.core.model.SystemKillSwitch
import app.proxyflow.core.model.TrafficStats
import app.proxyflow.core.testing.MainDispatcherRule
import app.proxyflow.core.testing.TestProfiles
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Robolectric only for android.content.Intent (the consent screen the controller hands over). */
@RunWith(AndroidJUnit4::class)
class HomeViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val profiles = TestProfiles().repository

    @Test
    fun showsTheEmptyStateUntilAProfileIsSaved() = runTest {
        val viewModel = HomeViewModel(profiles, UnavailableConnectionController())
        observe(viewModel)
        assertEquals(HomeUiState.NoProfile, viewModel.uiState.value)

        profiles.save(null, ProxyProfileInput(server = "proxy.example.com", port = "1080"))

        val ready = ready(viewModel)
        assertEquals("proxy.example.com", ready.profile.host)
        assertEquals(ConnectionStatus.Disconnected, ready.status)
        assertFalse("a build without the VPN cannot connect, and Home must say so", ready.canConnect)
    }

    @Test
    fun showsTheActiveProfile() = runTest {
        val viewModel = HomeViewModel(profiles, UnavailableConnectionController())
        observe(viewModel)
        profiles.save(null, ProxyProfileInput(server = "first.example.com", port = "1080"))
        val second = profiles.save(null, ProxyProfileInput(server = "second.example.com", port = "1080"))

        assertEquals("first.example.com", ready(viewModel).profile.host)
        profiles.setActive(second.id)
        assertEquals("second.example.com", ready(viewModel).profile.host)
    }

    @Test
    fun connectIsOnlyForwardedWhenConnectingIsAvailable() = runTest {
        val unavailable = RecordingController(canConnect = false)
        val available = RecordingController(canConnect = true)
        val saved = profiles.save(null, ProxyProfileInput(server = "proxy.example.com", port = "1080"))

        for (controller in listOf(unavailable, available)) {
            val viewModel = HomeViewModel(profiles, controller)
            observe(viewModel)
            viewModel.connect()
        }

        assertEquals(emptyList<String>(), unavailable.connects)
        assertEquals(listOf(saved.id), available.connects)
    }

    @Test
    fun androidsConsentIsAskedBehindTheExplainerBeforeAnythingStarts() = runTest {
        val consent = Intent("android.net.vpn.SETUP_VPN")
        val controller = RecordingController(consent = consent)
        val saved = profiles.save(null, ProxyProfileInput(server = "proxy.example.com", port = "1080"))
        val viewModel = HomeViewModel(profiles, controller)
        observe(viewModel)

        viewModel.connect()

        assertEquals(HomeDialog.VpnExplainer(consent), ready(viewModel).dialog)
        assertEquals("nothing starts before the user allows it", emptyList<String>(), controller.connects)

        viewModel.dismissDialog()
        viewModel.onConsentResult(granted = true)
        assertEquals(listOf(saved.id), controller.connects)
    }

    @Test
    fun aRefusedConsentShowsH1AndStartsNothing() = runTest {
        val controller = RecordingController(consent = Intent("android.net.vpn.SETUP_VPN"))
        profiles.save(null, ProxyProfileInput(server = "proxy.example.com", port = "1080"))
        val viewModel = HomeViewModel(profiles, controller)
        observe(viewModel)
        viewModel.connect()
        viewModel.dismissDialog()

        viewModel.onConsentResult(granted = false)

        assertEquals(HomeDialog.VpnPermissionDenied, ready(viewModel).dialog)
        assertEquals(emptyList<String>(), controller.connects)
        viewModel.dismissDialog()
        assertNull(ready(viewModel).dialog)
    }

    @Test
    fun whileAConnectionExistsHomeShowsItsProfileNotTheChosenOne() = runTest {
        val controller = RecordingController()
        val first = profiles.save(null, ProxyProfileInput(name = "Tokyo", server = "tokyo.example.com", port = "1080"))
        val second = profiles.save(null, ProxyProfileInput(name = "Office", server = "office.example.com", port = "3128"))
        val viewModel = HomeViewModel(profiles, controller)
        observe(viewModel)

        controller.current.value = ConnectionState(ConnectionStatus.Connected, profileId = first.id, profileName = "Tokyo", profile = first)
        profiles.setActive(second.id)
        assertEquals(first.id, ready(viewModel).profile.id)

        controller.current.value = ConnectionState(ConnectionStatus.Disconnected, profileId = first.id, profile = first)
        assertEquals(second.id, ready(viewModel).profile.id)
    }

    /** Edits apply at the next connect; until then Home must not describe settings the engine doesn't run. */
    @Test
    fun aConnectionIsDescribedWithTheSettingsItRunsAfterTheProfileIsEdited() = runTest {
        val controller = RecordingController()
        val saved = profiles.save(null, ProxyProfileInput(name = "Tokyo", server = "tokyo.example.com", port = "1080"))
        val viewModel = HomeViewModel(profiles, controller)
        observe(viewModel)
        controller.current.value = ConnectionState(ConnectionStatus.Connected, profileId = saved.id, profileName = "Tokyo", profile = saved)

        profiles.save(saved.id, ProxyProfileInput(name = "Tokyo", server = "tls.example.com", port = "443", useTls = true))

        val shown = ready(viewModel).profile
        assertEquals("tokyo.example.com", shown.host)
        assertFalse("the running connection has no TLS", shown.useTls)
    }

    @Test
    fun aBlockedConnectionStaysOnHomeWhenItsProfileIsDeleted() = runTest {
        val controller = RecordingController()
        val saved = profiles.save(null, ProxyProfileInput(name = "Tokyo", server = "tokyo.example.com", port = "1080"))
        val viewModel = HomeViewModel(profiles, controller)
        observe(viewModel)
        val deleted = ConnectionError(ConnectionError.PROFILE_DELETED)

        profiles.delete(saved.id)
        controller.current.value = ConnectionState(ConnectionStatus.Blocked, profileId = saved.id, profile = saved, error = deleted)

        val ready = ready(viewModel)
        assertEquals("Tokyo", ready.profile.displayName)
        assertEquals(ConnectionStatus.Blocked, ready.status)
        assertEquals(deleted, ready.error)
    }

    @Test
    fun anErrorIsShownOnlyWithTheProfileItBelongsTo() = runTest {
        val controller = RecordingController()
        val first = profiles.save(null, ProxyProfileInput(server = "tokyo.example.com", port = "1080"))
        val second = profiles.save(null, ProxyProfileInput(server = "office.example.com", port = "3128"))
        val viewModel = HomeViewModel(profiles, controller)
        observe(viewModel)
        val unreachable = ConnectionError("PROXY_UNREACHABLE")

        controller.current.value = ConnectionState(ConnectionStatus.Disconnected, profileId = first.id, error = unreachable)
        assertEquals(unreachable, ready(viewModel).error)

        profiles.setActive(second.id)
        assertNull(ready(viewModel).error)
    }

    @Test
    fun stoppingWhileTrafficIsHeldIsConfirmedFirst() = runTest {
        val controller = RecordingController()
        val saved = profiles.save(null, ProxyProfileInput(server = "proxy.example.com", port = "1080"))
        val viewModel = HomeViewModel(profiles, controller)
        observe(viewModel)
        controller.current.value = ConnectionState(
            ConnectionStatus.Blocked,
            profileId = saved.id,
            profile = saved,
            error = ConnectionError(ConnectionError.ENGINE_STOPPED),
        )

        viewModel.requestStop()
        assertEquals(HomeDialog.ConfirmStop, ready(viewModel).dialog)
        assertFalse(controller.calls.contains("disconnect"))

        viewModel.confirmStop()
        assertNull(ready(viewModel).dialog)
        assertTrue(controller.calls.contains("disconnect"))
    }

    @Test
    fun connectForgetsTheLastErrorFirst() = runTest {
        val controller = RecordingController()
        val saved = profiles.save(null, ProxyProfileInput(server = "proxy.example.com", port = "1080"))
        val viewModel = HomeViewModel(profiles, controller)
        observe(viewModel)

        viewModel.connect()

        assertEquals(listOf("clearError", "connect ${saved.id}"), controller.calls)
    }

    /** The diagnostics are shown while connected only, never for a connection that is gone (ADR-0011). */
    @Test
    fun diagnosticsShowWhileConnectedOnly() = runTest {
        val controller = RecordingController()
        val saved = profiles.save(null, ProxyProfileInput(server = "proxy.example.com", port = "1080"))
        val viewModel = HomeViewModel(profiles, controller)
        observe(viewModel)
        val reading = RoutingDiagnostics(connectionsOpened = 7)
        controller.readings.value = reading

        assertNull("not connected", ready(viewModel).diagnostics)
        controller.current.value = ConnectionState(ConnectionStatus.Connected, saved.id, profile = saved)
        assertEquals(reading, ready(viewModel).diagnostics)
        controller.current.value = ConnectionState(ConnectionStatus.Reconnecting, saved.id, profile = saved)
        assertNull("a switch shows no counters of the engine that stopped", ready(viewModel).diagnostics)
    }

    /**
     * The session's traffic shows while there is a connected session — on through reconnecting on its own
     * and waiting for a network — and never a reading of another session (ADR-0012).
     */
    @Test
    fun trafficShowsForTheSessionItBelongsTo() = runTest {
        val controller = RecordingController()
        val saved = profiles.save(null, ProxyProfileInput(server = "proxy.example.com", port = "1080"))
        val viewModel = HomeViewModel(profiles, controller)
        observe(viewModel)
        val since = 1_800_000_000_000L
        val reading = TrafficStats(bytesUp = 1_200, bytesDown = 34_000, activeConnections = 3, sinceMillis = since)
        controller.trafficReadings.value = reading

        assertNull("not connected", ready(viewModel).traffic)
        controller.current.value = ConnectionState(ConnectionStatus.Connected, saved.id, profile = saved, connectedSinceMillis = since)
        assertEquals(reading, ready(viewModel).traffic)
        val retry =
            ReconnectInfo(
                ReconnectReason.ProxyFailures,
                attempt = 2,
                maxAttempts = 8,
                nextAttemptAtMillis = since + 9_000,
                lastError = null,
            )
        controller.current.value = controller.current.value.copy(status = ConnectionStatus.Reconnecting, reconnect = retry)
        assertEquals("the session goes on while it reconnects", reading, ready(viewModel).traffic)
        controller.current.value = controller.current.value.copy(status = ConnectionStatus.Connected, connectedSinceMillis = since + 60_000)
        assertNull("a reading of another session is never shown", ready(viewModel).traffic)
        controller.current.value =
            ConnectionState(ConnectionStatus.Blocked, saved.id, profile = saved, error = ConnectionError("AUTH_FAILED"))
        assertNull(ready(viewModel).traffic)
    }

    /** M8: connecting the profile Home shows makes it the user's choice (Always-on VPN connects only that one). */
    @Test
    fun connectingMakesTheShownProfileTheChosenOneAndTheInterruptionSeen() = runTest {
        val controller = RecordingController()
        val protection = InMemoryProtectionStore(interruption = Interruption("p0", 0, null, ExitReason.Unknown, 0))
        val saved = profiles.save(null, ProxyProfileInput(server = "proxy.example.com", port = "1080"))
        val viewModel = HomeViewModel(profiles, controller, protection)
        observe(viewModel)
        assertNull("shown as the only profile, but never chosen", profiles.chosenProfileId.first())

        viewModel.connect()

        assertEquals(listOf(saved.id), controller.connects)
        assertEquals(saved.id, profiles.chosenProfileId.first())
        assertTrue("connecting again is seeing it, cleared through the session", "dismissInterruption" in controller.calls)
    }

    @Test
    fun androidsKillSwitchAndAnInterruptionReachHomeUntilDismissed() = runTest {
        val killSwitch = SystemKillSwitch(alwaysOn = true, blocksWithoutVpn = true, checkedAtMillis = 5)
        val interruption = Interruption("p1", connectedSinceMillis = 1, endedAtMillis = 2, reason = ExitReason.Crash, detectedAtMillis = 3)
        val protection = InMemoryProtectionStore(killSwitch = killSwitch, interruption = interruption)
        profiles.save(null, ProxyProfileInput(server = "proxy.example.com", port = "1080"))
        val controller = RecordingController(protection = protection)
        val viewModel = HomeViewModel(profiles, controller, protection)
        observe(viewModel)
        assertEquals(killSwitch, ready(viewModel).killSwitch)
        assertEquals(interruption, ready(viewModel).interruption)

        viewModel.dismissInterruption()
        assertNull(ready(viewModel).interruption)
        assertNull(protection.interruption.first())
        assertEquals("what Android said stays", killSwitch, ready(viewModel).killSwitch)

        viewModel.checkKillSwitch()
        assertEquals(listOf("dismissInterruption", "checkSystemKillSwitch"), controller.calls)
    }

    /** Android started ProxyFlow as Always-on VPN with no proxy chosen: no profile's error, shown whichever profile Home shows. */
    @Test
    fun alwaysOnWithoutAChosenProxyIsShownOnAnyProfile() = runTest {
        val controller = RecordingController()
        profiles.save(null, ProxyProfileInput(server = "proxy.example.com", port = "1080"))
        val viewModel = HomeViewModel(profiles, controller)
        observe(viewModel)
        controller.current.value = ConnectionState(error = ConnectionError(ConnectionError.ALWAYS_ON_NO_PROFILE))
        assertEquals(ConnectionError.ALWAYS_ON_NO_PROFILE, ready(viewModel).error?.code)

        controller.current.value = ConnectionState(profileId = "another", error = ConnectionError("AUTH_FAILED"))
        assertNull("another profile's error is not this one's", ready(viewModel).error)
    }

    @Test
    fun retryNowIsForwarded() = runTest {
        val controller = RecordingController()
        val viewModel = HomeViewModel(profiles, controller)
        viewModel.retryNow()
        assertEquals(listOf("retryNow"), controller.calls)
    }

    @Test
    fun theNotificationPermissionIsRequestedOncePerRun() {
        val viewModel = HomeViewModel(profiles, RecordingController())
        assertTrue(viewModel.takeNotificationRequest())
        assertFalse(viewModel.takeNotificationRequest())
    }

    private fun TestScope.observe(viewModel: HomeViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }
    }

    private fun ready(viewModel: HomeViewModel) = viewModel.uiState.value as HomeUiState.Ready

    /** [protection]: the store the session would clear an interruption in. */
    private class RecordingController(
        override val canConnect: Boolean = true,
        private val consent: Intent? = null,
        private val protection: ProtectionStore? = null,
    ) : ConnectionController {
        val current = MutableStateFlow(ConnectionState.Idle)
        override val state: StateFlow<ConnectionState> = current
        val calls = mutableListOf<String>()
        val connects: List<String> get() = calls.filter { it.startsWith("connect ") }.map { it.removePrefix("connect ") }

        override fun consentIntent(): Intent? = consent

        override fun connect(profileId: String) {
            calls += "connect $profileId"
        }

        override fun disconnect() {
            calls += "disconnect"
        }

        override fun clearError() {
            calls += "clearError"
        }

        val readings = MutableStateFlow<RoutingDiagnostics?>(null)
        override val diagnostics: Flow<RoutingDiagnostics?> = readings

        val trafficReadings = MutableStateFlow<TrafficStats?>(null)
        override val traffic: Flow<TrafficStats?> = trafficReadings

        override fun retryNow() {
            calls += "retryNow"
        }

        override fun checkSystemKillSwitch() {
            calls += "checkSystemKillSwitch"
        }

        override fun dismissInterruption() {
            calls += "dismissInterruption"
            runBlocking { protection?.setInterruption(null) }
        }
    }
}
