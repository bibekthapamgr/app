package app.proxyflow.feature.profiles

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.proxyflow.core.data.ConnectionController
import app.proxyflow.core.data.ConnectionTester
import app.proxyflow.core.data.InvalidProfileException
import app.proxyflow.core.data.ProfileRepository
import app.proxyflow.core.model.ConnectionError
import app.proxyflow.core.model.ConnectionState
import app.proxyflow.core.model.ConnectionTestResult
import app.proxyflow.core.model.CredentialState
import app.proxyflow.core.model.ProfileError
import app.proxyflow.core.model.ProfileField
import app.proxyflow.core.model.ProfileNames
import app.proxyflow.core.model.ProfileValidator
import app.proxyflow.core.model.ProxyProfile
import app.proxyflow.core.model.ProxyProfileInput
import app.proxyflow.core.model.ProxyProtocol
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where "Test connection" stands (boards D5–D7). */
sealed interface ConnectionTestState {
    data object Idle : ConnectionTestState

    data object Testing : ConnectionTestState

    /** What the tester found for the form exactly as it is now, which is [tested]; any edit clears it. */
    data class Done(val result: ConnectionTestResult, val tested: ProxyProfile) : ConnectionTestState
}

data class AddEditUiState(
    val isEdit: Boolean = false,
    /** The profile being edited is still being read (from Room and the Keystore); the form is not shown yet. */
    val loading: Boolean = false,
    /** The profile could not be read (storage or Keystore failure); nothing can be edited or saved. */
    val loadFailed: Boolean = false,
    val input: ProxyProfileInput = ProxyProfileInput(),
    /** Shown only after the first Save or Test attempt, then kept live while the user fixes them. */
    val errors: Map<ProfileField, ProfileError> = emptyMap(),
    val hasSavedPassword: Boolean = false,
    /**
     * Leaving the password empty keeps the saved one: true only while protocol, server, port,
     * username and TLS settings are unchanged, so a password never follows a profile to another proxy.
     */
    val keepsSavedPassword: Boolean = false,
    /** The saved username and password can't be read on this phone any more and must be entered again (H3). */
    val credentialsUnreadable: Boolean = false,
    /** Display name of the profile being edited, for the discard dialog (board D8). */
    val originalName: String? = null,
    val dirty: Boolean = false,
    val saving: Boolean = false,
    val saved: Boolean = false,
    /** The last Save failed for a reason the form can't show next to a field (storage or Keystore). */
    val saveFailed: Boolean = false,
    /** Save or Test attempts that found errors; each one scrolls the form back to the error summary. */
    val rejectedSaves: Int = 0,
    val test: ConnectionTestState = ConnectionTestState.Idle,
    /**
     * Board D9: Save was tapped on changes to how the running connection reaches its proxy; the user confirms reconnecting
     * with them first. The name of the profile the connection runs on.
     */
    val confirmReconnect: String? = null,
)

/**
 * Add/Edit form state. Every field except the username and password is kept in
 * [SavedStateHandle], so it survives rotation, theme changes and process death.
 * The credentials (ADR-0006) stay in this ViewModel's memory only, so they are
 * never written into saved instance state, which Android may persist; after
 * process death the user enters them again (an edited profile's username comes
 * back from its encrypted store).
 */
