package app.proxyflow.core.data.crypto

import app.proxyflow.core.testing.SoftwareSecretKeyStore
import java.security.ProviderException
import javax.crypto.SecretKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-0006 evidence: AES-256-GCM with the profile id as associated data, one key
 * per profile, tamper detection. Runs on the JVM with software keys; the same
 * code with Android Keystore keys runs in the emulator tests (androidTest).
 */
class CredentialCipherTest {
    private val keys = SoftwareSecretKeyStore()
    private val cipher = AesGcmCredentialCipher(keys)
    private val secret = "user:s3cret".encodeToByteArray()

    @Test
    fun sealedCredentialsOpenOnlyForTheirOwnProfile() {
        val sealed = cipher.seal("p1", secret)
        assertArrayEquals(secret, cipher.open("p1", sealed))
        assertEquals(12, sealed.iv.size)
        assertEquals("ciphertext plus a 16-byte tag", secret.size + 16, sealed.ciphertext.size)
        assertFalse("never stored in clear", sealed.ciphertext.containsSlice(secret))

        cipher.seal("p2", "other".encodeToByteArray())
        val moved = assertThrows(CredentialUnreadableException::class.java) { cipher.open("p2", sealed) }
        assertTrue("copied to another profile: permanent", moved.permanent)
    }

    @Test
    fun everyProfileHasItsOwnKeyAndEverySealAFreshIv() {
        val first = cipher.seal("p1", secret)
        val second = cipher.seal("p1", secret)
        assertFalse("same plaintext, different IV", first.iv.contentEquals(second.iv))
        assertFalse(first.ciphertext.contentEquals(second.ciphertext))
        cipher.seal("p2", secret)
        assertEquals(setOf("p1", "p2"), cipher.profilesWithKeys())
        assertNotEquals(keys.get(AesGcmCredentialCipher.aliasFor("p1")), keys.get(AesGcmCredentialCipher.aliasFor("p2")))
    }

    @Test
    fun anyChangedByteIsDetected() {
        val sealed = cipher.seal("p1", secret)
        for (i in sealed.ciphertext.indices) {
            val changed = sealed.ciphertext.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            val e = assertThrows(CredentialUnreadableException::class.java) {
                cipher.open("p1", SealedCredentials(sealed.formatVersion, sealed.iv, changed))
            }
            assertTrue(e.permanent)
        }
        val otherIv = sealed.iv.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        assertThrows(CredentialUnreadableException::class.java) { cipher.open("p1", SealedCredentials(1, otherIv, sealed.ciphertext)) }
        assertThrows(CredentialUnreadableException::class.java) { cipher.open("p1", SealedCredentials(2, sealed.iv, sealed.ciphertext)) }
    }

    @Test
    fun deletingTheKeyMakesEveryOldCiphertextUnreadable() {
        val sealed = cipher.seal("p1", secret)
        cipher.deleteKey("p1")
        assertTrue(cipher.profilesWithKeys().isEmpty())
        val e = assertThrows(CredentialUnreadableException::class.java) { cipher.open("p1", sealed) }
        assertTrue(e.permanent)

        val again = cipher.seal("p1", secret)
        assertArrayEquals("a new key works for new credentials", secret, cipher.open("p1", again))
        assertThrows(CredentialUnreadableException::class.java) { cipher.open("p1", sealed) }
    }

    @Test
    fun aKeystoreHiccupIsReportedAsTemporary() {
        val flaky = object : SecretKeyStore by keys {
            override fun get(alias: String): SecretKey? = throw ProviderException("keystore busy")
        }
        val sealed = cipher.seal("p1", secret)
        val e = assertThrows(CredentialUnreadableException::class.java) { AesGcmCredentialCipher(flaky).open("p1", sealed) }
        assertFalse(e.permanent)
    }

    @Test
    fun sealedCredentialsNeverPrintTheirBytes() {
        val text = cipher.seal("p1", secret).toString()
        assertEquals("SealedCredentials(v1, ${secret.size + 16} bytes)", text)
    }

    private fun ByteArray.containsSlice(slice: ByteArray): Boolean =
        (0..size - slice.size).any { start -> slice.indices.all { this[start + it] == slice[it] } }
}
