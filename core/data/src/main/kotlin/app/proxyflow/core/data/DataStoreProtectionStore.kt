package app.proxyflow.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.proxyflow.core.model.ExitReason
import app.proxyflow.core.model.Interruption
import app.proxyflow.core.model.SystemKillSwitch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** [ProtectionStore] in its own Preferences DataStore file. */
class DataStoreProtectionStore(private val dataStore: DataStore<Preferences>) : ProtectionStore {
    override val killSwitch: Flow<SystemKillSwitch?> = dataStore.data.map { prefs ->
        val checkedAt = prefs[KILL_SWITCH_CHECKED] ?: return@map null
        SystemKillSwitch(
            alwaysOn = prefs[KILL_SWITCH_ALWAYS_ON] ?: false,
            blocksWithoutVpn = prefs[KILL_SWITCH_BLOCKS] ?: false,
            checkedAtMillis = checkedAt,
        )
    }.distinctUntilChanged()

    override suspend fun recordKillSwitch(value: SystemKillSwitch) {
        dataStore.edit {
            it[KILL_SWITCH_ALWAYS_ON] = value.alwaysOn
            it[KILL_SWITCH_BLOCKS] = value.blocksWithoutVpn
            it[KILL_SWITCH_CHECKED] = value.checkedAtMillis
        }
    }

    override val interruption: Flow<Interruption?> = dataStore.data.map { prefs ->
        val since = prefs[INTERRUPTED_SINCE] ?: return@map null
        Interruption(
            profileId = prefs[INTERRUPTED_PROFILE],
            connectedSinceMillis = since,
            endedAtMillis = prefs[INTERRUPTED_ENDED],
            reason = ExitReason.of(prefs[INTERRUPTED_REASON]),
            detectedAtMillis = prefs[INTERRUPTED_DETECTED] ?: since,
        )
    }.distinctUntilChanged()

    override suspend fun setInterruption(value: Interruption?) {
        dataStore.edit { prefs ->
            INTERRUPTION_KEYS.forEach { prefs.remove(it) }
            if (value == null) return@edit
            value.profileId?.let { prefs[INTERRUPTED_PROFILE] = it }
            prefs[INTERRUPTED_SINCE] = value.connectedSinceMillis
            value.endedAtMillis?.let { prefs[INTERRUPTED_ENDED] = it }
            prefs[INTERRUPTED_REASON] = value.reason.key
            prefs[INTERRUPTED_DETECTED] = value.detectedAtMillis
        }
    }

    override suspend fun markConnected(marker: ConnectedMarker) {
        dataStore.edit { prefs ->
            prefs.removeMarker()
            marker.profileId?.let { prefs[CONNECTED_PROFILE] = it }
            prefs[CONNECTED_SINCE] = marker.sinceMillis
            prefs[CONNECTED_PID] = marker.pid
            marker.bootCount?.let { prefs[CONNECTED_BOOT] = it }
        }
    }

    override suspend fun clearConnected() {
        dataStore.edit { it.removeMarker() }
    }

    override suspend fun takeConnected(): ConnectedMarker? {
        var taken: ConnectedMarker? = null
        dataStore.edit { prefs ->
            val since = prefs[CONNECTED_SINCE]
            val pid = prefs[CONNECTED_PID]
            if (since != null && pid != null) taken = ConnectedMarker(prefs[CONNECTED_PROFILE], since, pid, prefs[CONNECTED_BOOT])
            prefs.removeMarker()
        }
        return taken
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
        val INTERRUPTION_KEYS = listOf(INTERRUPTED_PROFILE, INTERRUPTED_SINCE, INTERRUPTED_ENDED, INTERRUPTED_REASON, INTERRUPTED_DETECTED)

        val CONNECTED_PROFILE = stringPreferencesKey("connected_profile_id")
        val CONNECTED_SINCE = longPreferencesKey("connected_since")
        val CONNECTED_PID = intPreferencesKey("connected_pid")
        val CONNECTED_BOOT = intPreferencesKey("connected_boot_count")
        val MARKER_KEYS = listOf(CONNECTED_PROFILE, CONNECTED_SINCE, CONNECTED_PID, CONNECTED_BOOT)
    }
}
