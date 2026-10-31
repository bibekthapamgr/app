package app.proxyflow.core.data.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * AES-256-GCM keys generated inside the Android Keystore (ADR-0006). The key
 * material never enters the app's memory and cannot be exported. Usable
 * without the user unlocking the phone (`setUserAuthenticationRequired(false)`),
 * because reconnects and Always-on start happen in the background; StrongBox is
 * not requested because its availability and speed vary between phones.
 */
class AndroidKeystoreSecretKeyStore : SecretKeyStore {
    private val keyStore: KeyStore by lazy { KeyStore.getInstance(PROVIDER).apply { load(null) } }

    @Synchronized
    override fun getOrCreate(alias: String): SecretKey = get(alias) ?: generate(alias)

    @Synchronized
    override fun get(alias: String): SecretKey? = keyStore.getKey(alias, null) as SecretKey?

    @Synchronized
    override fun delete(alias: String) {
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    @Synchronized
    override fun aliases(): Set<String> = keyStore.aliases().toList().toSet()

    private fun generate(alias: String): SecretKey {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).run {
            init(spec)
            generateKey()
        }
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val KEY_BITS = 256
    }
}
