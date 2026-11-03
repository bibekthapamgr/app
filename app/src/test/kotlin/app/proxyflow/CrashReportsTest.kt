package app.proxyflow

import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The crash report's exception (M8, threat model I-3): what Android's report would print — the same classes and
 * stack traces — with no address, host name, sign-in or long token from any message, whatever the exception's class
 * does with its message.
 */
class CrashReportsTest {
    @Test
    fun theReportNamesTheSameExceptionWithItsMessageSanitized() {
        val original = IllegalStateException("cannot reach proxy.example.com:1080 as alice:hunter2-secret@proxy failed")

        val reported = CrashReports.sanitized(original)

        assertSame(IllegalStateException::class.java, reported.javaClass)
        assertNoSecret(reported)
        assertTrue("what failed is still said: ${reported.message}", reported.message!!.startsWith("cannot reach"))
        assertEquals(original.stackTrace.toList(), reported.stackTrace.toList())
    }

    @Test
    fun causesAndSuppressedExceptionsAreSanitizedToo() {
        val cause = IOException("connect to 203.0.113.7:3128 refused")
        val original = RuntimeException("check failed for http://user:pass@203.0.113.7/", cause)
        original.addSuppressed(IllegalArgumentException("token abcdefghijklmnopqrstuvwxyz0123456789"))

        val reported = CrashReports.sanitized(original)

        assertSame(IOException::class.java, reported.cause?.javaClass)
        assertEquals(cause.stackTrace.toList(), reported.cause!!.stackTrace.toList())
        assertEquals(1, reported.suppressed.size)
        assertNoSecret(reported)
    }

    @Test
    fun anExceptionThatCantBeMadeAgainIsReportedUnderItsName() {
        val original = NoMessageConstructor("socks.example.net:1080", IllegalStateException("bad proxy.example.org"))

        val reported = CrashReports.sanitized(original)

        assertTrue(reported is CrashReports.ReportedThrowable)
        assertTrue(reported.toString(), reported.toString().startsWith(NoMessageConstructor::class.java.name + ": "))
        assertSame("its cause is kept", IllegalStateException::class.java, reported.cause?.javaClass)
        assertNoSecret(reported)
    }

    @Test
    fun aClassThatMakesItsOwnMessageIsNotTrusted() {
        val reported = CrashReports.sanitized(OwnMessage("vpn.example.com"))

        assertTrue(reported is CrashReports.ReportedThrowable)
        assertNoSecret(reported)
    }

    @Test
    fun aCycleOfCausesEnds() {
        val first = IllegalStateException("first")
        val second = IllegalStateException("second", first)
        first.initCause(second)

        val reported = CrashReports.sanitized(first)

        assertEquals("first", reported.message)
        assertEquals("second", reported.cause?.message)
    }

    @Test
    fun theHandlerPassesTheSanitizedExceptionOn() {
        val passed = mutableListOf<Throwable>()
        val handler = CrashReports.SanitizingHandler { _, e -> passed += e }

        handler.uncaughtException(Thread.currentThread(), IllegalStateException("user alice:hunter2-secret@proxy.example.com"))

        assertNoSecret(passed.single())
    }

    @Test
    fun anExceptionWhoseMessageFailsIsReportedWithoutIt() {
        val passed = mutableListOf<Throwable>()
        val handler = CrashReports.SanitizingHandler { _, e -> passed += e }

        handler.uncaughtException(Thread.currentThread(), FailingMessage())

        val reported = passed.single()
        assertEquals(CrashReports.MESSAGE_WITHHELD, reported.message)
        assertTrue(reported.toString().startsWith(FailingMessage::class.java.name))
    }

    @Test
    fun outOfMemoryIsPassedAsItIs() {
        val passed = mutableListOf<Throwable>()
        val error = OutOfMemoryError("Failed to allocate a 16 byte allocation")

        CrashReports.SanitizingHandler { _, e -> passed += e }.uncaughtException(Thread.currentThread(), error)

        assertSame(error, passed.single())
    }

    @Test
    fun installingTwiceKeepsOneHandler() {
        val before = Thread.getDefaultUncaughtExceptionHandler()
        try {
            CrashReports.install()
            val installed = Thread.getDefaultUncaughtExceptionHandler()
            CrashReports.install()
            assertSame(installed, Thread.getDefaultUncaughtExceptionHandler())
            assertTrue(installed is CrashReports.SanitizingHandler)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before)
        }
    }

    private fun assertNoSecret(reported: Throwable) {
        val text = StringWriter().also { reported.printStackTrace(PrintWriter(it)) }.toString()
        for (secret in SECRETS) assertFalse("\"$secret\" in the report:\n$text", text.contains(secret))
    }

    /** Only a constructor with a cause: it can't be made again from a message alone. */
    class NoMessageConstructor(server: String, cause: Throwable) : RuntimeException("no route to $server", cause)

    /** Makes its message from a field. */
    class OwnMessage(private val host: String) : RuntimeException() {
        constructor() : this("")
        override val message: String get() = "cannot reach $host"
    }

    class FailingMessage : RuntimeException() {
        override val message: String get() = throw IllegalStateException("no message")
    }

    private companion object {
        val SECRETS = listOf(
            "proxy.example.com", "hunter2", "alice", "203.0.113.7", "user:pass", "abcdefghijklmnopqrstuvwxyz",
            "socks.example.net", "proxy.example.org", "vpn.example.com",
        )
    }
}
