package app.proxyflow.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import app.proxyflow.core.data.crypto.AesGcmCredentialCipher
import app.proxyflow.core.data.crypto.AndroidKeystoreSecretKeyStore
import app.proxyflow.core.data.crypto.SecretKeyStore
import app.proxyflow.core.data.db.ProxyFlowDatabase

/** Builds the app's profile storage, so no other module needs Room on its classpath. */
object ProfileStorage {
    /**
     * Profiles in the Room database in the app's private storage, credentials
     * encrypted under per-profile [keys] (the Android Keystore unless a JVM test
     * passes software keys), the chosen profile in the [settings] DataStore.
     */
    fun repository(
        context: Context,
        settings: DataStore<Preferences>,
        keys: SecretKeyStore = AndroidKeystoreSecretKeyStore(),
    ): DefaultProfileRepository = DefaultProfileRepository(
        store = RoomProfileStore(ProxyFlowDatabase.create(context).profileDao()),
        cipher = AesGcmCredentialCipher(keys),
        active = DataStoreActiveProfileStore(settings),
    )
}
