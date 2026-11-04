package app.proxyflow.feature.profiles

import app.proxyflow.core.data.ConnectionController
import app.proxyflow.core.data.ConnectionTester
import app.proxyflow.core.data.CredentialsRead
import app.proxyflow.core.data.ProfileRepository
import app.proxyflow.core.model.ConnectionError
import app.proxyflow.core.model.ConnectionState
import app.proxyflow.core.model.ConnectionStatus
import app.proxyflow.core.model.ConnectionTestResult
import app.proxyflow.core.model.ProfileTest
import app.proxyflow.core.model.ProxyProfile
import app.proxyflow.core.model.ProxyProfileInput
import app.proxyflow.core.testing.FakeConnectionTester
import app.proxyflow.core.testing.MainDispatcherRule
import app.proxyflow.core.testing.TestProfiles
import app.proxyflow.core.testing.UnavailableConnectionController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ProfilesViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val repository = TestProfiles().repository
    private val tester = FakeConnectionTester()

    private fun viewModel(repo: ProfileRepository = repository, connection: ConnectionController = UnavailableConnectionController()) =
        ProfilesViewModel(repo, connection, tester)

    @Test
    fun listsProfilesInSaveOrderAndMarksTheActiveOne() = runTest {
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        assertFalse(vm.uiState.value.loading)
        assertTrue(vm.uiState.value.profiles.isEmpty())

        repository.save(null, ProxyProfileInput(server = "a.example.com", port = "1080"))
        repository.save(null, ProxyProfileInput(server = "b.example.com", port = "1080"))

        assertEquals(listOf("a.example.com", "b.example.com"), vm.uiState.value.profiles.map { it.host })
        assertEquals("p1", vm.uiState.value.activeProfileId)
    }

    @Test
    fun useAndDeleteChangeTheList() = runTest {
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        repository.save(null, ProxyProfileInput(server = "a.example.com", port = "1080"))
        repository.save(null, ProxyProfileInput(server = "b.example.com", port = "1080"))

        vm.setActive("p2")
        assertEquals("p2", vm.uiState.value.activeProfileId)

        vm.delete("p2")
        assertEquals(listOf("a.example.com"), vm.uiState.value.profiles.map { it.host })
        assertEquals("deleting the active profile activates the next one", "p1", vm.uiState.value.activeProfileId)
    }

    @Test
    fun deletingTheProfileAConnectionUsesDisconnectsFirst() = runTest {
        val connection = RecordingConnection()
        val vm = viewModel(connection = connection)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        repository.save(null, ProxyProfileInput(server = "a.example.com", port = "1080"))
        repository.save(null, ProxyProfileInput(server = "b.example.com", port = "1080"))
        connection.current.value = ConnectionState(ConnectionStatus.Blocked, profileId = "p1")
        assertEquals("p1", vm.uiState.value.connectedProfileId)

        vm.delete("p2")
        assertEquals("another profile: the connection is left alone", emptyList<String>(), connection.calls)

        vm.delete("p1")
        assertEquals(listOf("disconnect"), connection.calls)
        assertTrue(vm.uiState.value.profiles.isEmpty())
    }

    @Test
    fun duplicateAddsANamedCopyWithTheSameSignIn() = runTest {
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        val office =
            ProxyProfileInput(
                name = "Office",
                server = "a.example.com",
                port = "1080",
                requiresSignIn = true,
                username = "u",
                password = "pw",
            )
        repository.save(null, office)

        vm.duplicate("p1")
        vm.duplicate("p1")
        // The screen passes the copy-name pattern of the phone's language.
        vm.duplicate("p1") { base, number -> "Kopie $number von $base" }
        assertEquals(listOf("Office", "Office (copy)", "Office (copy 2)", "Kopie 1 von Office"), vm.uiState.value.profiles.map { it.name })
        assertEquals("pw", String((repository.readCredentials("p2") as CredentialsRead.Available).password))
        assertEquals("the original stays active", "p1", vm.uiState.value.activeProfileId)
    }

    @Test
    fun aDuplicateThatFailsIsReportedOnceAndChangesNothing() = runTest {
        // For example a Keystore error while sealing the copy's credentials.
        val broken = object : ProfileRepository by repository {
            override suspend fun duplicate(id: String, name: String): ProxyProfile? = throw IllegalStateException("Keystore unavailable")
        }
        val vm = viewModel(broken)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        repository.save(null, ProxyProfileInput(name = "Office", server = "a.example.com", port = "1080"))

        vm.duplicate("p1")
        assertEquals(ProfileAction.Duplicate, vm.uiState.value.failed)
        assertEquals(listOf("Office"), vm.uiState.value.profiles.map { it.name })

        vm.onFailureShown()
        assertNull(vm.uiState.value.failed)
    }

    /**
     * "Use this proxy" asks the session to switch a connection running on another profile — the session decides, in order
     * with what it was asked before (VpnSessionTest) — then keeps the choice.
     */
    @Test
    fun useThisProxyAsksTheSessionToSwitchThenKeepsTheChoice() = runTest {
        val connection = RecordingConnection()
        val vm = viewModel(connection = connection)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        repository.save(null, ProxyProfileInput(server = "a.example.com", port = "1080"))
        repository.save(null, ProxyProfileInput(server = "b.example.com", port = "1080"))

        vm.setActive("p2")
        assertEquals(listOf("switchIfConnected p2"), connection.calls)
        assertEquals("p2", vm.uiState.value.activeProfileId)
        assertEquals("nothing connects or disconnects here", listOf("switchIfConnected p2"), connection.calls)
    }

    @Test
    fun testFromTheListShowsItRunningThenTheKeptResult() = runTest {
        val gate = CompletableDeferred<Unit>()
        val keeping = object : ConnectionTester by tester {
            override suspend fun testSaved(profileId: String): ConnectionTestResult? {
                gate.await()
                val tested = repository.get(profileId) ?: return null
                repository.recordTest(profileId, tested, ProfileTest(5_000, code = "AUTH_FAILED", latencyMillis = null))
                return ConnectionTestResult.Failed(ConnectionError("AUTH_FAILED"))
            }
        }
        val vm = ProfilesViewModel(repository, UnavailableConnectionController(), keeping)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        repository.save(null, ProxyProfileInput(server = "a.example.com", port = "1080"))

        vm.test("p1")
        vm.test("p1") // not twice at once
        assertEquals(setOf("p1"), vm.uiState.value.testing)
        gate.complete(Unit)
        assertEquals(emptySet<String>(), vm.uiState.value.testing)
        assertEquals("AUTH_FAILED", vm.uiState.value.lastTests["p1"]?.code)
        assertNull("the proxy's answer is no failure of the app's", vm.uiState.value.failed)
    }

    @Test
    fun aTestThatCouldntRunOnThisPhoneIsReported() = runTest {
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        repository.save(null, ProxyProfileInput(server = "a.example.com", port = "1080"))

        tester.result = ConnectionTestResult.Failed(ConnectionError(ConnectionError.INTERNAL, "the packet engine could not be loaded"))
        vm.test("p1")
        assertEquals(ProfileAction.Test, vm.uiState.value.failed)
        assertEquals(listOf("p1"), tester.savedRequests)
        assertTrue(vm.uiState.value.testing.isEmpty())
    }

    private class RecordingConnection : ConnectionController {
        val current = MutableStateFlow(ConnectionState.Idle)
        val calls = mutableListOf<String>()
        override val state: StateFlow<ConnectionState> = current
        override val canConnect = true

        override fun connect(profileId: String) {
            calls += "connect $profileId"
        }

        override fun disconnect() {
            calls += "disconnect"
            current.value = current.value.copy(status = ConnectionStatus.Disconnected)
        }

        override fun switchIfConnected(profileId: String) {
            calls += "switchIfConnected $profileId"
        }
    }
}
