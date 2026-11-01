package app.proxyflow.e2e

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import app.proxyflow.core.model.RoutingDiagnostics
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.UUID
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The M6 part of the lab on the CI runner (scripts/e2e_env.sh), read from the same file as
 * [E2eConfig]: TLS fronts, the gate, the destinations by address, over IPv6 and on the LAN
 * address, the HTTPS destination, the UDP sinks, and the lab's CA certificate (pushed beside it).
 */
data class LabConfig(
    val proxyHost: String,
    val proxyName: String,
    val tlsHttpPort: Int,
    val tlsSocksPort: Int,
    val tlsOtherPort: Int,
    val tlsExpiredPort: Int,
    val tlsUntrustedPort: Int,
    val gatePort: Int,
    val gateControlPort: Int,
    val addressDestination: String,
    val addressPort: Int,
    val v6Address: String,
    val v6Name: String,
    val v6Port: Int,
    val lanAddress: String,
    val lanPort: Int,
    val missingName: String,
    val httpsName: String,
    val httpsPort: Int,
    val httpsSha256: String,
    val udpPort: Int,
    val caPath: String,
) {
    /** The lab's CA (scripts/e2e_pki.py), for [TestTrust]. */
    fun ca(): X509Certificate {
        val pem = Shell.run("cat $caPath")
        check("BEGIN CERTIFICATE" in pem) { "no CA certificate at $caPath" }
        return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(pem.toByteArray())) as X509Certificate
    }

    companion object {
        fun load(): LabConfig {
            val values = E2eConfig.properties()
            fun value(key: String) = checkNotNull(values[key]) { "the lab's properties have no $key" }
            fun port(key: String) = value(key).toInt()
            return LabConfig(
                proxyHost = value("proxy.host"),
                proxyName = value("proxy.name"),
                tlsHttpPort = port("tls.http.port"),
                tlsSocksPort = port("tls.socks.port"),
                tlsOtherPort = port("tls.other.port"),
                tlsExpiredPort = port("tls.expired.port"),
                tlsUntrustedPort = port("tls.untrusted.port"),
                gatePort = port("gate.port"),
                gateControlPort = port("gate.control.port"),
                addressDestination = value("address.destination"),
                addressPort = port("address.port"),
                v6Address = value("v6.address"),
                v6Name = value("v6.name"),
                v6Port = port("v6.port"),
                lanAddress = value("lan.address"),
                lanPort = port("lan.port"),
                missingName = value("missing.name"),
                httpsName = value("https.name"),
                httpsPort = port("https.port"),
                httpsSha256 = value("https.sha256"),
                udpPort = port("udp.port"),
                caPath = value("ca.path"),
            )
        }
    }
}

/** A DNS answer as [ShellClient] printed it: the response code and the addresses (or "typeN" for other records). */
data class DnsAnswer(val rcode: Int, val answers: List<String>) {
    companion object {
        const val A = 1
        const val AAAA = 28
        const val TXT = 16
        const val HTTPS = 65
        const val NOERROR = 0
        const val REFUSED = 5

        fun parse(line: String): DnsAnswer {
            val match = Regex("^RCODE (\\d+) ANSWERS (\\S+)$").find(line) ?: throw AssertionError("not a DNS answer: $line")
            val answers = match.groupValues[2].takeIf { it != "-" }?.split(',').orEmpty()
            return DnsAnswer(match.groupValues[1].toInt(), answers.map(::normalized))
        }

        /** Addresses in one spelling, whatever the platform printed (fd99:0:0:0:0:0:0:1 or fd99::1). */
        fun normalized(address: String): String =
            if (address.startsWith("type")) address else InetAddress.getByName(address).hostAddress.orEmpty()
    }
}

/**
 * Runs app/src/androidTest/java/.../ShellClient.java as the shell user, whose traffic Android routes
 * into ProxyFlow's VPN, with app_process and this test APK as its class path (a copy the shell can
 * read in /data/local/tmp). One line of output per call.
 */
object ShellClient {
    private const val APK = "/data/local/tmp/pf-client.apk"

