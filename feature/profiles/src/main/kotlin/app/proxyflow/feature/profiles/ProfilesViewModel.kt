package app.proxyflow.feature.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.proxyflow.core.data.ProfileRepository
import app.proxyflow.core.model.ProfileNames
import app.proxyflow.core.model.ProxyProfile
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ProfilesUiState(
    val loading: Boolean = true,
    val profiles: List<ProxyProfile> = emptyList(),
    val activeProfileId: String? = null,
)

class ProfilesViewModel(private val repository: ProfileRepository) : ViewModel() {
    val uiState: StateFlow<ProfilesUiState> =
        combine(repository.profiles, repository.activeProfileId) { list, active ->
            ProfilesUiState(loading = false, profiles = list, activeProfileId = active)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfilesUiState())

    fun setActive(id: String) {
        viewModelScope.launch { repository.setActive(id) }
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.delete(id) }
    }

    /**
     * Copies the profile, its username and password included. [copyName] builds the
     * name from the original's and a copy number ("Office (copy)", "Office (copy 2)").
     */
    fun duplicate(id: String, copyName: (base: String, number: Int) -> String = ProfileNames.englishCopyFormat) {
        viewModelScope.launch {
            val profiles = repository.profiles.first()
            val original = profiles.firstOrNull { it.id == id } ?: return@launch
            repository.duplicate(id, ProfileNames.copyOf(original.displayName, profiles.map { it.name }, copyName))
        }
    }
}
