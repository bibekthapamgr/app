package app.proxyflow.feature.profiles

import app.proxyflow.core.data.ConnectionController
import app.proxyflow.core.data.CredentialsRead
import app.proxyflow.core.data.ProfileRepository
import app.proxyflow.core.model.ConnectionState
import app.proxyflow.core.model.ConnectionStatus
import app.proxyflow.core.model.ProxyProfile
import app.proxyflow.core.model.ProxyProfileInput
import app.proxyflow.core.testing.MainDispatcherRule
import app.proxyflow.core.testing.TestProfiles
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

    @Test
    fun listsProfilesInSaveOrderAndMarksTheActiveOne() = runTest {
        val vm = ProfilesViewModel(repository)
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
        val vm = ProfilesViewModel(repository)
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
        val vm = ProfilesViewModel(repository, connection)
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
        val vm = ProfilesViewModel(repository)
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
        val vm = ProfilesViewModel(broken)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        repository.save(null, ProxyProfileInput(name = "Office", server = "a.example.com", port = "1080"))

        vm.duplicate("p1")
        assertEquals(ProfileAction.Duplicate, vm.uiState.value.failed)
        assertEquals(listOf("Office"), vm.uiState.value.profiles.map { it.name })

        vm.onFailureShown()
        assertNull(vm.uiState.value.failed)
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
    }
}
