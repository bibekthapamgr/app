package app.proxyflow.feature.profiles

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.proxyflow.core.data.ConnectionTester
import app.proxyflow.core.data.InvalidProfileException
import app.proxyflow.core.data.ProfileRepository
import app.proxyflow.core.model.ConnectionTestResult
import app.proxyflow.core.model.CredentialState
import app.proxyflow.core.model.ProfileError
import app.proxyflow.core.model.ProfileField
import app.proxyflow.core.model.ProfileNames
import app.proxyflow.core.model.ProfileValidator
import app.proxyflow.core.model.ProxyProfileInput
import app.proxyflow.core.model.ProxyProtocol
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where "Test connection" stands (boards D5–D7). */
sealed interface ConnectionTestState {
    data object Idle : ConnectionTestState

    data object Testing : ConnectionTestState

    /** What the tester found for the form exactly as it is now; any edit clears it. */
    data class Done(val result: ConnectionTestResult) : ConnectionTestState
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

    fun save() {
        val current = state.value
        if (current.loading || current.loadFailed || current.saving || current.saved || !passesValidation()) return
        state.update { it.copy(saving = true, saveFailed = false) }
        // A profile that no longer exists is saved as a new one, under a new id.
        val id = profileId.takeIf { current.isEdit }
        viewModelScope.launch {
            try {
                repository.save(id, current.input)
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

    /** Tests the form as it is now. Only the tester's own answer is shown; nothing else ever reports success. */
    fun testConnection() {
        val current = state.value
        if (current.loading || current.loadFailed || current.test == ConnectionTestState.Testing || !passesValidation()) return
        val input = state.value.input
        val id = profileId.takeIf { state.value.isEdit }
        state.update { it.copy(test = ConnectionTestState.Testing) }
        testJob = viewModelScope.launch {
            val result = try {
                tester.test(id, input)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A tester that broke tested nothing: that is a failure, never a pass.
                ConnectionTestResult.Failed(INTERNAL_ERROR)
            }
            // A result is only shown for the form it was made with.
            if (state.value.input == input) state.update { it.copy(test = ConnectionTestState.Done(result)) }
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

    companion object {
        /** Must match the property name in [AddEditProxyDestination]. */
        const val ARG_PROFILE_ID = "profileId"

        /** Error code for a test that could not run (docs/m1/04 error catalogue: `INTERNAL`). */
        const val INTERNAL_ERROR = "INTERNAL"
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
