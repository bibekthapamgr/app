package app.proxyflow.feature.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.proxyflow.core.data.LogRepository
import app.proxyflow.core.model.LogEntry
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class LogsUiState(val loading: Boolean = true, val entries: List<LogEntry> = emptyList())

/** Newest entries first. Filters and the detail sheet (boards F2, F3) arrive with the engine's log in M7. */
class LogsViewModel(logs: LogRepository) : ViewModel() {
    val uiState: StateFlow<LogsUiState> =
        logs.entries
            .map { list -> LogsUiState(loading = false, entries = list.sortedByDescending { it.timeMillis }) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LogsUiState())

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
