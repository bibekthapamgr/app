package app.proxyflow

import app.proxyflow.core.data.LogRepository
import app.proxyflow.core.model.LogRetention
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps the diagnostic log within its bounds for as long as the process runs (ADR-0012, ADR-0014): first how long it
 * keeps entries as the user set it ([retention], board E1) — a purge with the default before that would delete what a
 * longer setting keeps (M10) — then a purge at once and every [intervalMillis] (docs/m1/03 J10: retention applied at
 * least daily), and each change of the setting, which deletes at once what a shorter time no longer allows. A setting
 * that can't be read, or isn't read within [settingsWaitMillis], keeps the default; a failure never stops the loop.
 */
internal suspend fun keepTheLogsBounds(
    logs: LogRepository,
    retention: Flow<LogRetention>,
    intervalMillis: Long,
    settingsWaitMillis: Long,
) = coroutineScope {
    var applied = withTimeoutOrNull(settingsWaitMillis) { retention.catch {}.firstOrNull() }
    applied?.let { runCatching { logs.setRetention(it.millis) } }
    launch {
        while (true) {
            runCatching { logs.purge() }
            delay(intervalMillis)
        }
    }
    retention.distinctUntilChanged().catch {}.collect {
        if (it != applied) {
            applied = it
            runCatching { logs.setRetention(it.millis) }
        }
    }
}
