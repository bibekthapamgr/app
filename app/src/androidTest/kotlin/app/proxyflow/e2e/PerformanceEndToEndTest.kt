package app.proxyflow.e2e

import android.app.KeyguardManager
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.proxyflow.core.engine.EngineStats
import app.proxyflow.core.engine.ProxyEngine
import app.proxyflow.core.model.ConnectionStatus
import app.proxyflow.core.model.ProxyProfile
import app.proxyflow.core.model.ProxyProtocol
import app.proxyflow.core.vpn.RetryPolicy
import java.io.DataInputStream
import java.io.File
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.ceil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M10's performance baseline, measured on each emulator against the V1 quality bars (docs/m1/01-scope.md §7): the time
 * to Connected, a new connection's set-up through the proxy, how fast a dead proxy's connections are refused (and that
 * none goes around it), throughput and the CPU it costs ProxyFlow's process, memory with 200 connections open, and CPU
 * while connected and idle with the screen off. Traffic comes from the shell, which the VPN covers ([FromShell]); the
 * CPU and memory read are this process's, where the engine runs.
 *
 * Each measurement is one logcat line, "PF-PERF <name> key=value …", which scripts/m10_performance.py reports per API
 * level and compares with the quality bars. An emulator on a CI runner is not a phone: its CPU is the runner's, shared
 * with the lab's proxies, and its network the emulator's own, so each throughput through ProxyFlow is shown beside the
 * same transfer from this process straight through the proxy, without the VPN and the engine. These are a baseline to
 * catch regressions; the quality bars are a phone's (docs/m10/physical-phone-checklist.md).
 *
 * Every connection goes through a proxy, so the M5–M7 evidence still holds: the destination's and the bulk server's
 * connections each match a proxy's tunnel (scripts/m5_evidence.py, m7_evidence.py).
 */
@RunWith(AndroidJUnit4::class)
class PerformanceEndToEndTest {
    private val app = App()
    private lateinit var env: E2eConfig
    private lateinit var lab: LabConfig
    private lateinit var gate: Gate

    @Before
    fun setUp() {
        env = E2eConfig.load()
        lab = LabConfig.load()
        gate = Gate(lab)
        gate.forward(env.socksPort)
        TestTrust.labCa = null
        TestRetry.policy = RetryPolicy()
        app.setVpnConsent(allowed = true)
        app.allowNotifications()
        app.disconnectAndSettle(timeoutMillis = SETTLE_BEFORE_TEST_MS)
        app.deleteAllProfiles()
    }

    @After
    fun tearDown() {
        try {
            if (::gate.isInitialized) runCatching { gate.forward(env.socksPort) }
        } finally {
            app.disconnectAndSettle()
            app.deleteAllProfiles()
        }
    }

    /**
     * Quality bar: Connected within 3 s at p90, warm, on a good network. From the tap (the controller's connect) to
     * Connected: the proxy's pre-flight check, the VPN interface, the engine, and Android routing apps into the VPN and
     * finding it working (ADR-0010). Each protocol connects once to warm up, then [CONNECTS] times; an attempt the
     * emulator's own uplink failed ([UPLINK_DROPPED], see [App.connect]) is left out and counted.
     */
    @Test(timeout = 600_000)
    fun timeToConnected() {
        for ((protocol, profile) in listOf("socks5" to socks(), "http" to http())) {
            app.connect(profile.id)
            app.disconnectAndSettle()
            val times = mutableListOf<Long>()
            var uplinkDrops = 0
            while (times.size < CONNECTS) {
                val start = SystemClock.elapsedRealtime()
                val ended = app.connectAndAwait(profile.id, "Connected or a failed attempt") {
                    it.status == ConnectionStatus.Connected ||
                        (it.status == ConnectionStatus.Disconnected || it.status == ConnectionStatus.Blocked) && it.error != null
                }
                val millis = SystemClock.elapsedRealtime() - start
                if (ended.status != ConnectionStatus.Connected && ended.error == UPLINK_DROPPED && uplinkDrops < UPLINK_DROPS) {
                    uplinkDrops++
                    app.disconnectAndSettle()
                    app.awaitProxy(env.proxyHost, if (protocol == "socks5") env.socksPort else env.httpPort)
                    continue
                }
                assertEquals("connect ${times.size + 1} through $protocol: $ended", ConnectionStatus.Connected, ended.status)
                times += millis
                app.disconnectAndSettle()
            }
            perf(
                "connect",
                "protocol" to protocol,
                "n" to times.size,
                "p50_ms" to percentile(times, 50),
                "p90_ms" to percentile(times, 90),
                "max_ms" to times.max(),
                "uplink_drops" to uplinkDrops,
            )
        }
    }

