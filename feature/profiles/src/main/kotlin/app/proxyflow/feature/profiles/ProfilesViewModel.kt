package app.proxyflow.feature.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.proxyflow.core.data.ProfileRepository
import app.proxyflow.core.model.ProfileNames
import app.proxyflow.core.model.ProxyProfile
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A list action that did not complete (storage or Android Keystore failure); the screen says so once. */
enum class ProfileAction { Use, Duplicate, Delete }

data class ProfilesUiState(
    val loading: Boolean = true,
    val profiles: List<ProxyProfile> = emptyList(),
    val activeProfileId: String? = null,
    val failed: ProfileAction? = null,
)

class ProfilesViewModel(private val repository: ProfileRepository) : ViewModel() {
    private val failed = MutableStateFlow<ProfileAction?>(null)

    val uiState: StateFlow<ProfilesUiState> =
        combine(repository.profiles, repository.activeProfileId, failed) { list, active, failure ->
            ProfilesUiState(loading = false, profiles = list, activeProfileId = active, failed = failure)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfilesUiState())

    fun setActive(id: String) = perform(ProfileAction.Use) { repository.setActive(id) }

    fun delete(id: String) = perform(ProfileAction.Delete) { repository.delete(id) }

    /** The screen has told the user about [ProfilesUiState.failed]. */
    fun onFailureShown() {
        failed.value = null
    }

    /**
     * Copies the profile, its username and password included. [copyName] builds the
     * name from the original's and a copy number ("Office (copy)", "Office (copy 2)").
     */
    fun duplicate(id: String, copyName: (base: String, number: Int) -> String = ProfileNames.englishCopyFormat) =
        perform(ProfileAction.Duplicate) {
            val profiles = repository.profiles.first()
            val original = profiles.firstOrNull { it.id == id } ?: return@perform
            repository.duplicate(id, ProfileNames.copyOf(original.displayName, profiles.map { it.name }, copyName))
        }

    /** Runs [block]; if storage or the Keystore fails, the list stays as it was and [action] is reported. */
    private fun perform(action: ProfileAction, block: suspend () -> Unit) {
        failed.value = null
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed.value = action
            }
        }
    }
}
