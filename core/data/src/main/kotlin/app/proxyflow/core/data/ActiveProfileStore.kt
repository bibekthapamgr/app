package app.proxyflow.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Which profile the user chose to use (docs/m1/06-architecture.md: kept in DataStore, no secrets). */
interface ActiveProfileStore {
    val activeId: Flow<String?>

    suspend fun set(id: String?)
}

/** [ActiveProfileStore] in the settings DataStore file, next to the theme. */
class DataStoreActiveProfileStore(private val dataStore: DataStore<Preferences>) : ActiveProfileStore {
    override val activeId: Flow<String?> = dataStore.data.map { it[ACTIVE_PROFILE] }

    override suspend fun set(id: String?) {
        dataStore.edit { prefs ->
            if (id == null) prefs.remove(ACTIVE_PROFILE) else prefs[ACTIVE_PROFILE] = id
        }
    }

    private companion object {
        val ACTIVE_PROFILE = stringPreferencesKey("active_profile_id")
    }
}
