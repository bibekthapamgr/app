package app.proxyflow.core.data.crypto

import app.proxyflow.core.model.SecretText

/**
 * The plaintext inside [SealedCredentials]: a version byte, then the username
 * and the password, each as a 2-byte length and UTF-8. Both travel together
 * because together they identify a paid proxy account (ADR-0006). Works on
 * `CharArray`/`ByteArray` and wipes its own temporary copies, so the only
 * `String` copies of a password are the ones the text field itself holds.
 */
object CredentialCodec {
    private const val VERSION: Byte = 1
    private const val MAX_FIELD_BYTES = 0xFFFF

    fun encode(username: CharArray, password: CharArray): ByteArray {
        var user: ByteArray? = null
        var pass: ByteArray? = null
        try {
            user = SecretText.utf8(username)
            pass = SecretText.utf8(password)
            require(user.size <= MAX_FIELD_BYTES && pass.size <= MAX_FIELD_BYTES) { "credential too long" }
            val out = ByteArray(1 + 2 + user.size + 2 + pass.size)
            out[0] = VERSION
            var at = putField(out, 1, user)
            at = putField(out, at, pass)
            check(at == out.size)
            return out
        } finally {
            user?.fill(0)
            pass?.fill(0)
        }
    }

    /** @throws IllegalArgumentException when [bytes] is not a credential encoded by [encode]. */
    fun decode(bytes: ByteArray): Pair<CharArray, CharArray> {
        require(bytes.isNotEmpty() && bytes[0] == VERSION) { "unknown credential encoding" }
        val (userStart, userLength) = field(bytes, 1)
        val (passStart, passLength) = field(bytes, userStart + userLength)
        require(passStart + passLength == bytes.size) { "trailing bytes" }
        val user = SecretText.chars(bytes, userStart, userLength)
        try {
            return user to SecretText.chars(bytes, passStart, passLength)
        } catch (e: Throwable) {
            user.fill('\u0000')
            throw e
        }
    }

    private fun putField(out: ByteArray, at: Int, value: ByteArray): Int {
        out[at] = (value.size ushr 8).toByte()
        out[at + 1] = value.size.toByte()
        value.copyInto(out, at + 2)
        return at + 2 + value.size
    }

    private fun field(bytes: ByteArray, at: Int): Pair<Int, Int> {
        require(at + 2 <= bytes.size) { "truncated" }
        val length = ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)
        require(at + 2 + length <= bytes.size) { "truncated" }
        return (at + 2) to length
    }
}