    /**
     * Latency: a new connection's set-up through each proxy. ProxyFlow answers the app's SYN only once the proxy's tunnel
     * is open (ADR-0002), so this is the round trips to the proxy and the proxy's own connection to the destination.
     * Beside it, the same client's TCP connection straight to the proxy, before connecting: what the phone's uplink
     * itself takes for one.
     */
    @Test(timeout = 300_000)
    fun connectionSetUpThroughTheProxy() {
        for ((protocol, profile) in listOf("socks5" to socks(), "http" to http())) {
            val port = if (protocol == "socks5") env.socksPort else env.httpPort
            setUpTimes(protocol, "direct_to_proxy") { FromShell.run("connect", env.proxyHost, port, PROBE_TIMEOUT_MS) }
            app.connect(profile.id)
            setUpTimes(protocol, "vpn") { FromShell.run("connect", env.destinationName, env.destinationPort, PROBE_TIMEOUT_MS) }
            app.disconnectAndSettle()
        }
    }

    private fun setUpTimes(protocol: String, path: String, connect: () -> String) {
        val results = (1..SETUPS).map { connect() }
        assertTrue("every connection ($protocol, $path) opened: $results", results.all { it.startsWith("CONNECTED ") })
        val times = results.map { it.split(" ")[1].toLong() }
        perf(
            "setup",
            "protocol" to protocol,
            "path" to path,
            "n" to times.size,
            "p50_ms" to percentile(times, 50),
            "p90_ms" to percentile(times, 90),
            "max_ms" to times.max(),
        )
    }

    /**
     * Quality bar: once the proxy dies, new connections are refused (RST) in under 1 s, and none goes around it. Through
     * the lab's gate in front of Dante: connected, then the gate stops listening, as a proxy whose port is shut; the next
     * [PROBES] connections to the destination are timed, and none may open — one that did would have gone around the
     * proxy. Then a proxy that accepts and never answers: its connection waits for the proxy's handshake timeout (10 s,
     * engine/adapters/dialer.go), then is refused.
     */
    @Test(timeout = 300_000)
    fun aDeadProxysConnectionsAreRefusedQuickly() {
        val profile = app.save("Gate", ProxyProtocol.SOCKS5, env.proxyHost, lab.gatePort, env.socksUser, env.socksPassword)
        app.connect(profile.id)
        val open = FromShell.run("connect", env.destinationName, env.destinationPort, PROBE_TIMEOUT_MS)
        assertTrue("through a working proxy, a connection opens: $open", open.startsWith("CONNECTED "))

        gate.refuse()
        val refused = (1..PROBES).map { FromShell.run("connect", env.destinationName, env.destinationPort, PROBE_TIMEOUT_MS) }
        assertTrue("with the proxy refusing, no connection opens: $refused", refused.none { it.startsWith("CONNECTED ") })
        assertTrue("the first connection fails, timed: $refused", refused.first().startsWith("FAILED "))
        perf(
            "fail_closed",
            "proxy" to "refusing",
            "first_ms" to refused.first().split(" ")[1],
            "opened" to refused.count { it.startsWith("CONNECTED ") },
            "results" to refused.joinToString(",") { it.replace(' ', ':') },
            "status" to app.controller.state.value.status,
        )

        gate.forward(env.socksPort)
        app.disconnectAndSettle()
        app.connect(profile.id)
        gate.silent()
        val held = FromShell.run("connect", env.destinationName, env.destinationPort, SILENT_PROBE_TIMEOUT_MS)
        assertTrue("with the proxy never answering, the connection fails in the end: $held", held.startsWith("FAILED "))
        perf(
            "fail_closed",
            "proxy" to "silent",
            "first_ms" to held.split(" ")[1],
            "opened" to 0,
            "results" to held.replace(' ', ':'),
            "status" to app.controller.state.value.status,
        )
    }

