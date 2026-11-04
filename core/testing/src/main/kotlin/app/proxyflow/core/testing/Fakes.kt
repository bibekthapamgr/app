package app.proxyflow.core.testing

import app.proxyflow.core.data.ConnectionController
import app.proxyflow.core.data.LogRepository
import app.proxyflow.core.data.SettingsRepository
import app.proxyflow.core.model.ConnectionState
import app.proxyflow.core.model.LogEntry
import app.proxyflow.core.model.LogRetention
import app.proxyflow.core.model.ThemePreference
import app.proxyflow.core.model.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Settings held in memory, for ViewModel and UI tests. */
class FakeSettingsRepository(initial: UserSettings = UserSettings()) : SettingsRepository {
    val state = MutableStateFlow(initial)
    override val settings: Flow<UserSettings> = state

    override suspend fun setTheme(theme: ThemePreference) = state.update { it.copy(theme = theme) }

    override suspend fun setLogRetention(retention: LogRetention) = state.update { it.copy(logRetention = retention) }

    override suspend fun setOnboardingCompleted() = state.update { it.copy(onboardingCompleted = true) }
}

/** A log whose entries a test can set directly; appends get ids, and purges are counted. */
class FakeLogRepository(initial: List<LogEntry> = emptyList()) : LogRepository {
    val state = MutableStateFlow(initial)
    override val entries: Flow<List<LogEntry>> = state
    var purges = 0
        private set
    override var generation = 0L
        private set
    override val retentionMillis = MutableStateFlow(LogRepository.RETENTION_MILLIS)

    override suspend fun setRetention(millis: Long) {
        retentionMillis.value = millis
        purges++
    }

    override suspend fun append(entries: List<LogEntry>, generation: Long) {
        if (generation < this.generation) return
        state.update { kept -> entries.mapIndexed { i, e -> e.copy(id = (kept.maxOfOrNull { it.id } ?: 0) + i + 1) } + kept }
    }

    override suspend fun clear() {
        generation++
        state.value = emptyList()
    }

    override suspend fun purge() {
        purges++
    }
}

/** A controller that can't connect, for ViewModel tests that don't connect: always disconnected, Connect not available. */
class UnavailableConnectionController : ConnectionController {
    private val current = MutableStateFlow(ConnectionState.Idle)
    override val state: StateFlow<ConnectionState> = current.asStateFlow()
    override val canConnect: Boolean = false

    override fun connect(profileId: String) = Unit

    override fun disconnect() = Unit
}
