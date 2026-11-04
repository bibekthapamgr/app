package app.proxyflow.core.vpn

import app.proxyflow.core.model.ConnectionState
import app.proxyflow.core.model.ProxyProfile
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch

/**
 * Watches the saved profile a connection runs on, for as long as a connection exists (J11), in [VpnRuntime]:
 * - deleted, its credentials must not stay in use: [deleted] stops the connection's engine;
 * - renamed, the new name shows at once — Home, the tile, the notification ([renamed]); a name is not part of how the
 *   connection reaches its proxy, so nothing reconnects.
 *
 * The profiles ([profiles], Room) are read only while a connection exists. A failure reading them is logged ([log]) and
 * the read tried again ([retryReading]) — never left to end the process the VPN runs in, and never the end of the watch:
 * a deletion after Room recovers still stops the connection.
 */
internal fun CoroutineScope.watchConnectionProfile(
    state: Flow<ConnectionState>,
    profiles: Flow<List<ProxyProfile>>,
    deleted: (profileId: String) -> Unit,
    renamed: (profileId: String, name: String) -> Unit,
    log: (String) -> Unit,
    retryDelayMillis: (failuresInARow: Long) -> Long = ::profileReadRetryDelay,
): Job {
    val connection = state.map { s -> s.profileId?.takeIf { s.active } }.distinctUntilChanged()
    val readable = profiles.retryReading(log, retryDelayMillis)
    return launch {
        launch(CoroutineName(DELETION_WATCH)) {
            connection.collectLatest { id ->
                if (id == null) return@collectLatest
                val gone = readable.firstOrNull { list -> list.none { it.id == id } }
                if (gone != null) deleted(id)
            }
        }
        launch(CoroutineName(RENAME_WATCH)) {
            connection.collectLatest { id ->
                if (id == null) return@collectLatest
                readable.mapNotNull { list -> list.firstOrNull { it.id == id }?.name }
                    .distinctUntilChanged()
                    .collect { name -> renamed(id, name) }
            }
        }
    }
}

internal const val DELETION_WATCH = "profile deletion watch"
internal const val RENAME_WATCH = "profile rename watch"

/** How long a read must stay good before the count of failures starts over: the store has recovered. */
internal const val RECOVERED_AFTER_MILLIS = 60_000L

/**
 * The profiles, read again after each failure: logged ([log]), then tried again after [delayMillis] of the failures in a
 * row so far (0 for the first). A read that stays good for [RECOVERED_AFTER_MILLIS] ([clockMillis]) starts the count
 * over; one that fails again sooner doesn't, so a store that keeps failing soon after each read is still tried less and
 * less often. Cancellation and failures downstream are not retried.
 */
internal fun Flow<List<ProxyProfile>>.retryReading(
    log: (String) -> Unit,
    delayMillis: (failuresInARow: Long) -> Long,
    clockMillis: () -> Long = { System.nanoTime() / 1_000_000 },
): Flow<List<ProxyProfile>> = flow {
    var inARow = 0L
    var goodSince: Long? = null
    emitAll(
        onEach { if (goodSince == null) goodSince = clockMillis() }.retryWhen { e, _ ->
            val good = goodSince
            goodSince = null
            if (good != null && clockMillis() - good >= RECOVERED_AFTER_MILLIS) inARow = 0
            log("profiles can't be read (${e.javaClass.simpleName}); trying again")
            delay(delayMillis(inARow++))
            true
        },
    )
}

/** 1 s after the first failure in a row, doubling up to 30 s between tries: a store that stays broken fills no log. */
internal fun profileReadRetryDelay(failuresInARow: Long): Long = minOf(1_000L shl failuresInARow.coerceAtMost(5).toInt(), 30_000L)
