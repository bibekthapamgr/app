package app.proxyflow.feature.profiles

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.proxyflow.core.data.ProfileRepository
import app.proxyflow.core.model.ProfileError
import app.proxyflow.core.model.ProfileField
import app.proxyflow.core.model.ProfileValidator
import app.proxyflow.core.model.ProxyProfileInput
import app.proxyflow.core.model.ProxyProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AddEditUiState(
    val isEdit: Boolean = false,
    val input: ProxyProfileInput = ProxyProfileInput(),
    /** Shown only after the first Save attempt, then kept live while the user fixes them. */
    val errors: Map<ProfileField, ProfileError> = emptyMap(),
    val hasSavedPassword: Boolean = false,
    /**
     * Leaving the password empty keeps the saved one: true only while protocol, server, port,
     * username and TLS settings are unchanged, so a password never follows a profile to another proxy.
     */
    val keepsSavedPassword: Boolean = false,
    /** Display name of the profile being edited, for the discard dialog (board D8). */
    val originalName: String? = null,
    val dirty: Boolean = false,
    val saving: Boolean = false,
    val saved: Boolean = false,
    /** Save attempts that found errors; each one scrolls the form back to the error summary. */
    val rejectedSaves: Int = 0,
)

/**
 * Add/Edit form state. Every field except the username and password is kept in
 * [SavedStateHandle], so it survives rotation, theme changes and process death.
 * The credentials (ADR-0006) stay in this ViewModel's memory only, so they are
 * never written into saved instance state, which Android may persist; after
 * process death the user enters them again (an edited profile's username comes
 * back from the profile itself).
 */
class AddEditProxyViewModel(private val savedState: SavedStateHandle, private val repository: ProfileRepository) : ViewModel() {
    /** Navigation argument (AddEditProxyDestination.profileId); null when adding. */
    private val profileId: String? = savedState[ARG_PROFILE_ID]

    private val state = MutableStateFlow(AddEditUiState(isEdit = profileId != null, input = restore() ?: ProxyProfileInput()))
    val uiState: StateFlow<AddEditUiState> = state.asStateFlow()

    private var initial: ProxyProfileInput = ProxyProfileInput()
    private var showErrors: Boolean = savedState[KEY_SHOW_ERRORS] ?: false

    init {
        if (profileId != null) {
            viewModelScope.launch {
                val profile = repository.get(profileId)
                if (profile == null) {
                    // Deleted, or (in M2) lost with the app process: offer what was restored as a new profile,
                    // and let Back ask before discarding it.
                    state.update { it.copy(isEdit = false) }
                    refresh()
                    return@launch
                }
                initial = ProxyProfileInput.from(profile)
                state.update { s ->
                    // After rotation or process death the restored fields win over the stored profile,
                    // except the username, which is not kept in saved state.
                    val input = if (savedState.contains(KEY_SERVER)) s.input.copy(username = initial.username) else initial
                    s.copy(input = input, hasSavedPassword = profile.hasPassword, originalName = profile.displayName)
                }
                refresh()
            }
        } else {
            refresh()
        }
    }

    fun onNameChange(value: String) = edit { it.copy(name = value.take(ProfileValidator.MAX_NAME_LENGTH)) }

    fun onProtocolChange(value: ProxyProtocol) = edit { it.copy(protocol = value) }

    fun onServerChange(value: String) = edit { it.copy(server = value) }

    fun onPortChange(value: String) = edit { it.copy(port = value.filter(Char::isDigit).take(5)) }

    fun onRequiresSignInChange(value: Boolean) = edit { it.copy(requiresSignIn = value) }

    fun onUsernameChange(value: String) = edit { it.copy(username = value) }

    fun onPasswordChange(value: String) = edit { it.copy(password = value) }

    fun onUseTlsChange(value: Boolean) = edit { it.copy(useTls = value) }

    fun onTlsServerNameChange(value: String) = edit { it.copy(tlsServerName = value) }

    fun save() {
        val current = state.value
        if (current.saving || current.saved) return
        showErrors = true
        savedState[KEY_SHOW_ERRORS] = true
        refresh()
        if (state.value.errors.isNotEmpty()) {
            state.update { it.copy(rejectedSaves = it.rejectedSaves + 1) }
            return
        }
        state.update { it.copy(saving = true) }
        // A profile that no longer exists is saved as a new one, under a new id.
        val id = profileId.takeIf { current.isEdit }
        viewModelScope.launch {
            repository.save(id, current.input)
            state.update { it.copy(saving = false, saved = true, dirty = false) }
        }
    }

    private fun edit(transform: (ProxyProfileInput) -> ProxyProfileInput) {
        state.update { it.copy(input = transform(it.input)) }
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