class AddEditProxyViewModel(
    private val savedState: SavedStateHandle,
    private val repository: ProfileRepository,
    private val tester: ConnectionTester,
    private val connection: ConnectionController,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    /** Navigation argument (AddEditProxyDestination.profileId); null when adding. */
    private val profileId: String? = savedState[ARG_PROFILE_ID]

    /** Whether this ViewModel restores a form from before process death; decided before any keystroke can change it. */
    private val restored: Boolean = savedState.contains(KEY_SERVER)

    private val state = MutableStateFlow(
        AddEditUiState(isEdit = profileId != null, loading = profileId != null, input = restore() ?: ProxyProfileInput()),
    )
    val uiState: StateFlow<AddEditUiState> = state.asStateFlow()

    private var initial: ProxyProfileInput = ProxyProfileInput()
    private var showErrors: Boolean = savedState[KEY_SHOW_ERRORS] ?: false
    private var testJob: Job? = null

    /** The last test's form, result and time: kept with the profile when exactly that form is saved (board C2). */
    private var lastTested: Tested? = null

    init {
        if (profileId != null) {
            viewModelScope.launch {
                val editing = try {
                    repository.loadForEditing(profileId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Storage or Keystore failure: show it instead of a form that would save over the profile.
                    state.update { it.copy(loading = false, loadFailed = true) }
                    return@launch
                }
                if (editing == null) {
                    // Deleted meanwhile: offer what was restored as a new profile, and let Back ask before discarding it.
                    state.update { it.copy(isEdit = false, loading = false) }
                    refresh()
                    return@launch
                }
                initial = editing.input
                state.update { s ->
                    // After rotation or process death the restored fields win over the stored profile,
                    // except the username, which is not kept in saved state.
                    val input = if (restored) s.input.copy(username = initial.username) else initial
                    s.copy(
                        loading = false,
                        input = input,
                        hasSavedPassword = editing.credentials == CredentialState.Saved,
                        credentialsUnreadable = editing.credentials == CredentialState.Unreadable,
                        originalName = editing.profile.displayName,
                    )
                }
                refresh()
            }
        } else {
            refresh()
        }
    }

    fun onNameChange(value: String) = edit { it.copy(name = ProfileNames.truncate(value, ProfileValidator.MAX_NAME_LENGTH)) }

    fun onProtocolChange(value: ProxyProtocol) = edit { it.copy(protocol = value) }

    fun onServerChange(value: String) = edit { it.copy(server = value) }

    fun onPortChange(value: String) = edit { it.copy(port = value.filter { c -> c in '0'..'9' }.take(5)) }

    fun onRequiresSignInChange(value: Boolean) = edit { it.copy(requiresSignIn = value) }

    fun onUsernameChange(value: String) = edit { it.copy(username = value) }

    fun onPasswordChange(value: String) = edit { it.copy(password = value) }

    fun onUseTlsChange(value: Boolean) = edit { it.copy(useTls = value) }

    fun onTlsServerNameChange(value: String) = edit { it.copy(tlsServerName = value) }

    /**
     * Saves the form. When a connection runs on this profile and the changes are to how it reaches its proxy — its server,
     * TLS or sign-in — the user is asked first (board D9): the connection then runs again with them ([saveAndReconnect]).
     * During a first connect nothing is held yet ([ConnectionState.holdsTrafficOnSwitch]): the attempt is checked again
     * with what is saved, without asking.
     */
    fun save() = save(reconnect = false)

    /** D9's "Save and reconnect": saves, then the connection runs again with what was saved (J11). */
    fun saveAndReconnect() {
        state.update { it.copy(confirmReconnect = null) }
        save(reconnect = true)
    }

    /** D9's "Keep editing": nothing is saved. */
    fun keepEditing() = state.update { it.copy(confirmReconnect = null) }

    private fun save(reconnect: Boolean) {
        val current = state.value
        if (current.loading || current.loadFailed || current.saving || current.saved || !passesValidation()) return
        // A profile that no longer exists is saved as a new one, under a new id.
        val id = profileId.takeIf { current.isEdit }
        val running = connection.state.value.takeIf { id != null && it.active && it.profileId == id }
        val changed = running?.takeIf { changesTheConnection(it, current.input) }
        if (!reconnect && changed != null && changed.holdsTrafficOnSwitch) {
            // Named as saved: a rename since it connected shows (the connection's own name is from its start).
            state.update { it.copy(confirmReconnect = current.originalName ?: changed.profileName.orEmpty()) }
            return
        }
        val reconnects = reconnect || changed != null
        state.update { it.copy(saving = true, saveFailed = false) }
        viewModelScope.launch {
            try {
                // Whole, even if the screen closes meanwhile (which ends this scope): the profile, its last test when it
                // tested exactly what is saved, and the reconnect the user confirmed (D9).
                withContext(NonCancellable) {
                    val saved = repository.save(id, current.input)
                    lastTested?.takeIf { it.input == current.input }?.let { keep(saved, it) }
                    // Flows end and traffic is held until the saved settings are checked, never sent around a proxy;
                    // during a first connect, the attempt starts again with them.
                    if (reconnects) connection.reconnect(saved.id)
                }
                state.update { it.copy(saving = false, saved = true, dirty = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: InvalidProfileException) {
                // The repository checks again; it can only disagree if the saved profile changed meanwhile.
                state.update { it.copy(saving = false, errors = e.errors, rejectedSaves = it.rejectedSaves + 1) }
            } catch (e: Exception) {
                state.update { it.copy(saving = false, saveFailed = true) }
            }
        }
    }

    /** Whether saving [input] changes how the connection [running] reaches its proxy: its server, TLS, or sign-in. */
    private fun changesTheConnection(running: ConnectionState, input: ProxyProfileInput): Boolean {
        val runs = running.profile ?: return true
        val edited = input.toProfile(runs.id, createdAtMillis = 0, updatedAtMillis = 0)
        // A typed password is a new sign-in; a new username needs one too (the form asks for it).
        return !edited.sameServerAs(runs) || (input.requiresSignIn && input.password.isNotEmpty())
    }

    /** Tests the form as it is now. Only the tester's own answer is shown; nothing else ever reports success. */
    fun testConnection() {
        val current = state.value
        if (current.loading || current.loadFailed || current.test == ConnectionTestState.Testing || !passesValidation()) return
        val input = state.value.input
        val id = profileId.takeIf { state.value.isEdit }
        // Validated, so its port is a number; the screen words the result for this server.
        val tested = input.toProfile(id ?: "", createdAtMillis = 0, updatedAtMillis = 0)
        state.update { it.copy(test = ConnectionTestState.Testing) }
        testJob = viewModelScope.launch {
            val result = try {
                tester.test(id, input)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A tester that broke tested nothing: that is a failure, never a pass.
                ConnectionTestResult.Failed(ConnectionError(ConnectionError.INTERNAL, "the test could not run"), fromEngine = false)
            }
            // A result is only shown for the form it was made with.
            if (state.value.input == input) {
                lastTested = Tested(input, result, clock())
                state.update { it.copy(test = ConnectionTestState.Done(result, tested)) }
            }
        }
    }

    /** Stops a test that is running; nothing is shown or kept for it. */
    fun cancelTest() {
        if (state.value.test != ConnectionTestState.Testing) return
        testJob?.cancel()
        state.update { it.copy(test = ConnectionTestState.Idle) }
    }

    /**
     * Keeps [test] as [profile]'s last test. Only the engine's answers are: not this phone's own failures (`INTERNAL`),
     * and not what the tester found before asking the engine — a password it had none of, say — which describes the form,
     * not the proxy. A storage failure only loses it.
     */
    private suspend fun keep(profile: ProxyProfile, test: Tested) {
        val result = test.result
        if (result is ConnectionTestResult.Failed && (result.code == ConnectionError.INTERNAL || !result.fromEngine)) return
        try {
            repository.recordTest(profile.id, profile, result.asProfileTest(test.atMillis))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The profile is saved; only its test result isn't shown in the list.
        }
    }

    /** Shows the errors from now on; false (and the form scrolls to them) when there are any. */
    private fun passesValidation(): Boolean {
        showErrors = true
        savedState[KEY_SHOW_ERRORS] = true
        refresh()
        if (state.value.errors.isEmpty()) return true
        state.update { it.copy(rejectedSaves = it.rejectedSaves + 1) }
        return false
    }

    private fun edit(transform: (ProxyProfileInput) -> ProxyProfileInput) {
        // Nothing to edit until the profile is read; the screen shows no fields meanwhile.
        if (state.value.loading || state.value.loadFailed) return
        testJob?.cancel()
        // The test was of the form as it was; its copy of a typed password goes too.
        lastTested = null
        state.update { it.copy(input = transform(it.input), test = ConnectionTestState.Idle, saveFailed = false) }
        persist(state.value.input)
        refresh()
    }

    private fun refresh() = state.update { s ->
        val keeps = s.hasSavedPassword && s.input.sameCredentialTargetAs(initial)
        s.copy(
            keepsSavedPassword = keeps,
            errors = if (showErrors) ProfileValidator.validate(s.input, savedPasswordKept = keeps) else emptyMap(),
            dirty = s.input != initial,
        )
    }

    private fun persist(input: ProxyProfileInput) {
        savedState[KEY_NAME] = input.name
        savedState[KEY_PROTOCOL] = input.protocol.name
        savedState[KEY_SERVER] = input.server
        savedState[KEY_PORT] = input.port
        savedState[KEY_SIGN_IN] = input.requiresSignIn
        savedState[KEY_TLS] = input.useTls
        savedState[KEY_TLS_NAME] = input.tlsServerName
    }

    private fun restore(): ProxyProfileInput? {
        if (!savedState.contains(KEY_SERVER)) return null
        return ProxyProfileInput(
            name = savedState[KEY_NAME] ?: "",
            protocol = ProxyProtocol.entries.firstOrNull { it.name == savedState.get<String>(KEY_PROTOCOL) } ?: ProxyProtocol.SOCKS5,
            server = savedState[KEY_SERVER] ?: "",
            port = savedState[KEY_PORT] ?: "",
            requiresSignIn = savedState[KEY_SIGN_IN] ?: false,
            username = "",
            password = "",
            useTls = savedState[KEY_TLS] ?: false,
            tlsServerName = savedState[KEY_TLS_NAME] ?: "",
        )
    }

    private data class Tested(val input: ProxyProfileInput, val result: ConnectionTestResult, val atMillis: Long)

    companion object {
        /** Must match the property name in [AddEditProxyDestination]. */
        const val ARG_PROFILE_ID = "profileId"

        private const val KEY_NAME = "form.name"
        private const val KEY_PROTOCOL = "form.protocol"
        private const val KEY_SERVER = "form.server"
        private const val KEY_PORT = "form.port"
        private const val KEY_SIGN_IN = "form.signIn"
        private const val KEY_TLS = "form.tls"
        private const val KEY_TLS_NAME = "form.tlsName"
        private const val KEY_SHOW_ERRORS = "form.showErrors"
    }
}
