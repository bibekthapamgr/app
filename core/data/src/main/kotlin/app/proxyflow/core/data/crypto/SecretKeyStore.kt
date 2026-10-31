package app.proxyflow.core.data.crypto

import javax.crypto.SecretKey

/** Where the credential keys live. Production uses the Android Keystore; JVM tests use software keys. */
interface SecretKeyStore {
    /** A new AES-256 key stored under [alias], replacing any key already there. */
    fun create(alias: String): SecretKey

    /** The key stored under [alias], or null when there is none. */
    fun get(alias: String): SecretKey?

    /** Deletes the key under [alias]; nothing happens when there is none. */
    fun delete(alias: String)

    fun aliases(): Set<String>
}
