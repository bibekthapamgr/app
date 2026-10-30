package app.proxyflow.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.proxyflow.core.data.ConnectionController
import app.proxyflow.core.data.ProfileRepository
import app.proxyflow.core.model.ConnectionStatus
import app.proxyflow.core.model.ProxyProfile
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

sealed interface HomeUiState {
    data object Loading : HomeUiState

    /** Board B1. */
    data object NoProfile : HomeUiState

    /** Board B2 (and, from M5, B3–B7). */
    data class Ready(val profile: ProxyProfile, val status: ConnectionStatus, val canConnect: Boolean) : HomeUiState
}

class HomeViewModel(profiles: ProfileRepository, private val connection: ConnectionController) : ViewModel() {
    val uiState: StateFlow<HomeUiState> =
        combine(profiles.profiles, profiles.activeProfileId, connection.status) { list, activeId, status ->
            val active = list.firstOrNull { it.id == activeId } ?: list.firstOrNull()
            if (active == null) HomeUiState.NoProfile else HomeUiState.Ready(active, status, connection.canConnect)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HomeUiState.Loading)

    fun connect() {
        val state = uiState.value
        if (state is HomeUiState.Ready && state.canConnect) connection.connect(state.profile.id)
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
