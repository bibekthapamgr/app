package app.proxyflow.feature.profiles

import androidx.lifecycle.SavedStateHandle
import app.proxyflow.core.data.CredentialsRead
import app.proxyflow.core.data.CredentialsUnavailableException
import app.proxyflow.core.data.ProfileForEditing
import app.proxyflow.core.data.ProfileRepository
import app.proxyflow.core.data.crypto.AesGcmCredentialCipher
import app.proxyflow.core.model.ConnectionTestResult
import app.proxyflow.core.model.ProfileError
import app.proxyflow.core.model.ProfileField
import app.proxyflow.core.model.ProxyProfile
import app.proxyflow.core.model.ProxyProfileInput
import app.proxyflow.core.model.ProxyProtocol
import app.proxyflow.core.testing.FakeConnectionTester
import app.proxyflow.core.testing.MainDispatcherRule
import app.proxyflow.core.testing.TestProfiles
import kotlinx.coroutines.CompletableDeferred
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

    private val profiles = TestProfiles()
    private val repository = profiles.repository
    private val tester = FakeConnectionTester()

    private fun viewModel(handle: SavedStateHandle = SavedStateHandle(), repo: ProfileRepository = repository) =
        AddEditProxyViewModel(handle, repo, tester)

    private suspend fun savedPassword(id: String): String? = (repository.readCredentials(id) as? CredentialsRead.Available)?.let {
        String(it.password)
    }

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
        assertTrue(profiles.store.records.value.isEmpty())

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
        assertEquals(listOf("proxy.example.com:1080"), profiles.store.records.value.map { it.toProfile().endpoint })
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

        val profile = repository.get(saved.id)!!
        assertEquals("Office", profile.name)
        assertEquals("the saved password is kept", "secret", savedPassword(saved.id))
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
        assertEquals("new-secret", savedPassword(saved.id))
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
        assertTrue(profiles.store.records.value.isEmpty())
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
        // Profiles outlive the process: they are stored on the phone (M3).
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
    fun aProfileGoneAfterProcessDeathIsOfferedAsANewOne() = runTest {
        val saved = repository.save(null, signIn)
        val before = editing(saved.id)
        viewModel(before).onServerChange("other.example.com")

        // Deleted meanwhile (or the app's data was cleared): the new process doesn't have it.
        val newProcess = TestProfiles()
        val vm = viewModel(afterProcessDeath(before), newProcess.repository)
        val state = vm.uiState.value
        assertFalse(state.isEdit)
        assertNull(state.originalName)
        assertFalse(state.hasSavedPassword)
        assertEquals("other.example.com", state.input.server)
        assertTrue("Back asks before the restored changes are thrown away", state.dirty)

        vm.onRequiresSignInChange(false)
        vm.save()
        assertEquals("saved as a new profile", listOf("p1"), newProcess.store.records.value.map { it.id })
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
        assertEquals("a new id, not the missing one", listOf("p1"), profiles.store.records.value.map { it.id })
    }

    @Test
    fun credentialsThatCantBeReadAreAskedForAgain() = runTest {
        val saved = repository.save(null, signIn)
        profiles.keys.delete(AesGcmCredentialCipher.aliasFor(profiles.store.get(saved.id)!!.credentials!!.keyId))

        val vm = viewModel(editing(saved.id))
        val state = vm.uiState.value
        assertTrue(state.credentialsUnreadable)
        assertFalse(state.hasSavedPassword)
        assertEquals("", state.input.username)
        assertTrue("sign-in stays on", state.input.requiresSignIn)

        vm.save()
        assertEquals(ProfileError.UsernameRequired, vm.uiState.value.errors[ProfileField.Username])
        vm.onUsernameChange("user")
        vm.onPasswordChange("typed-again")
        vm.save()
        assertTrue(vm.uiState.value.saved)
        assertEquals("typed-again", savedPassword(saved.id))
    }

    @Test
    fun aFailedSaveSaysSoAndCanBeRetried() = runTest {
        var fail = true
        val flaky = object : ProfileRepository by repository {
            override suspend fun save(id: String?, input: ProxyProfileInput): ProxyProfile {
                if (fail) error("disk full")
                return repository.save(id, input)
            }
        }
        val vm = viewModel(repo = flaky)
        vm.onServerChange("proxy.example.com")
        vm.onPortChange("1080")
        vm.save()
        assertTrue(vm.uiState.value.saveFailed)
        assertFalse(vm.uiState.value.saved)
        assertFalse(vm.uiState.value.saving)

        fail = false
        vm.save()
        assertFalse(vm.uiState.value.saveFailed)
        assertTrue(vm.uiState.value.saved)
    }

    @Test
    fun testConnectionShowsOnlyWhatTheTesterFound() = runTest {
        val vm = viewModel()
        vm.onServerChange("proxy.example.com")
        vm.onPortChange("1080")

        vm.testConnection()
        assertEquals(ConnectionTestState.Done(ConnectionTestResult.Unavailable), vm.uiState.value.test)
        assertEquals(listOf<String?>(null), tester.requests.map { it.first })

        tester.result = ConnectionTestResult.Failed("AUTH_FAILED")
        vm.testConnection()
        assertEquals(ConnectionTestState.Done(ConnectionTestResult.Failed("AUTH_FAILED")), vm.uiState.value.test)

        tester.result = ConnectionTestResult.Passed(latencyMillis = 42)
        vm.testConnection()
        assertEquals(ConnectionTestState.Done(ConnectionTestResult.Passed(42)), vm.uiState.value.test)

        vm.onPortChange("1081")
        assertEquals("a result belongs to the form it tested", ConnectionTestState.Idle, vm.uiState.value.test)
    }

    @Test
    fun anInvalidFormIsNotTested() = runTest {
        val vm = viewModel()
        vm.onServerChange("bad host")
        vm.testConnection()
        assertTrue(tester.requests.isEmpty())
        assertEquals(ConnectionTestState.Idle, vm.uiState.value.test)
        assertEquals(ProfileError.ServerInvalid, vm.uiState.value.errors[ProfileField.Server])
        assertEquals(1, vm.uiState.value.rejectedSaves)
    }

    @Test
    fun aTestInProgressIsDroppedWhenTheFormChanges() = runTest {
        val gate = CompletableDeferred<ConnectionTestResult>()
        val slow = object : app.proxyflow.core.data.ConnectionTester {
            override suspend fun test(profileId: String?, input: ProxyProfileInput) = gate.await()
        }
        val vm = AddEditProxyViewModel(SavedStateHandle(), repository, slow)
        vm.onServerChange("proxy.example.com")
        vm.onPortChange("1080")
        vm.testConnection()
        assertEquals(ConnectionTestState.Testing, vm.uiState.value.test)

        vm.onNameChange("Office")
        gate.complete(ConnectionTestResult.Passed(1))
        assertEquals("never a pass for a form that was not tested", ConnectionTestState.Idle, vm.uiState.value.test)
    }

    @Test
    fun anEditedProfileIsTestedUnderItsIdSoItsSavedPasswordCanBeUsed() = runTest {
        val saved = repository.save(null, signIn)
        val vm = viewModel(editing(saved.id))
        vm.testConnection()
        assertEquals(saved.id, tester.requests.single().first)
        assertEquals("", tester.requests.single().second.password)
    }

    @Test
    fun aTesterThatBreaksIsReportedAsAFailureNeverAPass() = runTest {
        val broken = object : app.proxyflow.core.data.ConnectionTester {
            override suspend fun test(profileId: String?, input: ProxyProfileInput): ConnectionTestResult = error("engine crashed")
        }
        val vm = AddEditProxyViewModel(SavedStateHandle(), repository, broken)
        vm.onServerChange("proxy.example.com")
        vm.onPortChange("1080")
        vm.testConnection()
        assertEquals(ConnectionTestState.Done(ConnectionTestResult.Failed(AddEditProxyViewModel.INTERNAL_ERROR)), vm.uiState.value.test)
    }

    @Test
    fun theFormWaitsForTheProfileAndIgnoresInputUntilThen() = runTest {
        val saved = repository.save(null, signIn.copy(name = "Office"))
        val gate = CompletableDeferred<Unit>()
        val slow = object : ProfileRepository by repository {
            override suspend fun loadForEditing(id: String): ProfileForEditing? {
                gate.await()
                return repository.loadForEditing(id)
            }
        }
        val vm = viewModel(editing(saved.id), slow)
        assertTrue(vm.uiState.value.loading)
        vm.onServerChange("typed-too-early.example.com")
        vm.save()
        vm.testConnection()
        assertTrue(tester.requests.isEmpty())

        gate.complete(Unit)
        val state = vm.uiState.value
        assertFalse(state.loading)
        assertEquals("the stored profile, not the form defaults", "proxy.example.com", state.input.server)
        assertTrue(state.input.requiresSignIn)
        assertEquals("user", state.input.username)
        assertFalse(state.dirty)
        assertFalse(state.saved)
    }

    @Test
    fun aProfileThatCantBeReadIsShownAsSuchAndNeverSavedOver() = runTest {
        val saved = repository.save(null, signIn)
        val failing = object : ProfileRepository by repository {
            override suspend fun loadForEditing(id: String): ProfileForEditing? = throw CredentialsUnavailableException()
        }
        val vm = viewModel(editing(saved.id), failing)
        assertTrue(vm.uiState.value.loadFailed)
        assertFalse(vm.uiState.value.loading)

        vm.onRequiresSignInChange(false)
        vm.save()
        assertFalse(vm.uiState.value.saved)
        assertTrue("sign-in and its key are untouched", repository.get(saved.id)!!.requiresSignIn)
        assertEquals("secret", savedPassword(saved.id))
    }

    @Test
    fun namesAreCutWithoutBreakingAnEmoji() {
        val vm = viewModel()
        vm.onNameChange("x".repeat(39) + "\uD83D\uDE00")
        assertEquals("x".repeat(39), vm.uiState.value.input.name)
    }

    @Test
    fun thePortTakesOnlyTheDigits0To9() {
        val vm = viewModel()
        vm.onPortChange("\u0661\u0660\u0668\u06601080")
        assertEquals("1080", vm.uiState.value.input.port)
    }
}
