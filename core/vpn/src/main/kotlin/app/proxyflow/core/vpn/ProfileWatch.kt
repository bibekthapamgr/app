package app.proxyflow.core.vpn

import app.proxyflow.core.model.ConnectionState
import app.proxyflow.core.model.ProxyProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch

/**
 * Watches the saved profile a connection runs on, for as long as a connection exists (J11), in [VpnRuntime]:
 * - deleted, its credentials must not stay in use: [deleted] stops the connection's engine;
 * - renamed, the new name shows at once — Home, the tile, the notification ([renamed]); a name is not part of how the
 *   connection reaches its proxy, so nothing reconnects.
 *
 * The profiles ([profiles], Room) are read only while a connection exists. A failure reading them is logged ([log]) and
 * the read tried again after [retryDelayMillis] — never left to end the process the VPN runs in, and never the end of
 * the watch: a deletion after Room recovers still stops the connection.
 */
internal fun CoroutineScope.watchConnectionProfile(
    state: Flow<ConnectionState>,
    profiles: Flow<List<ProxyProfile>>,
    deleted: (profileId: String) -> Unit,
    renamed: (profileId: String, name: String) -> Unit,
    log: (String) -> Unit,
    retryDelayMillis: (attempt: Long) -> Long = ::profileReadRetryDelay,
): Job {
    val connection = state.map { s -> s.profileId?.takeIf { s.active } }.distinctUntilChanged()
    val readable = profiles.retryWhen { e, attempt ->
        log("profiles can't be read (${e.javaClass.simpleName}); trying again")
        delay(retryDelayMillis(attempt))
        true
    }
    return launch {
        launch {
            connection.collectLatest { id ->
                if (id == null) return@collectLatest
                val gone = readable.firstOrNull { list -> list.none { it.id == id } }
                if (gone != null) deleted(id)
            }
        }
        launch {
            connection.collectLatest { id ->
                if (id == null) return@collectLatest
                readable.mapNotNull { list -> list.firstOrNull { it.id == id }?.name }
                    .distinctUntilChanged()
                    .collect { name -> renamed(id, name) }
            }
        }
    }
}

/** 1 s after the first failure, doubling up to 30 s between tries: a store that stays broken fills no log. */
internal fun profileReadRetryDelay(attempt: Long): Long = minOf(1_000L shl attempt.coerceAtMost(5).toInt(), 30_000L)
