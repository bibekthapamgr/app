package app.proxyflow.lint

import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.intellij.psi.PsiType
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UParenthesizedExpression
import org.jetbrains.uast.UPolyadicExpression
import org.jetbrains.uast.UQualifiedReferenceExpression
import org.jetbrains.uast.USimpleNameReferenceExpression
import org.jetbrains.uast.util.isConstructorCall
import org.jetbrains.uast.visitor.AbstractUastVisitor

/**
 * Log statements carry no secrets or payloads (threat model §6 item 3, I-3; ADR-0012). A call that
 * writes to logcat — `android.util.Log`, the VPN layer's own log (`VpnLog.add`, and the session's
 * `log` function), `println` — may not pass:
 *
 * - a value whose name says it is a credential, a server or a destination (`password`, `username`,
 *   `secret`, `credential`, `host`, `server`, `endpoint`, `address`, `destination`, `sni`, `url`,
 *   `payload`), wherever it appears in the statement;
 * - bytes or characters (`ByteArray`, `CharArray`, `ByteBuffer`), the engine's secret, credentials
 *   read from storage or a profile being edited, wherever they appear;
 * - a whole profile, or any of its fields but its random `id` and its `protocol`;
 * - an exception itself, its message or its stack trace (they can carry any of the above); its
 *   class name (`e.javaClass.simpleName`) is fine.
 *
 * Codes, counts, states and ids are what logs hold.
 *
 * The same rules apply to an exception's message (M8, [EXCEPTION_ISSUE]): a Throwable constructed with one,
 * `error(…)`, and the lazy message of `require`, `check`, `requireNotNull` and `checkNotNull`. An uncaught
 * exception's message goes to Android's crash report and the phone's log; a cause passed on is the cause's own.
 */
