package app.proxyflow.core.data.crypto

import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/**
 * A profile's username and password as stored: AES-256-GCM ciphertext (with its
 * authentication tag), the 12-byte IV, the format version, and the id of the
 * Keystore key they were sealed under (ADR-0006).
 */
class SealedCredentials(val formatVersion: Int, val keyId: String, val iv: ByteArray, val ciphertext: ByteArray) {
    override fun equals(other: Any?): Boolean = other is SealedCredentials &&
        formatVersion == other.formatVersion &&
        keyId == other.keyId &&
        iv.contentEquals(other.iv) &&
        ciphertext.contentEquals(other.ciphertext)

    override fun hashCode(): Int = 31 * (31 * (31 * formatVersion + keyId.hashCode()) + iv.contentHashCode()) + ciphertext.contentHashCode()

    override fun toString(): String = "SealedCredentials(v$formatVersion, ${ciphertext.size} bytes)"
}

/**
 * The stored credentials can't be decrypted. [permanent] is true when trying
 * again cannot help (the key is gone or invalidated, the data was changed or
 * belongs to another profile); otherwise the Keystore failed in a way that a
 * retry may get past (ADR-0006: retry once, then H3).
 */
class CredentialUnreadableException(val permanent: Boolean, message: String, cause: Throwable? = null) :
    GeneralSecurityException(message, cause)

/** Encrypts and decrypts profile credentials, each set under a key of its own. */
interface CredentialCipher {
    /**
     * Encrypts [plaintext] for [profileId] under a new key made for it alone, so
     * deleting that key later makes this ciphertext, and every copy of it, unreadable.
     */
    fun seal(profileId: String, plaintext: ByteArray): SealedCredentials

    /** @throws CredentialUnreadableException when [sealed] can't be decrypted for [profileId]. */
    fun open(profileId: String, sealed: SealedCredentials): ByteArray

    /** Deletes the key [keyId], so nothing ever sealed under it can be decrypted again. */
    fun deleteKey(keyId: String)

    /** The ids of every credential key in the key store, so keys no profile uses any more can be found. */
    fun keyIds(): Set<String>
}

/**
 * AES-256-GCM with a new key for every set of credentials it seals (ADR-0006).
 * The associated data binds each ciphertext to its profile and format, so a
 * ciphertext copied to another profile, or any changed byte, fails to decrypt
 * instead of yielding wrong credentials. Deleting a key makes every copy of
 * what was sealed under it permanently unreadable, wherever the bytes may
 * linger on flash storage.
 */
class AesGcmCredentialCipher(private val keys: SecretKeyStore, private val newKeyId: () -> String = { UUID.randomUUID().toString() }) :
    CredentialCipher {
    override fun seal(profileId: String, plaintext: ByteArray): SealedCredentials {
        val keyId = newKeyId()
        val alias = aliasFor(keyId)
        val key = keys.create(alias)
        try {
            // The key generates the IV (the Android Keystore requires randomized encryption).
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
            cipher.updateAAD(associatedData(profileId, FORMAT_VERSION))
            val ciphertext = cipher.doFinal(plaintext)
            val iv = cipher.iv
            check(iv != null && iv.size == IV_BYTES) { "unexpected IV" }
            return SealedCredentials(FORMAT_VERSION, keyId, iv, ciphertext)
        } catch (e: Exception) {
            // Nothing refers to the new key yet.
            runCatching { keys.delete(alias) }
            throw e
        }
    }

    override fun open(profileId: String, sealed: SealedCredentials): ByteArray {
        if (sealed.formatVersion != FORMAT_VERSION) {
            throw CredentialUnreadableException(permanent = true, "unknown format ${sealed.formatVersion}")
        }
        if (sealed.iv.size != IV_BYTES) throw CredentialUnreadableException(permanent = true, "bad IV length")
        val key = try {
            keys.get(aliasFor(sealed.keyId))
        } catch (e: UnrecoverableKeyException) {
            throw CredentialUnreadableException(permanent = true, "key unrecoverable", e)
        } catch (e: GeneralSecurityException) {
            throw CredentialUnreadableException(permanent = false, "key store failed", e)
        } catch (e: ProviderException) {
            // Some Keystore implementations report transient failures as ProviderException.
            throw CredentialUnreadableException(permanent = false, "key store failed", e)
        }
        if (key == null) throw CredentialUnreadableException(permanent = true, "no such key")
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed.iv))
            cipher.updateAAD(associatedData(profileId, sealed.formatVersion))
            cipher.doFinal(sealed.ciphertext)
        } catch (e: AEADBadTagException) {
            throw CredentialUnreadableException(permanent = true, "authentication failed", e)
        } catch (e: InvalidKeyException) {
            // Includes KeyPermanentlyInvalidatedException; Android also reports most other Keystore
            // failures at this point as InvalidKeyException. The cost of treating one of those as
            // permanent is that the user types the sign-in again, which seals it under a new key.
            throw CredentialUnreadableException(permanent = true, "key invalid", e)
        } catch (e: GeneralSecurityException) {
            throw CredentialUnreadableException(permanent = false, "decryption failed", e)
        } catch (e: ProviderException) {
            throw CredentialUnreadableException(permanent = false, "decryption failed", e)
        }
    }

    override fun deleteKey(keyId: String) = keys.delete(aliasFor(keyId))

    override fun keyIds(): Set<String> = keys.aliases().filter { it.startsWith(ALIAS_PREFIX) }.map { it.removePrefix(ALIAS_PREFIX) }.toSet()

    companion object {
        const val FORMAT_VERSION = 1
        const val ALIAS_PREFIX = "proxyflow_credentials_"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val IV_BYTES = 12

        /** The Keystore alias of key [keyId]. Database version 1 named each profile's only key after the profile. */
        fun aliasFor(keyId: String) = ALIAS_PREFIX + keyId

        private fun associatedData(profileId: String, version: Int) = "proxyflow/credentials/v$version/$profileId".encodeToByteArray()
    }
}
