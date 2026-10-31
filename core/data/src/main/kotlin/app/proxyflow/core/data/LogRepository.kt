package app.proxyflow.core.data

import app.proxyflow.core.model.LogEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The local event log shown on the Logs screen. The engine writes to it from M7. */
interface LogRepository {
    val entries: Flow<List<LogEntry>>
}

/** M2: no engine yet, so the log stays empty. */
class InMemoryLogRepository : LogRepository {
    private val list = MutableStateFlow<List<LogEntry>>(emptyList())
    override val entries: Flow<List<LogEntry>> = list.asStateFlow()
}
