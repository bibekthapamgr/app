package app.proxyflow.core.engine

import app.proxyflow.core.model.SecretText
import java.io.Closeable

/**
 * A proxy username and password on their way into the engine, in the byte
 * form the engine reads (adapters.ParseCredentials, the same layout as
 * core/data's CredentialCodec version 1): a version byte, then the username
 * and the password, each as a 2-byte big-endian length and UTF-8.
 *
 * The bytes are handed to the engine once and zeroed right after, by the
 * engine and by [ProxyEngine]; [close] zeroes them too. They never become a
 * `String` and never appear in [toString].
 */
class EngineSecret private constructor(bytes: ByteArray) : Closeable {
    private var data: ByteArray? = bytes

    /** Hands the bytes over once; later calls get an empty array (no sign-in). */
    internal fun take(): ByteArray {
        val out = data ?: ByteArray(0)
        data = null
        return out
    }

    override fun close() {
        data?.fill(0)
        data = null
    }

    override fun toString() = "EngineSecret(<redacted>)"

    companion object {
        private const val VERSION: Byte = 1
        private const val MAX_FIELD_BYTES = 0xFFFF

        /**
         * Encodes [username] and [password] without making `String` copies; the
         * caller still wipes its own arrays. An empty username means no sign-in.
         *
         * @throws IllegalArgumentException for broken text (half of a surrogate pair) or over-long fields.
         */
        fun of(username: CharArray, password: CharArray): EngineSecret {
            val user = SecretText.utf8(username)
            val pass = try {
                SecretText.utf8(password)
            } catch (e: IllegalArgumentException) {
                user.fill(0)
                throw e
            }
            try {
                require(user.size <= MAX_FIELD_BYTES && pass.size <= MAX_FIELD_BYTES) { "credential too long" }
                val out = ByteArray(1 + 2 + user.size + 2 + pass.size)
                out[0] = VERSION
                var at = putField(out, 1, user)
                at = putField(out, at, pass)
                check(at == out.size)
                return EngineSecret(out)
            } finally {
                user.fill(0)
                pass.fill(0)
            }
        }

        private fun putField(out: ByteArray, at: Int, value: ByteArray): Int {
            out[at] = (value.size ushr 8).toByte()
            out[at + 1] = value.size.toByte()
            value.copyInto(out, at + 2)
            return at + 2 + value.size
        }
    }
}
