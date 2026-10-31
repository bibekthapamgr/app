package app.proxyflow.feature.home

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.proxyflow.core.data.ConnectionController
import app.proxyflow.core.data.UnavailableConnectionController
import app.proxyflow.core.model.ConnectionError
import app.proxyflow.core.model.ConnectionState
import app.proxyflow.core.model.ConnectionStatus
import app.proxyflow.core.model.ProxyProfileInput
import app.proxyflow.core.testing.MainDispatcherRule
import app.proxyflow.core.testing.TestProfiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
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

        controller.current.value = ConnectionState(ConnectionStatus.Connected, profileId = first.id, profileName = "Tokyo")
        profiles.setActive(second.id)
        assertEquals(first.id, ready(viewModel).profile.id)

        controller.current.value = ConnectionState(ConnectionStatus.Disconnected, profileId = first.id)
        assertEquals(second.id, ready(viewModel).profile.id)
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
        controller.current.value =
            ConnectionState(ConnectionStatus.Blocked, profileId = saved.id, error = ConnectionError(ConnectionError.ENGINE_STOPPED))

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

    private class RecordingController(override val canConnect: Boolean = true, private val consent: Intent? = null) :
        ConnectionController {
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
    }
}
