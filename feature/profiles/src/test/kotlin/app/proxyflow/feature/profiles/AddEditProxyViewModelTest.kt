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

    /** Saved state for the form opened on profile [id]. */
    private fun editing(id: String) = SavedStateHandle(mapOf(AddEditProxyViewModel.ARG_PROFILE_ID to id))

    /** What Android hands a new process: only the values that were in saved state. */
    private fun afterProcessDeath(before: SavedStateHandle) = SavedStateHandle(before.keys().associateWith { before.get<Any?>(it) })

    private val signIn =
        ProxyProfileInput(server = "proxy.example.com", port = "1080", requiresSignIn = true, username = "user", password = "secret")

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
    fun everyRejectedSaveIsCountedSoTheFormCanScrollToTheErrors() {
        val vm = viewModel()
        assertEquals(0, vm.uiState.value.rejectedSaves)
        vm.save()
        vm.save()
        assertEquals(2, vm.uiState.value.rejectedSaves)

        vm.onServerChange("proxy.example.com")
        vm.onPortChange("1080")
        vm.save()
        assertEquals("a successful save is not a rejected one", 2, vm.uiState.value.rejectedSaves)
        assertTrue(vm.uiState.value.saved)
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
        val saved = repository.save(null, signIn)
        val vm = viewModel(editing(saved.id))

        val state = vm.uiState.value
        assertTrue(state.isEdit)
        assertEquals("proxy.example.com", state.input.server)
        assertEquals("user", state.input.username)
        assertEquals("", state.input.password)
        assertTrue(state.hasSavedPassword)
        assertTrue("left empty, the saved password is kept", state.keepsSavedPassword)
        assertEquals("proxy.example.com", state.originalName)
        assertFalse(state.dirty)
    }

    @Test
    fun savingAnEditKeepsTheIdAndTheStoredPasswordWhenLeftEmpty() = runTest {
        val saved = repository.save(null, signIn)
        val vm = viewModel(editing(saved.id))
        vm.onNameChange("Office")
        vm.save()

        val profile = repository.profiles.value.single()
        assertEquals(saved.id, profile.id)
        assertEquals("Office", profile.name)
        assertTrue(profile.hasPassword)
    }

    @Test
    fun sendingTheSavedPasswordSomewhereElseNeedsItEnteredAgain() = runTest {
        val saved = repository.save(null, signIn)
        val vm = viewModel(editing(saved.id))

        vm.onPortChange("1081")
        assertFalse(vm.uiState.value.keepsSavedPassword)
        vm.save()
        assertEquals(ProfileError.PasswordRequired, vm.uiState.value.errors[ProfileField.Password])
        assertFalse(vm.uiState.value.saved)
        assertEquals("the saved profile is unchanged", 1080, repository.get(saved.id)?.port)

        vm.onPortChange("1080")
        assertTrue("back to the proxy it was saved for", vm.uiState.value.keepsSavedPassword)
        assertTrue(vm.uiState.value.errors.isEmpty())

        vm.onPortChange("1081")
        vm.onPasswordChange("new-secret")
        vm.save()
        assertTrue(vm.uiState.value.saved)
        assertEquals(1081, repository.get(saved.id)?.port)
        assertEquals(true, repository.get(saved.id)?.hasPassword)
    }

    @Test
    fun aNewProfileThatNeedsSignInNeedsAPassword() {
        val vm = viewModel()
        vm.onServerChange("proxy.example.com")
        vm.onPortChange("1080")
        vm.onRequiresSignInChange(true)
        vm.onUsernameChange("user")
        vm.save()

        assertEquals(mapOf(ProfileField.Password to ProfileError.PasswordRequired), vm.uiState.value.errors)
        assertFalse(vm.uiState.value.saved)
        assertTrue(repository.profiles.value.isEmpty())
    }

    @Test
    fun credentialsAreNeverWrittenToSavedState() {
        val handle = SavedStateHandle()
        val vm = viewModel(handle)
        vm.onRequiresSignInChange(true)
        vm.onUsernameChange("user-must-not-persist")
        vm.onPasswordChange("pw-must-not-persist")
        vm.save()

        val values = handle.keys().map { handle.get<Any?>(it).toString() }
        assertFalse(values.toString(), values.any { "must-not-persist" in it })
        assertTrue(handle.keys().none { it.contains("password", ignoreCase = true) || it.contains("username", ignoreCase = true) })
    }

    @Test
    fun formIsRestoredAfterProcessDeathExceptTheCredentials() {
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
        val restored = viewModel(afterProcessDeath(before)).uiState.value.input

        assertEquals(ProxyProtocol.HTTP, restored.protocol)
        assertEquals("proxy.example.com", restored.server)
        assertEquals("3128", restored.port)
        assertTrue(restored.requiresSignIn)
        assertEquals("", restored.username)
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
        assertEquals(ProfileError.ServerInvalid, viewModel(afterProcessDeath(before)).uiState.value.errors[ProfileField.Server])
    }

    @Test
    fun anEditRestoredAfterProcessDeathTakesTheUsernameFromTheProfile() = runTest {
        // Profiles that outlive the process (stored on the phone from M3).
        val saved = repository.save(null, signIn)
        val before = editing(saved.id)
        viewModel(before).apply {
            onNameChange("Office")
            onUsernameChange("typed-before-process-death")
        }
        val restored = viewModel(afterProcessDeath(before)).uiState.value

        assertTrue(restored.isEdit)
        assertEquals("Office", restored.input.name)
        assertEquals("user", restored.input.username)
        assertEquals("", restored.input.password)
        assertTrue(restored.keepsSavedPassword)
        assertTrue(restored.dirty)
    }

    @Test
    fun aProfileLostWithTheProcessIsOfferedAsANewOne() = runTest {
        val saved = repository.save(null, signIn)
        val before = editing(saved.id)
        viewModel(before).onServerChange("other.example.com")

        // M2 keeps profiles in memory, so the new process starts without them.
        val newProcess = InMemoryProfileRepository(newId = { "new" })
        val vm = AddEditProxyViewModel(afterProcessDeath(before), newProcess)
        val state = vm.uiState.value
        assertFalse(state.isEdit)
        assertNull(state.originalName)
        assertFalse(state.hasSavedPassword)
        assertEquals("other.example.com", state.input.server)
        assertTrue("Back asks before the restored changes are thrown away", state.dirty)

        vm.onRequiresSignInChange(false)
        vm.save()
        assertEquals("saved as a new profile", listOf("new"), newProcess.profiles.value.map { it.id })
    }

    @Test
    fun missingProfileLeavesAnEmptyAddForm() {
        val vm = viewModel(editing("gone"))
        assertFalse(vm.uiState.value.isEdit)
        assertNull(vm.uiState.value.originalName)
        assertEquals("", vm.uiState.value.input.server)
        assertFalse(vm.uiState.value.dirty)

        vm.onServerChange("proxy.example.com")
        vm.onPortChange("1080")
        vm.save()
        assertEquals("a new id, not the missing one", listOf("p1"), repository.profiles.value.map { it.id })
    }
}
