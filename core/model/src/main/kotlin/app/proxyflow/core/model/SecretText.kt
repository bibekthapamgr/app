package app.proxyflow.core.model

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * UTF-8 conversions for secrets (proxy usernames and passwords) that leave no
 * stray copies: the output array is sized exactly before anything is written
 * into it, so no buffer is grown and dropped with part of a secret inside, and
 * the only scratch array is zeroed before returning. (The JDK's convenience
 * `CharsetEncoder.encode(CharBuffer)` guesses a size, and on non-ASCII text
 * outgrows it and abandons the first buffer unwiped.)
 */
object SecretText {
    /**
     * Encodes [chars] as UTF-8 into an array of exactly the right size.
     *
     * @throws IllegalArgumentException for broken text (half of a surrogate pair).
     */
    fun utf8(chars: CharArray): ByteArray {
        val out = ByteArray(utf8Length(chars))
        val encoder = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val dst = ByteBuffer.wrap(out)
        var result = encoder.encode(CharBuffer.wrap(chars), dst, true)
        if (result.isUnderflow) result = encoder.flush(dst)
        if (!result.isUnderflow || dst.hasRemaining()) {
            out.fill(0)
            throw IllegalArgumentException("not valid text")
        }
        return out
    }

    /**
     * Decodes [length] bytes of UTF-8 starting at [offset] of [bytes].
     *
     * @throws IllegalArgumentException when the bytes are not UTF-8.
     */
    fun chars(bytes: ByteArray, offset: Int, length: Int): CharArray {
        // UTF-8 never decodes to more UTF-16 chars than it has bytes.
        val scratch = CharArray(length)
        try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val dst = CharBuffer.wrap(scratch)
            var result = decoder.decode(ByteBuffer.wrap(bytes, offset, length), dst, true)
            if (result.isUnderflow) result = decoder.flush(dst)
            require(result.isUnderflow) { "not valid UTF-8" }
            return scratch.copyOf(dst.position())
        } finally {
            scratch.fill('\u0000')
        }
    }

    /** The UTF-8 length of [chars]; rejects unpaired surrogates. */
    private fun utf8Length(chars: CharArray): Int {
        var n = 0
        var i = 0
        while (i < chars.size) {
            val c = chars[i]
            n += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                Character.isHighSurrogate(c) -> {
                    require(i + 1 < chars.size && Character.isLowSurrogate(chars[i + 1])) { "not valid text" }
                    i++
                    4
                }
                Character.isLowSurrogate(c) -> throw IllegalArgumentException("not valid text")
                else -> 3
            }
            i++
        }
        return n
    }
}
