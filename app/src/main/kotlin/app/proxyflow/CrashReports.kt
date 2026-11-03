package app.proxyflow

import app.proxyflow.core.model.LogSanitizer
import java.util.Collections
import java.util.IdentityHashMap

/**
 * What Android's crash report gets when an exception reaches the top of a thread (M8, threat model I-3): the same
 * exception — its class, its stack trace, its causes and suppressed exceptions — with every message passed through
 * [LogSanitizer], so an address, a host name, a `user:password@` or a long token that some code put in a message
 * never reaches the report Android keeps on the phone or Google Play's crash reports. ProxyFlow's own messages hold
 * codes and fixed words only (Lint's ExceptionSecret); this covers a library's.
 *
 * What it can't change: Android writes the uncaught exception, message and all, to the phone's log before any app
 * handler runs (its own pre-handler), and a failure in native code is reported by Android without Java's help.
 */
internal object CrashReports {
    /** Puts [SanitizingHandler] in front of the handler Android installed, once per process. */
    fun install() {
        val current = Thread.getDefaultUncaughtExceptionHandler()
        if (current is SanitizingHandler) return
        Thread.setDefaultUncaughtExceptionHandler(SanitizingHandler(current))
    }

    /**
     * Passes on [error] with its messages sanitized. Whatever the exception holds or does, the handler Android
     * installed runs next with something: it is what ends the process and keeps the crash report.
     */
    class SanitizingHandler(private val next: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, error: Throwable) {
            val reported = try {
                reportOf(error)
            } catch (e: Throwable) {
                LAST_RESORT // not even a stand-in could be made (out of memory): one that says nothing
            }
            next?.uncaughtException(thread, reported)
        }
    }

    /** What Android's handler gets for [error]. */
    private fun reportOf(error: Throwable): Throwable {
        // Out of memory: copying could fail the same way, and Android's message for it names no data.
        if (error is OutOfMemoryError) return error
        return try {
            sanitized(error)
        } catch (e: Throwable) {
            // The exception could not even be copied (its own code failed): none of its text is passed on, and its
            // stack trace only if that can still be read.
            ReportedThrowable(error.javaClass.name, MESSAGE_WITHHELD, null).also { stand ->
                try {
                    stand.stackTrace = error.stackTrace
                } catch (e: Throwable) {
                    // the stand-in keeps its own
                }
            }
        }
    }

    /**
     * [error] with its messages sanitized: an exception of the same class when one can be made with only that message
     * (so the report still names it), otherwise a [ReportedThrowable] that prints the same class name; the same stack
     * traces; causes and suppressed exceptions likewise, each once.
     */
    fun sanitized(error: Throwable): Throwable = copy(error, Collections.newSetFromMap(IdentityHashMap()), depth = 0)

    private fun copy(error: Throwable, seen: MutableSet<Throwable>, depth: Int): Throwable {
        seen += error
        val message = error.message?.let(LogSanitizer::text)
        val cause = error.cause?.takeIf { it !in seen && depth < MAX_DEPTH }?.let { copy(it, seen, depth + 1) }
        val same = sameClass(error, message)
        val copy = if (same != null && (cause == null || runCatching { same.initCause(cause) }.isSuccess)) {
            same
        } else {
            ReportedThrowable(error.javaClass.name, message, cause)
        }
        copy.stackTrace = error.stackTrace
        for (suppressed in error.suppressed) {
            if (suppressed !in seen && depth < MAX_DEPTH) copy.addSuppressed(copy(suppressed, seen, depth + 1))
        }
        return copy
    }

    /** A new [error] of the same class with only [message], if the class has such a constructor and says no more. */
    private fun sameClass(error: Throwable, message: String?): Throwable? = try {
        val made = if (message == null) {
            error.javaClass.getConstructor().newInstance()
        } else {
            error.javaClass.getConstructor(String::class.java).newInstance(message)
        }
        // A class that builds its own message from its fields would say something else, or nothing: not used.
        made.takeIf { it.message == message && it.localizedMessage == message && it.cause == null }
    } catch (e: Exception) {
        null
    } catch (e: LinkageError) {
        null
    }

    /** Stands for an exception that could not be made again: prints as that exception, with its message sanitized. */
    class ReportedThrowable(private val className: String, message: String?, cause: Throwable?) :
        Throwable(message, cause, true, true) {
        override fun toString(): String = message?.let { "$className: $it" } ?: className
    }

    const val MESSAGE_WITHHELD = "[message withheld]"
    private const val MAX_DEPTH = 16

    /** Made with the handler, for when nothing else can be: names no class and holds no text. */
    private val LAST_RESORT = ReportedThrowable(Throwable::class.java.name, MESSAGE_WITHHELD, null)
}