    private val installed: Boolean by lazy {
        val source = InstrumentationRegistry.getInstrumentation().context.packageCodePath
        Shell.run("cp $source $APK")
        Shell.run("chmod 644 $APK")
        check(Shell.run("ls -l $APK").contains("pf-client.apk")) { "could not copy the test APK for the shell" }
        true
    }

    fun run(vararg args: Any): String {
        check(installed)
        val command = "export CLASSPATH=$APK;exec app_process /system/bin app.proxyflow.e2e.ShellClient " + args.joinToString(" ")
        val output = Shell.script(command).lines().map { it.trim() }
        return output.lastOrNull { it.isNotEmpty() } ?: "ERROR no output"
    }

    fun tcp(host: String, port: Int, line: String, timeoutMillis: Int = 10_000): String = run("tcp", host, port, line, timeoutMillis)

    fun udp(host: String, port: Int, timeoutMillis: Int = 3_000): String = run("udp", host, port, timeoutMillis)

    fun dns(server: String, name: String, type: Int, overTcp: Boolean = false): DnsAnswer =
        DnsAnswer.parse(run("dns", server, name, type, 4_000, if (overTcp) "tcp" else ""))

    /** The system resolver's answer for [name], as an app gets it (InetAddress.getAllByName). */
    fun resolve(name: String): List<String> {
        val line = run("resolve", name)
        check(line.startsWith("ADDRESSES ")) { "resolving $name: $line" }
        return line.removePrefix("ADDRESSES ").split(',').map(DnsAnswer::normalized)
    }

    fun tls(host: String, port: Int, caPath: String, line: String): String = run("tls", host, port, caPath, line, 10_000)
}

/**
 * The lab's gate (scripts/e2e_servers.py gate): a proxy the tests break while connected. The tests run in
 * ProxyFlow's own process, which its VPN leaves out, so its control port is reached directly.
 */
class Gate(private val lab: LabConfig) {
    fun forward(port: Int) = send("forward 127.0.0.1:$port")

    fun refuse() = send("refuse")

    fun silent() = send("silent")

    private fun send(command: String) {
        val answer = Socket().use { socket ->
            socket.connect(InetSocketAddress(lab.proxyHost, lab.gateControlPort), CONTROL_TIMEOUT_MS)
            socket.soTimeout = CONTROL_TIMEOUT_MS
            socket.getOutputStream().write("$command\n".toByteArray())
            socket.getInputStream().bufferedReader().readLine()
        }
        check(answer?.startsWith("ok") == true) { "the gate answered \"$answer\" to \"$command\"" }
        Log.i(LAB_TAG, "gate $command")
    }

    private companion object {
        const val CONTROL_TIMEOUT_MS = 5_000
    }
}

/** What Home shows under Diagnostics (ADR-0011), read through the app's own path; waits for the first reading. */
fun App.diagnostics(): RoutingDiagnostics = runBlocking { withTimeout(DIAGNOSTICS_WAIT_MS) { runtime.diagnostics.filterNotNull().first() } }

/** Waits until the diagnostics satisfy [condition]: the engine counts as connections end. Returns them. */
fun App.awaitDiagnostics(what: String, condition: (RoutingDiagnostics) -> Boolean): RoutingDiagnostics {
    var last: RoutingDiagnostics? = null
    eventually(what, timeoutMillis = DIAGNOSTICS_WAIT_MS, diagnose = { "diagnostics: $last" }) {
        diagnostics().also { last = it }.let(condition)
    }
    return checkNotNull(last)
}

/**
 * Lines the M6 tests send, logged for scripts/m6_evidence.py: "sent <line> to <host>:<port>" must arrive at
 * that destination exactly once, "refused <line> to <host>:<port>" nowhere at all.
 */
object Lines {
    fun next(label: String): String = "PF6-$label-" + UUID.randomUUID().toString().replace("-", "").take(12)

    fun sent(line: String, host: String, port: Int) = Log.i(LAB_TAG, "sent $line to ${endpoint(host, port)}")

    fun refused(line: String, host: String, port: Int) = Log.i(LAB_TAG, "refused $line to ${endpoint(host, port)}")

    private fun endpoint(host: String, port: Int) = if (':' in host) "[$host]:$port" else "$host:$port"
}

const val LAB_TAG = "ProxyFlowE2E"
private const val DIAGNOSTICS_WAIT_MS = 15_000L
