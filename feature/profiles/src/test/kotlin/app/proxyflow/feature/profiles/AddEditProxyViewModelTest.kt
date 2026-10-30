package app.proxyflow.feature.profiles

import androidx.lifecycle.SavedStateHandle
import app.proxyflow.core.data.InMemoryProfileRepository
import app.proxyflow.core.model.ProfileError
import app.proxyflow.core.model.ProfileField
import app.proxyflow.core.model.ProxyProfileInput
import app.proxyflow.core.model.ProxyProtocol
import app.proxyflow.core.testing.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AddEditProxyViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private var nextId = 0
    private val repository = InMemoryProfileRepository(newId = { "p${++nextId}" })

    private fun viewModel(handle: SavedStateHandle = SavedStateHandle()) = AddEditProxyViewModel(handle, repository)

    @Test
    fun argumentKeyMatchesTheNavigationDestination() {
        assertEquals(AddEditProxyDestination::profileId.name, AddEditProxyViewModel.ARG_PROFILE_ID)
    }

    @Test
    fun errorsAppearOnlyAfterTheFirstSaveAndClearWhileFixing() {
        val vm = viewModel()
        vm.onServerChange("not a host")
        assertTrue("no errors while the user is still typing", vm.uiState.value.errors.isEmpty())

        vm.save()
        assertEquals(ProfileError.ServerInvalid, vm.uiState.value.errors[ProfileField.Server])
        assertEquals(ProfileError.PortOutOfRange, vm.uiState.value.errors[ProfileField.Port])
        assertFalse(vm.uiState.value.saved)
        assertTrue(repository.profiles.value.isEmpty())

        vm.onServerChange("proxy.example.com")
        assertFalse(ProfileField.Server in vm.uiState.value.errors)
        assertTrue(ProfileField.Port in vm.uiState.value.errors)
    }

    @Test
    fun validFormIsSavedOnce() {
        val vm = viewModel()
        vm.onServerChange("proxy.example.com")
        vm.onPortChange("1080")
        vm.save()
        vm.save()

        assertTrue(vm.uiState.value.saved)
        assertFalse(vm.uiState.value.dirty)
        assertEquals(listOf("proxy.example.com:1080"), repository.profiles.value.map { it.endpoint })
    }

    @Test
    fun portKeepsDigitsOnlyAndAtMostFive() {
        val vm = viewModel()
        vm.onPortChange("80a8-0")
        assertEquals("8080", vm.uiState.value.input.port)
        vm.onPortChange("1234567")
        assertEquals("12345", vm.uiState.value.input.port)
    }

    @Test
    fun nameIsCappedAt40Characters() {
        val vm = viewModel()
        vm.onNameChange("x".repeat(50))
        assertEquals(40, vm.uiState.value.input.name.length)
    }

    @Test
    fun dirtyTracksChangesFromTheStartingValues() {
        val vm = viewModel()
        assertFalse(vm.uiState.value.dirty)
        vm.onServerChange("p")
        assertTrue(vm.uiState.value.dirty)
        vm.onServerChange("")
        assertFalse(vm.uiState.value.dirty)
    }

    @Test
    fun editingLoadsTheProfileButNeverItsPassword() = runTest {
        val saved = repository.save(
            null,
            ProxyProfileInput(server = "proxy.example.com", port = "1080", requiresSignIn = true, username = "user", password = "secret"),
        )
        val vm = viewModel(SavedStateHandle(mapOf(AddEditProxyViewModel.ARG_PROFILE_ID to saved.id)))

        val state = vm.uiState.value
        assertTrue(state.isEdit)
        assertEquals("proxy.example.com", state.input.server)
        assertEquals("user", state.input.username)
        assertEquals("", state.input.password)
        assertTrue(state.hasSavedPassword)
        assertEquals("proxy.example.com", state.originalName)
        assertFalse(state.dirty)
    }

    @Test
    fun savingAnEditKeepsTheIdAndTheStoredPasswordWhenLeftEmpty() = runTest {
        val saved = repository.save(
            null,
            ProxyProfileInput(server = "proxy.example.com", port = "1080", requiresSignIn = true, username = "user", password = "secret"),
        )
        val vm = viewModel(SavedStateHandle(mapOf(AddEditProxyViewModel.ARG_PROFILE_ID to saved.id)))
        vm.onPortChange("1081")
        vm.save()

        val profiles = repository.profiles.value
        assertEquals(1, profiles.size)
        assertEquals(saved.id, profiles.single().id)
        assertEquals(1081, profiles.single().port)
        assertTrue(profiles.single().hasPassword)
    }

    @Test
    fun passwordIsNeverWrittenToSavedState() {
        val handle = SavedStateHandle()
        val vm = viewModel(handle)
        vm.onRequiresSignInChange(true)
        vm.onUsernameChange("user")
        vm.onPasswordChange("pw-must-not-persist")

        val saved = handle.keys().map { handle.get<Any?>(it) }
        assertFalse(saved.any { it.toString().contains("pw-must-not-persist") })
        assertTrue(handle.keys().none { it.contains("password", ignoreCase = true) })
    }

    @Test
    fun formIsRestoredAfterProcessDeathExceptThePassword() {
        val before = SavedStateHandle()
        viewModel(before).apply {
            onProtocolChange(ProxyProtocol.HTTP)
            onServerChange("proxy.example.com")
            onPortChange("3128")
            onRequiresSignInChange(true)
            onUsernameChange("user")
            onPasswordChange("secret")
            onUseTlsChange(true)
            onTlsServerNameChange("tls.example.com")
        }
        // Android restores only what was in saved state into a new process.
        val after = SavedStateHandle(before.keys().associateWith { before.get<Any?>(it) })
        val restored = viewModel(after).uiState.value.input

        assertEquals(ProxyProtocol.HTTP, restored.protocol)
        assertEquals("proxy.example.com", restored.server)
        assertEquals("3128", restored.port)
        assertTrue(restored.requiresSignIn)
        assertEquals("user", restored.username)
        assertEquals("", restored.password)
        assertTrue(restored.useTls)
        assertEquals("tls.example.com", restored.tlsServerName)
    }

    @Test
    fun errorsShownBeforeProcessDeathAreShownAgain() {
        val before = SavedStateHandle()
        viewModel(before).apply {
            onServerChange("bad host")
            save()
        }
        val after = SavedStateHandle(before.keys().associateWith { before.get<Any?>(it) })
        assertEquals(ProfileError.ServerInvalid, viewModel(after).uiState.value.errors[ProfileField.Server])
    }

    @Test
    fun missingProfileLeavesAnEmptyEditForm() {
        val vm = viewModel(SavedStateHandle(mapOf(AddEditProxyViewModel.ARG_PROFILE_ID to "gone")))
        assertTrue(vm.uiState.value.isEdit)
        assertNull(vm.uiState.value.originalName)
        assertEquals("", vm.uiState.value.input.server)
    }
}
