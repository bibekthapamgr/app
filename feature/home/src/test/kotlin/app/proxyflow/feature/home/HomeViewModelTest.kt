package app.proxyflow.feature.home

import app.proxyflow.core.data.ConnectionController
import app.proxyflow.core.data.InMemoryProfileRepository
import app.proxyflow.core.data.UnavailableConnectionController
import app.proxyflow.core.model.ConnectionStatus
import app.proxyflow.core.model.ProxyProfileInput
import app.proxyflow.core.testing.MainDispatcherRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class HomeViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private var nextId = 0
    private val profiles = InMemoryProfileRepository(newId = { "p${++nextId}" })

    @Test
    fun showsTheEmptyStateUntilAProfileIsSaved() = runTest {
        val viewModel = HomeViewModel(profiles, UnavailableConnectionController())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }
        assertEquals(HomeUiState.NoProfile, viewModel.uiState.value)

        profiles.save(null, ProxyProfileInput(server = "proxy.example.com", port = "1080"))

        val ready = viewModel.uiState.value as HomeUiState.Ready
        assertEquals("proxy.example.com", ready.profile.host)
        assertEquals(ConnectionStatus.Disconnected, ready.status)
        assertFalse("M2 cannot connect, and Home must say so", ready.canConnect)
    }

    @Test
    fun showsTheActiveProfile() = runTest {
        val viewModel = HomeViewModel(profiles, UnavailableConnectionController())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }
        profiles.save(null, ProxyProfileInput(server = "first.example.com", port = "1080"))
        val second = profiles.save(null, ProxyProfileInput(server = "second.example.com", port = "1080"))

        assertEquals("first.example.com", (viewModel.uiState.value as HomeUiState.Ready).profile.host)
        profiles.setActive(second.id)
        assertEquals("second.example.com", (viewModel.uiState.value as HomeUiState.Ready).profile.host)
    }

    @Test
    fun connectIsOnlyForwardedWhenConnectingIsAvailable() = runTest {
        val unavailable = RecordingController(canConnect = false)
        val available = RecordingController(canConnect = true)
        val saved = profiles.save(null, ProxyProfileInput(server = "proxy.example.com", port = "1080"))

        for (controller in listOf(unavailable, available)) {
            val viewModel = HomeViewModel(profiles, controller)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }
            viewModel.connect()
        }

        assertEquals(emptyList<String>(), unavailable.connects)
        assertEquals(listOf(saved.id), available.connects)
    }

    private class RecordingController(override val canConnect: Boolean) : ConnectionController {
        override val status: StateFlow<ConnectionStatus> = MutableStateFlow(ConnectionStatus.Disconnected)
        val connects = mutableListOf<String>()

        override fun connect(profileId: String) {
            connects += profileId
        }

        override fun disconnect() = Unit
    }
}
