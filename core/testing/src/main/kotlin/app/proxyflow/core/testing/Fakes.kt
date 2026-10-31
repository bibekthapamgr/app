package app.proxyflow.core.testing

import app.proxyflow.core.data.LogRepository
import app.proxyflow.core.data.SettingsRepository
import app.proxyflow.core.model.LogEntry
import app.proxyflow.core.model.ThemePreference
import app.proxyflow.core.model.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Settings held in memory, for ViewModel and UI tests. */
class FakeSettingsRepository(initial: UserSettings = UserSettings()) : SettingsRepository {
    val state = MutableStateFlow(initial)
    override val settings: Flow<UserSettings> = state

    override suspend fun setTheme(theme: ThemePreference) = state.update { it.copy(theme = theme) }
}

/** A log whose entries a test can set directly. */
class FakeLogRepository(initial: List<LogEntry> = emptyList()) : LogRepository {
    val state = MutableStateFlow(initial)
    override val entries: Flow<List<LogEntry>> = state
}