    /**
     * Quality bar: at least 100 Mbit/s down through a local test proxy, under 50 % of one core at 50 Mbit/s. Each protocol
     * carries [BULK_BYTES] each way to the lab's bulk server, [TRANSFERS] times: first from this process straight through
     * the proxy (the path's own speed, without ProxyFlow), then from the shell through ProxyFlow; the median time of each
     * is reported, with every one. The CPU is this process's (the engine's and the app's) over the transfers through
     * ProxyFlow; the share of one core at 50 Mbit/s follows from the CPU seconds per gigabyte.
     */
    @Test(timeout = 480_000)
    fun throughputAndItsCpu() {
        for ((protocol, profile) in listOf("socks5" to socks(), "http" to http())) {
            for (direction in listOf("down", "up")) {
                val times = (1..TRANSFERS).map { proxyOnly(protocol, direction, BULK_BYTES) }
                perf(
                    "throughput",
                    "protocol" to protocol,
                    "direction" to direction,
                    "path" to "proxy_only",
                    "bytes" to BULK_BYTES,
                    "ms" to percentile(times, 50),
                    "mbit_s" to mbits(BULK_BYTES, percentile(times, 50)),
                    "each_ms" to times.joinToString(","),
                )
            }
            app.connect(profile.id)
            for (direction in listOf("down", "up")) {
                val cpuBefore = cpuMillis()
                val times = (1..TRANSFERS).map {
                    val result = FromShell.run("rate", env.destinationName, lab.bulkPort, direction, BULK_BYTES, RATE_TIMEOUT_MS)
                    assertTrue("$direction through $protocol: $result", result.startsWith("RATE "))
                    result.split(" ")[2].toLong()
                }
                val cpu = cpuMillis() - cpuBefore
                val cpuSecondsPerGb = cpu / 1000.0 / (TRANSFERS * BULK_BYTES / 1e9)
                perf(
                    "throughput",
                    "protocol" to protocol,
                    "direction" to direction,
                    "path" to "vpn",
                    "bytes" to BULK_BYTES,
                    "ms" to percentile(times, 50),
                    "mbit_s" to mbits(BULK_BYTES, percentile(times, 50)),
                    "each_ms" to times.joinToString(","),
                    "cpu_ms" to cpu,
                    "cpu_s_per_gb" to "%.2f".format(Locale.ROOT, cpuSecondsPerGb),
                    "cpu_pct_at_50mbit" to "%.1f".format(Locale.ROOT, cpuSecondsPerGb * GB_PER_SECOND_AT_50_MBIT * 100),
                )
            }
            app.disconnectAndSettle()
        }
    }

    /**
     * Quality bar: engine memory under 50 MB at 200 concurrent connections. The engine runs in this process, so beside the
     * process's resident memory (the app's and the test's as well) the Go runtime's own accounts are read: what it holds
     * from Android, its heap and its goroutines' stacks — idle while connected, and with [MANY] connections held open
     * through SOCKS5, all at once.
     */
    @Test(timeout = 300_000)
    fun memoryWithTwoHundredConnections() {
        val disconnected = rssKib()
        app.connect(socks().id)
        Thread.sleep(SETTLE_MS)
        val idleRss = rssKib()
        val idle = ProxyEngine().stats()
        var held = ""
        val holder = thread(name = "many") {
            held = runCatching { FromShell.run("many", env.destinationName, lab.bulkPort, MANY, HOLD_S) }.getOrElse { "ERROR $it" }
        }
        var peakRss = idleRss
        var engine = idle
        eventually("$MANY connections open at once", MANY_OPEN_MS, diagnose = { "engine: ${ProxyEngine().stats()}; shell: $held" }) {
            peakRss = maxOf(peakRss, rssKib())
            engine = ProxyEngine().stats()
            engine.tcpActive >= MANY
        }
        var most: EngineStats = engine
        repeat(SAMPLES) {
            Thread.sleep(1_000)
            peakRss = maxOf(peakRss, rssKib())
            val now = ProxyEngine().stats()
            if (now.tcpActive >= MANY && now.goHeldKib >= most.goHeldKib) most = now
        }
        holder.join()
        assertTrue("every connection opened: $held", held.startsWith("HELD $MANY OF $MANY FAILED 0 "))
        perf(
            "memory",
            "connections" to most.tcpActive,
            "rss_disconnected_kib" to disconnected,
            "rss_idle_kib" to idleRss,
            "rss_peak_kib" to peakRss,
            "go_held_idle_kib" to idle.goHeldKib,
            "go_held_kib" to most.goHeldKib,
            "go_heap_kib" to most.heapInuseKib,
            "go_stacks_kib" to most.stackInuseKib,
            "goroutines_idle" to idle.goroutines,
            "goroutines" to most.goroutines,
            "opening_ms" to held.substringAfter(" IN ").substringBefore(' '),
        )
    }

