package app.proxyflow.lint

import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestMode
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue

/** What the LogSecret check reports, and what it lets through (threat model §6 item 3). */
class LogSecretDetectorTest : LintDetectorTest() {
    override fun getDetector(): Detector = LogSecretDetector()

    override fun getIssues(): List<Issue> = listOf(LogSecretDetector.ISSUE)

    fun testCodesCountsStatesAndIdsAreFine() {
        check(
            """
            package test
            import android.util.Log
            import app.proxyflow.core.model.ProxyProfile
            import app.proxyflow.core.vpn.VpnLog
            class Session(private val log: (String) -> Unit, private val vpnLog: VpnLog) {
                fun f(profile: ProxyProfile, code: String, attempt: Int, e: Exception) {
                    log("connect ${'$'}{profile.id} ${'$'}{profile.protocol}")
                    log("reconnect attempt ${'$'}attempt failed: ${'$'}code")
                    vpnLog.add("unexpected ${'$'}{e.javaClass.simpleName}")
                    Log.i("ProxyFlowVpn", "offline")
                    println("count ${'$'}attempt")
                }
            }
            """,
        ).expectClean()
    }

    fun testCredentialsAndServersAreErrors() {
        check(
            """
            package test
            import app.proxyflow.core.vpn.VpnLog
            class Form(private val vpnLog: VpnLog) {
                fun f(username: String, password: String, host: String) {
                    vpnLog.add("signing in as " + username)
                    vpnLog.add("password ${'$'}password")
                    vpnLog.add("to ${'$'}{host.lowercase()}")
                }
            }
            """,
        ).expectErrorCount(3)
    }

    fun testAProfileOnlyItsIdAndProtocol() {
        check(
            """
            package test
            import app.proxyflow.core.model.ProxyProfile
            class Session(private val log: (String) -> Unit) {
                fun f(profile: ProxyProfile) {
                    log("profile ${'$'}profile")
                    log("proxy ${'$'}{profile.port}")
                    log("called ${'$'}{profile.name}")
                }
            }
            """,
        ).expectErrorCount(3)
    }

    fun testSecretsBytesAndExceptionMessagesAreErrors() {
        check(
            """
            package test
            import android.util.Log
            import app.proxyflow.core.engine.EngineSecret
            class Engine {
                fun f(secret: EngineSecret, packet: ByteArray, e: Exception) {
                    Log.i("T", "secret ${'$'}secret")
                    println(String(packet))
                    Log.e("T", "failed: ${'$'}{e.message}")
                    Log.w("T", "failed: " + e)
                    Log.d("T", e.stackTraceToString())
                }
            }
            """,
        ).expectErrorCount(5)
    }

    fun testOtherFunctionsAreNotLogs() {
        check(
            """
            package test
            class Ui {
                fun show(text: String) = Unit
                fun f(password: String, host: String) {
                    show(password)
                    show("host ${'$'}host")
                }
            }
            """,
        ).expectClean()
    }

    private fun check(source: String) = lint()
        .files(kotlin(source).indented(), *STUBS)
        .issues(LogSecretDetector.ISSUE)
        .allowMissingSdk()
        .testModes(TestMode.DEFAULT)
        .run()

    private companion object {
        val STUBS: Array<TestFile> = arrayOf(
            java(
                """
                package android.util;
                public final class Log {
                    public static int v(String tag, String msg) { return 0; }
                    public static int d(String tag, String msg) { return 0; }
                    public static int i(String tag, String msg) { return 0; }
                    public static int w(String tag, String msg) { return 0; }
                    public static int e(String tag, String msg) { return 0; }
                }
                """,
            ).indented(),
            kotlin(
                """
                package app.proxyflow.core.vpn
                class VpnLog { fun add(message: String) = Unit }
                """,
            ).indented(),
            kotlin(
                """
                package app.proxyflow.core.model
                enum class ProxyProtocol { SOCKS5, HTTP }
                class ProxyProfile(val id: String, val name: String, val protocol: ProxyProtocol, val host: String, val port: Int)
                """,
            ).indented(),
            kotlin(
                """
                package app.proxyflow.core.engine
                class EngineSecret
                """,
            ).indented(),
        )
    }
}
