package app.proxyflow.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.proxyflow.core.model.ExitReason
import app.proxyflow.core.model.Interruption
import app.proxyflow.core.model.KillSwitchStatus
import app.proxyflow.core.model.SystemKillSwitch
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * [ProtectionStore] in its own Preferences DataStore file. A file that can't be read reads as empty (nothing known),
 * so the screens that show it never fail on it; writes that fail throw to the caller, which logs them.
 *
 * Every write of this file goes through here, and after each one the flows read DataStore afresh: DataStore's own
 * flow can miss a write for a collector that started while that write was on its way (it reads the old file under
 * the write's new version, then drops the write's own update as not newer). On the emulators that kept an
 * interruption from a test that began to wait for it just then (M8, run 41690836372); Home could miss it the same way.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreProtectionStore(private val dataStore: DataStore<Preferences>) : ProtectionStore {
    /** Counts this store's writes, once each is done (or has failed): each is a reason to read again. */
    private val writes = MutableStateFlow(0L)

    private val prefs: Flow<Preferences> = writes.flatMapLatest {
        dataStore.data.catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
    }

    private suspend fun write(change: (MutablePreferences) -> Unit) {
        try {
            dataStore.edit { change(it) }
        } finally {
            writes.update { it + 1 }
        }
    }

    override val killSwitch: Flow<SystemKillSwitch?> = prefs.map { prefs ->
        val checkedAt = prefs[KILL_SWITCH_CHECKED] ?: return@map null
        SystemKillSwitch(
            alwaysOn = prefs[KILL_SWITCH_ALWAYS_ON] ?: false,
            blocksWithoutVpn = prefs[KILL_SWITCH_BLOCKS] ?: false,
            checkedAtMillis = checkedAt,
        )
    }.distinctUntilChanged()

    override suspend fun recordKillSwitch(value: SystemKillSwitch) {
        write {
            it[KILL_SWITCH_ALWAYS_ON] = value.alwaysOn
            it[KILL_SWITCH_BLOCKS] = value.blocksWithoutVpn
            it[KILL_SWITCH_CHECKED] = value.checkedAtMillis
        }
    }

    override val interruption: Flow<Interruption?> = prefs.map { prefs ->
        val since = prefs[INTERRUPTED_SINCE] ?: return@map null
        Interruption(
            profileId = prefs[INTERRUPTED_PROFILE],
            connectedSinceMillis = since,
            endedAtMillis = prefs[INTERRUPTED_ENDED],
            reason = ExitReason.of(prefs[INTERRUPTED_REASON]),
            detectedAtMillis = prefs[INTERRUPTED_DETECTED] ?: since,
            killSwitch = KillSwitchStatus.of(prefs[INTERRUPTED_KILL_SWITCH]),
        )
    }.distinctUntilChanged()

    override suspend fun setInterruption(value: Interruption?) {
        write { prefs -> prefs.putInterruption(value) }
    }

    override suspend fun markConnected(marker: ConnectedMarker) {
        write { prefs ->
            prefs.removeMarker()
            marker.profileId?.let { prefs[CONNECTED_PROFILE] = it }
            prefs[CONNECTED_SINCE] = marker.sinceMillis
            prefs[CONNECTED_PID] = marker.pid
            marker.bootCount?.let { prefs[CONNECTED_BOOT] = it }
        }
    }

    override suspend fun clearConnected() {
        write { it.removeMarker() }
    }

    override suspend fun connectedMarker(): ConnectedMarker? {
        val prefs = prefs.first()
        val since = prefs[CONNECTED_SINCE] ?: return null
        val pid = prefs[CONNECTED_PID] ?: return null
        return ConnectedMarker(prefs[CONNECTED_PROFILE], since, pid, prefs[CONNECTED_BOOT])
    }

    override suspend fun interrupted(value: Interruption) {
        write { prefs ->
            prefs.putInterruption(value)
            prefs.removeMarker()
        }
    }

    private fun MutablePreferences.putInterruption(value: Interruption?) {
        INTERRUPTION_KEYS.forEach { remove(it) }
        if (value == null) return
        value.profileId?.let { this[INTERRUPTED_PROFILE] = it }
        this[INTERRUPTED_SINCE] = value.connectedSinceMillis
        value.endedAtMillis?.let { this[INTERRUPTED_ENDED] = it }
        this[INTERRUPTED_REASON] = value.reason.key
        this[INTERRUPTED_DETECTED] = value.detectedAtMillis
        value.killSwitch?.let { this[INTERRUPTED_KILL_SWITCH] = it.key }
    }

    private fun MutablePreferences.removeMarker() {
        MARKER_KEYS.forEach { remove(it) }
    }

    private companion object {
        val KILL_SWITCH_ALWAYS_ON = booleanPreferencesKey("kill_switch_always_on")
        val KILL_SWITCH_BLOCKS = booleanPreferencesKey("kill_switch_blocks_without_vpn")
        val KILL_SWITCH_CHECKED = longPreferencesKey("kill_switch_checked_at")

        val INTERRUPTED_PROFILE = stringPreferencesKey("interrupted_profile_id")
        val INTERRUPTED_SINCE = longPreferencesKey("interrupted_connected_since")
        val INTERRUPTED_ENDED = longPreferencesKey("interrupted_ended_at")
        val INTERRUPTED_REASON = stringPreferencesKey("interrupted_reason")
        val INTERRUPTED_DETECTED = longPreferencesKey("interrupted_detected_at")
        val INTERRUPTED_KILL_SWITCH = stringPreferencesKey("interrupted_kill_switch")
        val INTERRUPTION_KEYS =
            listOf(
                INTERRUPTED_PROFILE,
                INTERRUPTED_SINCE,
                INTERRUPTED_ENDED,
                INTERRUPTED_REASON,
                INTERRUPTED_DETECTED,
                INTERRUPTED_KILL_SWITCH,
            )

        val CONNECTED_PROFILE = stringPreferencesKey("connected_profile_id")
        val CONNECTED_SINCE = longPreferencesKey("connected_since")
        val CONNECTED_PID = intPreferencesKey("connected_pid")
        val CONNECTED_BOOT = intPreferencesKey("connected_boot_count")
        val MARKER_KEYS = listOf(CONNECTED_PROFILE, CONNECTED_SINCE, CONNECTED_PID, CONNECTED_BOOT)
    }
}