class LogSecretDetector :
    Detector(),
    SourceCodeScanner {
    override fun getApplicableUastTypes(): List<Class<out UElement>> = listOf(UCallExpression::class.java)

    override fun createUastHandler(context: JavaContext): UElementHandler = object : UElementHandler() {
        override fun visitCallExpression(node: UCallExpression) {
            val message = exceptionMessage(context, node)
            if (message != null) {
                for (argument in message) {
                    val found = findSecret(argument) ?: continue
                    context.report(
                        EXCEPTION_ISSUE,
                        node,
                        context.getLocation(found.first),
                        "This exception's message may carry ${found.second}: say what failed with codes, counts and fixed words",
                    )
                    return
                }
                return
            }
            if (!writesToLog(node)) return
            for (argument in node.valueArguments) {
                val found = findSecret(argument) ?: continue
                context.report(
                    ISSUE,
                    node,
                    context.getLocation(found.first),
                    "This log statement may carry ${found.second}: log codes, counts, states and ids only",
                )
                return
            }
        }
    }

    /**
     * The arguments that make up an exception's message: those of a Throwable's constructor but its cause, `error`'s,
     * and the lazy message of the preconditions (not their condition). Null when [node] makes no exception message.
     */
    private fun exceptionMessage(context: JavaContext, node: UCallExpression): List<UExpression>? {
        if (node.isConstructorCall()) {
            val created = node.resolve()?.containingClass ?: return null
            if (!context.evaluator.inheritsFrom(created, "java.lang.Throwable", false)) return null
            return node.valueArguments.filterNot { it.getExpressionType()?.isThrowable() == true }
        }
        val name = node.methodName ?: return null
        val owner = node.resolve()?.containingClass?.qualifiedName ?: return null
        if (!owner.startsWith("kotlin.PreconditionsKt")) return null
        return when (name) {
            "error" -> node.valueArguments
            in PRECONDITIONS -> node.valueArguments.drop(1)
            else -> null
        }
    }

    private fun writesToLog(node: UCallExpression): Boolean {
        val name = node.methodName ?: node.methodIdentifier?.name ?: return false
        val owner = node.resolve()?.containingClass?.qualifiedName
        return when {
            owner == "android.util.Log" -> name in LOG_METHODS
            owner == "app.proxyflow.core.vpn.VpnLog" -> name == "add"
            owner == "kotlin.io.ConsoleKt" || owner == "java.io.PrintStream" -> name == "println" || name == "print"
            // VpnSession's lifecycle log is a function value: log("…").
            else -> node.methodIdentifier?.name == "log" || name == "log"
        }
    }

    /** The first part of [argument] that may be secret, and what it may be; null when the argument looks safe. */
    private fun findSecret(argument: UExpression): Pair<UElement, String>? {
        // What becomes text as a whole: the argument, or each part of a string template or concatenation.
        val values = mutableListOf<UExpression>()
        fun collect(expression: UExpression) {
            when (expression) {
                is UPolyadicExpression -> expression.operands.forEach(::collect)
                is UParenthesizedExpression -> collect(expression.expression)
                else -> values += expression
            }
        }
        collect(argument)
        for (value in values) {
            wholeValueProblem(value)?.let { return value to it }
        }
        var found: Pair<UElement, String>? = null
        argument.accept(
            object : AbstractUastVisitor() {
                override fun visitElement(node: UElement): Boolean = found != null

                override fun visitSimpleNameReferenceExpression(node: USimpleNameReferenceExpression): Boolean {
                    if (found == null) found = nameProblem(node.identifier)?.let { node to it } ?: typeProblem(node)?.let { node to it }
                    return super.visitSimpleNameReferenceExpression(node)
                }

                override fun visitQualifiedReferenceExpression(node: UQualifiedReferenceExpression): Boolean {
                    if (found == null) found = qualifiedProblem(node)?.let { node to it } ?: typeProblem(node)?.let { node to it }
                    return super.visitQualifiedReferenceExpression(node)
                }

                override fun visitCallExpression(node: UCallExpression): Boolean {
                    if (found == null) found = callProblem(node)?.let { node to it } ?: typeProblem(node)?.let { node to it }
                    return super.visitCallExpression(node)
                }
            },
        )
        return found
    }

    /** A whole object turned into text: a profile (every field, the server included) or an exception (its message). */
    private fun wholeValueProblem(value: UExpression): String? {
        val type = value.getExpressionType() ?: return null
        return when {
            type.isA(PROFILE) -> "a whole profile, its server included"
            type.isThrowable() -> "an exception's message"
            else -> null
        }
    }

    /** An identifier one of whose words names a credential, a server, a destination or a payload ("proxyHost", "user_name"). */
    private fun nameProblem(identifier: String): String? {
        val words = identifier.split(WORD_BOUNDARY).filter { it.isNotEmpty() }.map { it.lowercase().removeSuffix("s") }
        return if (words.any { it in DENIED_WORDS }) "\"$identifier\" (a credential, a server, a destination or a payload)" else null
    }

    private fun qualifiedProblem(node: UQualifiedReferenceExpression): String? {
        val selector = (node.selector as? USimpleNameReferenceExpression)?.identifier ?: return null
        val receiver = node.receiver.getExpressionType()
        return when {
            receiver != null && receiver.isA(PROFILE) && selector !in PROFILE_FIELDS_ALLOWED -> "a profile's \"$selector\""
            receiver != null && receiver.isThrowable() && selector in EXCEPTION_TEXT -> "an exception's message"
            else -> nameProblem(selector)
        }
    }

    private fun callProblem(node: UCallExpression): String? {
        val name = node.methodName ?: return null
        val receiver = node.receiverType
        return when {
            receiver != null && receiver.isThrowable() && name in EXCEPTION_CALLS -> "an exception's message or stack trace"
            else -> null
        }
    }

    /** Values that are secret or payload whatever they are called. */
    private fun typeProblem(node: UExpression): String? {
        val type = node.getExpressionType() ?: return null
        val text = type.canonicalText
        return when {
            text in DENIED_ARRAYS -> "bytes or characters ($text)"
            DENIED_TYPES.any { type.isA(it) } -> "a secret (${text.substringAfterLast('.')})"
            else -> null
        }
    }

    private fun PsiType.isThrowable(): Boolean = isA("java.lang.Throwable") || isA("kotlin.Throwable")

    private fun PsiType.isA(className: String): Boolean {
        if (canonicalText == className || canonicalText.startsWith("$className.")) return true
        return superTypes.any { it.isA(className) }
    }

    companion object {
        val ISSUE: Issue = Issue.create(
            id = "LogSecret",
            briefDescription = "A log statement may carry a secret or a payload",
            explanation = "Logcat is readable by anyone with the device on a USB cable, ends up in bug reports, and outlives " +
                "the session. ProxyFlow's logs hold codes, counts, states and ids only (threat model I-3, ADR-0012): never a " +
                "username or password, the engine's secret, a server's name or address, a destination, bytes apps sent, a " +
                "whole profile, or an exception's message or stack trace. Log the catalogue code or the exception's class name instead.",
            category = Category.SECURITY,
            priority = 9,
            severity = Severity.ERROR,
            implementation = Implementation(LogSecretDetector::class.java, Scope.JAVA_FILE_SCOPE),
        )

        val EXCEPTION_ISSUE: Issue = Issue.create(
            id = "ExceptionSecret",
            briefDescription = "An exception's message may carry a secret",
            explanation = "An exception's message ends up in Android's crash report and the phone's log when nothing " +
                "catches it, and Android writes it to the log before any app code runs (M8, threat model I-3). It holds " +
                "what failed in codes, counts and fixed words: never a username or password, the engine's secret, a " +
                "server's name or address, a destination, bytes apps sent, a whole profile, or another exception's message " +
                "(pass that exception on as the cause instead).",
            category = Category.SECURITY,
            priority = 9,
            severity = Severity.ERROR,
            implementation = Implementation(LogSecretDetector::class.java, Scope.JAVA_FILE_SCOPE),
        )

        private val PRECONDITIONS = setOf("require", "check", "requireNotNull", "checkNotNull")

        private val LOG_METHODS = setOf("v", "d", "i", "w", "e", "wtf", "println")

        /** Words of identifiers (compared without a plural "s"). */
        private val DENIED_WORDS = setOf(
            "password", "passwd", "username", "user", "secret", "credential", "host", "hostname", "server", "endpoint",
            "addres", "addresse",
            "address", "destination", "dst", "sni", "url", "uri", "payload", "packet",
        )

        /** Between the words of an identifier: before an upper-case letter, at an underscore or a digit run. */
        private val WORD_BOUNDARY = Regex("(?<=[a-z0-9])(?=[A-Z])|_+|(?<=[A-Za-z])(?=[0-9])")

        private const val PROFILE = "app.proxyflow.core.model.ProxyProfile"
        private val PROFILE_FIELDS_ALLOWED = setOf("id", "protocol")

        private val DENIED_TYPES = listOf(
            "app.proxyflow.core.engine.EngineSecret",
            "app.proxyflow.core.data.CredentialsRead",
            "app.proxyflow.core.model.ProxyProfileInput",
            "java.nio.ByteBuffer",
        )
        private val DENIED_ARRAYS = setOf("byte[]", "char[]")
        private val EXCEPTION_TEXT = setOf("message", "localizedMessage", "cause", "stackTrace")
        private val EXCEPTION_CALLS = setOf("toString", "stackTraceToString", "getMessage", "getLocalizedMessage", "printStackTrace")
    }
}