    /**
     * Quality bar: under 2 % battery an hour above the phone's own, connected and idle with the screen off — battery only
     * a phone can show. The emulator gives what drives it: this process's CPU over [IDLE_MS] with the screen off, the
     * traffic other apps sent through the VPN meanwhile, and the engine's goroutines before and after (none may pile up).
     */
    @Test(timeout = 600_000)
    fun idleWhileConnected() {
        app.connect(socks().id)
        Thread.sleep(SETTLE_MS)
        val before = ProxyEngine().stats()
        Shell.run("input keyevent KEYCODE_SLEEP")
        try {
            val cpuBefore = cpuMillis()
            val start = SystemClock.elapsedRealtime()
            Thread.sleep(IDLE_MS)
            val cpu = cpuMillis() - cpuBefore
            val millis = SystemClock.elapsedRealtime() - start
            val after = ProxyEngine().stats()
            assertEquals("still connected after $millis ms idle, screen off", ConnectionStatus.Connected, app.controller.state.value.status)
            perf(
                "idle",
                "seconds" to millis / 1000,
                "cpu_ms" to cpu,
                "cpu_pct" to "%.3f".format(Locale.ROOT, cpu * 100.0 / millis),
                "cpu_s_per_hour" to "%.1f".format(Locale.ROOT, cpu / 1000.0 * 3_600_000 / millis),
                "bytes" to (after.bytesUp + after.bytesDown) - (before.bytesUp + before.bytesDown),
                "goroutines_before" to before.goroutines,
                "goroutines_after" to after.goroutines,
            )
        } finally {
            Shell.run("input keyevent KEYCODE_WAKEUP")
            Shell.run("wm dismiss-keyguard")
            val keyguard = app.context.getSystemService(KeyguardManager::class.java)
            val keyguardState = { Shell.script("dumpsys window | grep -i keyguard") }
            eventually("the phone unlocked", diagnose = keyguardState) { !keyguard.isKeyguardLocked }
        }
    }

    private fun socks(): ProxyProfile =
        app.save("Perf SOCKS5", ProxyProtocol.SOCKS5, env.proxyHost, env.socksPort, env.socksUser, env.socksPassword)

    private fun http(): ProxyProfile =
        app.save("Perf HTTP", ProxyProtocol.HTTP, env.proxyHost, env.httpPort, env.httpUser, env.httpPassword)

    /**
     * One transfer from this process straight through the proxy, which ProxyFlow's VPN leaves out (and here it is
     * disconnected anyway): the bulk server's protocol over a SOCKS5 or HTTP CONNECT tunnel; the time it took.
     */
    private fun proxyOnly(protocol: String, direction: String, bytes: Long): Long {
        val start = SystemClock.elapsedRealtime()
        Socket().use { socket ->
            socket.soTimeout = RATE_TIMEOUT_MS
            socket.connect(InetSocketAddress(env.proxyHost, if (protocol == "socks5") env.socksPort else env.httpPort), PROBE_TIMEOUT_MS)
            val output = socket.getOutputStream()
            val input = DataInputStream(socket.getInputStream())
            if (protocol == "socks5") socks5Tunnel(output, input) else httpTunnel(output, input)
            val chunk = ByteArray(CHUNK)
            if (direction == "down") {
                output.write("DOWN $bytes\n".toByteArray())
                var left = bytes
                while (left > 0) {
                    val n = input.read(chunk, 0, minOf(left, CHUNK.toLong()).toInt())
                    check(n > 0) { "the bulk server stopped after ${bytes - left} of $bytes bytes" }
                    left -= n
                }
            } else {
                output.write("UP $bytes\n".toByteArray())
                var left = bytes
                while (left > 0) {
                    val n = minOf(left, CHUNK.toLong()).toInt()
                    output.write(chunk, 0, n)
                    left -= n
                }
                val reply = input.bufferedReader().readLine()
                check(reply == "GOT $bytes") { "the bulk server answered $reply to $bytes bytes" }
            }
        }
        return SystemClock.elapsedRealtime() - start
    }

    /** RFC 1928 and 1929: sign in, then CONNECT to the bulk server by name, as ProxyFlow asks. */
    private fun socks5Tunnel(output: OutputStream, input: DataInputStream) {
        output.write(byteArrayOf(5, 1, 2))
        check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 2) { "Dante refused sign-in by name and password" }
        val user = env.socksUser.toByteArray()
        val password = env.socksPassword.toByteArray()
        output.write(byteArrayOf(1, user.size.toByte()) + user + byteArrayOf(password.size.toByte()) + password)
        check(input.readUnsignedByte() == 1 && input.readUnsignedByte() == 0) { "Dante refused the sign-in" }
        val name = env.destinationName.toByteArray()
        val port = lab.bulkPort
        output.write(byteArrayOf(5, 1, 0, 3, name.size.toByte()) + name + byteArrayOf((port shr 8).toByte(), port.toByte()))
        check(input.readUnsignedByte() == 5) { "not a SOCKS5 reply" }
        val reply = input.readUnsignedByte()
        check(reply == 0) { "Dante answered $reply to the CONNECT" }
        input.readUnsignedByte()
        when (val type = input.readUnsignedByte()) {
            1 -> input.skipBytes(4)
            4 -> input.skipBytes(16)
            3 -> input.skipBytes(input.readUnsignedByte())
            else -> error("SOCKS5 address type $type")
        }
        input.skipBytes(2)
    }

    /** An HTTP CONNECT to the bulk server by name, signed in (Basic), as ProxyFlow asks; the 200's headers read. */
    private fun httpTunnel(output: OutputStream, input: DataInputStream) {
        val target = "${env.destinationName}:${lab.bulkPort}"
        val credentials = Base64.getEncoder().encodeToString("${env.httpUser}:${env.httpPassword}".toByteArray())
        output.write("CONNECT $target HTTP/1.1\r\nHost: $target\r\nProxy-Authorization: Basic $credentials\r\n\r\n".toByteArray())
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) head.append(input.readUnsignedByte().toChar())
        check(head.startsWith("HTTP/1.1 200") || head.startsWith("HTTP/1.0 200")) { "Squid answered ${head.lineSequence().first()}" }
    }

    private fun perf(name: String, vararg values: Pair<String, Any?>) {
        Log.i(PERF_TAG, "PF-PERF $name " + values.joinToString(" ") { (key, value) -> "$key=$value" })
    }

    private companion object {
        const val PERF_TAG = "ProxyFlowPerf"
        const val CONNECTS = 20
        const val UPLINK_DROPS = 3
        const val SETUPS = 20
        const val PROBES = 5
        const val PROBE_TIMEOUT_MS = 5_000
        const val SILENT_PROBE_TIMEOUT_MS = 30_000
        const val BULK_BYTES = 16L * 1024 * 1024
        const val TRANSFERS = 3
        const val CHUNK = 64 * 1024
        const val RATE_TIMEOUT_MS = 120_000
        const val MANY = 200
        const val HOLD_S = 60
        const val MANY_OPEN_MS = 90_000L
        const val SAMPLES = 5
        const val IDLE_MS = 5 * 60_000L
        const val SETTLE_MS = 3_000L

        /** 50 Mbit/s is 6.25 MB, so 0.00625 GB, a second. */
        const val GB_PER_SECOND_AT_50_MBIT = 0.00625

        /** This process's CPU time, user and system, in milliseconds (proc(5): fields 14 and 15 of /proc/self/stat). */
        fun cpuMillis(): Long {
            // After the command name, which may hold spaces: the state is field 3, so field n is at index n - 3.
            val fields = File("/proc/self/stat").readText().substringAfterLast(") ").split(" ")
            val ticks = fields[11].toLong() + fields[12].toLong()
            return ticks * 1000 / Os.sysconf(OsConstants._SC_CLK_TCK)
        }

        /** This process's resident memory (VmRSS), in KiB. */
        fun rssKib(): Long = File("/proc/self/status").readLines().first { it.startsWith("VmRSS:") }.split(Regex("\\s+"))[1].toLong()

        /** The nearest-rank percentile: the smallest value with at least [p] % of them at or below it. */
        fun percentile(values: List<Long>, p: Int): Long {
            val sorted = values.sorted()
            return sorted[(ceil(p / 100.0 * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)]
        }

        fun mbits(bytes: Long, millis: Long): String = "%.1f".format(Locale.ROOT, bytes * 8.0 / 1000.0 / millis.coerceAtLeast(1))
    }
}
