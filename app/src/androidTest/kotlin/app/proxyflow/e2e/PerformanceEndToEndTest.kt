package app.proxyflow.e2e

import android.app.KeyguardManager
import android.os.PowerManager
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
import java.io.File
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
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
 * none goes around it), throughput and the CPU it costs ProxyFlow's process, memory with 200 connections open — idle,
 * and with data in flight on each — and CPU while connected and idle with the screen off. Traffic comes from the shell,
 * which the VPN covers ([FromShell]); the CPU and memory read are this process's, where the engine runs.
 *
 * Each measurement is one logcat line, "PF-PERF <name> key=value …", which scripts/m10_performance.py reports per API
 * level, checks for consistent units and compares with the quality bars; each is logged before the test asserts
 * anything about it, so a failure still shows its figures. An emulator on a CI runner is not a phone: its CPU is the
 * runner's, shared with the lab's proxies, and its network the emulator's own, so each throughput through ProxyFlow is
 * shown beside the same transfer by the same client straight through the proxy, without the VPN and the engine. These
 * are a baseline to catch regressions; the quality bars are a phone's (docs/m10/physical-phone-checklist.md).
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

    /** The threads that ran shell clients for this test: [tearDown] ends them before it disconnects. */
    private val clientThreads = CopyOnWriteArrayList<Thread>()

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
            // JUnit interrupts a test that ran out of time and comes here at once, while the test's thread may still be in
            // a shell client: every client ends before the disconnect, as one after it would look a lab name up outside
            // the VPN (Shell.run starts none in an interrupted thread).
            for (client in clientThreads) if (client !== Thread.currentThread()) client.interrupt()
            Shell.stopClients()
            for (client in clientThreads) if (client !== Thread.currentThread()) client.join(CLIENT_JOIN_MS)
            Shell.stopClients()
            if (::gate.isInitialized) runCatching { gate.forward(env.socksPort) }
            wakeAndUnlock()
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
            setUpTimes(protocol, "direct_to_proxy") { shell("connect", env.proxyHost, port, PROBE_TIMEOUT_MS) }
            app.connect(profile.id)
            setUpTimes(protocol, "vpn") { shell("connect", env.destinationName, env.destinationPort, PROBE_TIMEOUT_MS) }
            app.disconnectAndSettle()
        }
    }

    private fun setUpTimes(protocol: String, path: String, connect: () -> String) {
        val results = (1..SETUPS).map { connect() }
        val times = results.filter { it.startsWith("CONNECTED ") }.map { it.split(" ")[1].toLong() }
        perf(
            "setup",
            "protocol" to protocol,
            "path" to path,
            "n" to times.size,
            "failed" to results.size - times.size,
            "p50_ms" to if (times.isEmpty()) "?" else percentile(times, 50),
            "p90_ms" to if (times.isEmpty()) "?" else percentile(times, 90),
            "max_ms" to (times.maxOrNull() ?: "?"),
        )
        assertEquals("every connection ($protocol, $path) opened: $results", results.size, times.size)
    }

    /**
     * Quality bar: once the proxy dies, new connections are refused (RST) in under 1 s, and none goes around it. Through
     * the lab's gate in front of Dante: connected, then the gate stops listening, as a proxy whose port is shut; the next
     * [PROBES] connections to the destination are timed, with the session's state at each, and none may open — one that
     * did would have gone around the proxy. A few failures in a row make the session reconnect (the escalation rule), and
     * from then on new connections are held, not refused, until it gets through: the bar is the connections' while the
     * session was still connected. Then a proxy that accepts and never answers: its connection waits for the proxy's
     * handshake timeout (10 s, engine/adapters/dialer.go), then is refused.
     */
    @Test(timeout = 300_000)
    fun aDeadProxysConnectionsAreRefusedQuickly() {
        val profile = app.save("Gate", ProxyProtocol.SOCKS5, env.proxyHost, lab.gatePort, env.socksUser, env.socksPassword)
        app.connect(profile.id)
        val open = shell("connect", env.destinationName, env.destinationPort, PROBE_TIMEOUT_MS)
        assertTrue("through a working proxy, a connection opens: $open", open.startsWith("CONNECTED "))

        gate.refuse()
        val refused = (1..PROBES).map {
            val state = app.controller.state.value.status
            shell("connect", env.destinationName, env.destinationPort, PROBE_TIMEOUT_MS) to state
        }
        val connectedTimes = refused.filter { (result, state) -> state == ConnectionStatus.Connected && result.startsWith("FAILED ") }
            .map { it.first.split(" ")[1].toLong() }
        perf(
            "fail_closed",
            "proxy" to "refusing",
            "first_ms" to refused.first().first.takeIf { it.startsWith("FAILED ") }?.split(" ")?.get(1),
            "connected_probes" to connectedTimes.size,
            "worst_connected_ms" to connectedTimes.maxOrNull(),
            "opened" to refused.count { it.first.startsWith("CONNECTED ") },
            "results" to refused.joinToString(",") { (result, state) -> result.replace(' ', ':') + ":" + state },
            "status" to app.controller.state.value.status,
        )
        assertTrue("with the proxy refusing, no connection opens: $refused", refused.none { it.first.startsWith("CONNECTED ") })
        assertTrue("the first connection fails, timed: $refused", refused.first().first.startsWith("FAILED "))

        gate.forward(env.socksPort)
        app.disconnectAndSettle()
        app.connect(profile.id)
        gate.silent()
        val held = shell("connect", env.destinationName, env.destinationPort, SILENT_PROBE_TIMEOUT_MS)
        perf(
            "fail_closed",
            "proxy" to "silent",
            "first_ms" to held.takeIf { it.startsWith("FAILED ") }?.split(" ")?.get(1),
            "opened" to if (held.startsWith("CONNECTED ")) 1 else 0,
            "results" to held.replace(' ', ':'),
            "status" to app.controller.state.value.status,
        )
        assertTrue("with the proxy never answering, the connection fails in the end: $held", held.startsWith("FAILED "))
    }

    /**
     * Quality bar: at least 100 Mbit/s down through a local test proxy, under 50 % of one core at 50 Mbit/s. Each protocol
     * carries [BULK_BYTES] each way to the lab's bulk server, [TRANSFERS] times by each path, the two paths in turn and the
     * other way round each time: from the shell through ProxyFlow, and the same shell client straight through the proxy
     * (signing in and asking for the bulk server by name itself, while ProxyFlow is not connected) — the path's own speed.
     * Each transfer is timed without its set-up (the lookup, the connection, the proxy's handshake, the request), which
     * is reported apart; the median rate of each is reported, with every one. The CPU is this process's (the engine's and
     * the app's) over the transfers through ProxyFlow, per gigabyte the engine carried meanwhile (other apps' traffic
     * included); the share of one core at 50 Mbit/s follows from it, and is also measured: an upload paced at 50 Mbit/s.
     */
    @Test(timeout = 900_000)
    fun throughputAndItsCpu() {
        for ((protocol, profile) in listOf("socks5" to socks(), "http" to http())) {
            val byPath = mapOf(PROXY_ONLY to Transfers(), VPN to Transfers())
            for (round in 1..TRANSFERS) {
                for (path in if (round % 2 == 1) listOf(PROXY_ONLY, VPN) else listOf(VPN, PROXY_ONLY)) {
                    if (path == VPN) app.connect(profile.id) else app.disconnectAndSettle()
                    for (direction in DIRECTIONS) {
                        val cpuBefore = cpuMillis()
                        val before = if (path == VPN) ProxyEngine().stats() else null
                        val result = if (path == VPN) {
                            shell("rate", env.destinationName, lab.bulkPort, direction, BULK_BYTES, RATE_TIMEOUT_MS)
                        } else {
                            shell("rate", env.destinationName, lab.bulkPort, direction, BULK_BYTES, RATE_TIMEOUT_MS, "via", protocol)
                        }
                        val cpu = cpuMillis() - cpuBefore
                        val after = if (before != null) ProxyEngine().stats() else null
                        val carried = if (before != null && after != null) carriedBy(before, after) else null
                        byPath.getValue(path).add(direction, result, cpu, carried)
                    }
                }
            }
            app.disconnectAndSettle()
            for (direction in DIRECTIONS) {
                for (path in listOf(PROXY_ONLY, VPN)) byPath.getValue(path).report(protocol, direction, path)
            }
            for (path in listOf(PROXY_ONLY, VPN)) byPath.getValue(path).assertAll(protocol, path)
            app.connect(profile.id)
            pacedAt50Mbit(protocol)
            app.disconnectAndSettle()
        }
    }

    /** The bytes the engine carried between two of its readings, both ways: the shell's transfer and other apps' traffic. */
    private fun carriedBy(before: EngineStats, after: EngineStats): Long =
        (after.bytesUp + after.bytesDown) - (before.bytesUp + before.bytesDown)

    /** ProxyFlow's CPU while it carries 50 Mbit/s, measured: an upload paced at that rate for [PACED_S]. */
    private fun pacedAt50Mbit(protocol: String) {
        val cpuBefore = cpuMillis()
        val before = ProxyEngine().stats()
        val result = shell("pace", env.destinationName, lab.bulkPort, BYTES_PER_SECOND_AT_50_MBIT, PACED_S, RATE_TIMEOUT_MS)
        val cpu = cpuMillis() - cpuBefore
        val after = ProxyEngine().stats()
        val paced = Regex("^PACED (\\d+) (\\d+)$").find(result)
        val bytes = paced?.groupValues?.get(1)?.toLong()
        val ms = paced?.groupValues?.get(2)?.toLong()
        perf(
            "cpu_at_50mbit",
            "protocol" to protocol,
            "direction" to "up",
            "bytes" to bytes,
            "ms" to ms,
            "mbit_s" to if (bytes != null && ms != null) mbits(bytes, ms) else null,
            "cpu_ms" to cpu,
            "cpu_pct" to ms?.let { "%.1f".format(Locale.ROOT, cpu * 100.0 / it.coerceAtLeast(1)) },
            "engine_bytes" to carriedBy(before, after),
        )
        assertTrue("the paced upload through $protocol: $result", paced != null)
    }

    /** One path's transfers, each direction's: the rate of each (without its set-up), the set-up, and ProxyFlow's CPU. */
    private inner class Transfers {
        private val each = mutableMapOf<String, MutableList<Transfer>>()
        private val results = mutableListOf<String>()
        private val cpu = mutableMapOf<String, Long>()
        private val carried = mutableMapOf<String, Long>()

        fun add(direction: String, result: String, cpuMillis: Long, engineBytes: Long?) {
            results += result
            Transfer.parse(result)?.let { each.getOrPut(direction) { mutableListOf() } += it }
            cpu[direction] = (cpu[direction] ?: 0) + cpuMillis
            if (engineBytes != null) carried[direction] = (carried[direction] ?: 0) + engineBytes
        }

        fun report(protocol: String, direction: String, path: String) {
            val transfers = each[direction].orEmpty()
            val median = transfers.sortedBy { it.mbits }.getOrNull((transfers.size - 1) / 2)
            val engineBytes = carried[direction]
            val cpuMillis = cpu[direction] ?: 0
            val perGb = engineBytes?.takeIf { it > 0 }?.let { cpuMillis / 1000.0 / (it / 1e9) }
            perf(
                "throughput",
                "protocol" to protocol,
                "direction" to direction,
                "path" to path,
                "bytes" to BULK_BYTES,
                "n" to transfers.size,
                "timed_bytes" to median?.timedBytes,
                "ms" to median?.ms,
                "mbit_s" to median?.let { mbits(it.timedBytes, it.ms) },
                "each_mbit_s" to transfers.joinToString(",") { mbits(it.timedBytes, it.ms) },
                "setup_ms" to transfers.map { it.setupMs }.takeIf { it.isNotEmpty() }?.let { percentile(it, 50) },
                *if (path == VPN) {
                    arrayOf(
                        "cpu_ms" to cpuMillis,
                        "engine_bytes" to engineBytes,
                        "cpu_s_per_gb" to perGb?.let { "%.2f".format(Locale.ROOT, it) },
                        "cpu_pct_at_50mbit" to perGb?.let { "%.1f".format(Locale.ROOT, it * GB_PER_SECOND_AT_50_MBIT * 100) },
                    )
                } else {
                    emptyArray()
                },
            )
        }

        fun assertAll(protocol: String, path: String) {
            assertEquals("every transfer ($protocol, $path) ran: $results", TRANSFERS * DIRECTIONS.size, each.values.sumOf { it.size })
        }
    }

    /** "RATE timed ms SETUP ms" (ShellClient): the bytes the time covers, the time, and the set-up before it. */
    private data class Transfer(val timedBytes: Long, val ms: Long, val setupMs: Long) {
        val mbits: Double get() = timedBytes * 8.0 / 1000.0 / ms.coerceAtLeast(1)

        companion object {
            fun parse(result: String): Transfer? = Regex("^RATE (\\d+) (\\d+) SETUP (\\d+)$").find(result)?.groupValues?.let {
                Transfer(it[1].toLong(), it[2].toLong(), it[3].toLong())
            }
        }
    }

    /**
     * Quality bar: engine memory under 50 MB (10^6 bytes) at 200 concurrent connections. The engine runs in this process,
     * so beside the process's resident memory (the app's and the test's as well) the Go runtime's own accounts are read:
     * what it holds from Android, its heap and its goroutines' stacks — idle while connected, then with [MANY] connections
     * held open through SOCKS5 all at once: first idle, then each with [LOAD_BYTES] in flight, sent by the bulk server and
     * left unread behind a small receive buffer, so they wait along the way, ProxyFlow's own buffers full among them (64
     * to 128 KiB of the netstack's and 64 KiB of the relay's a connection, engine/tunnel). Once all are closed the engine
     * must be back to what it was: no goroutine left behind.
     */
    @Test(timeout = 480_000)
    fun memoryWithTwoHundredConnections() {
        val disconnected = rssKib()
        app.connect(socks().id)
        Thread.sleep(SETTLE_MS)
        val idleRss = rssKib()
        val idle = ProxyEngine().stats()
        for (traffic in listOf("idle", "in_flight")) holdMany(traffic, disconnected, idleRss, idle)
    }

    private fun holdMany(traffic: String, disconnected: Long, idleRss: Long, idle: EngineStats) {
        val load: List<Any> = if (traffic == "in_flight") listOf("load", LOAD_BYTES) else emptyList()
        var held = ""
        val holder = thread(name = "many-$traffic") {
            held = runCatching { shell("many", env.destinationName, lab.bulkPort, MANY, HOLD_S, *load.toTypedArray()) }
                .getOrElse { "ERROR $it" }
        }
        clientThreads += holder
        var peakRss = idleRss
        var engine = idle
        eventually("$MANY connections open at once", MANY_OPEN_MS, diagnose = { "engine: ${ProxyEngine().stats()}; shell: $held" }) {
            peakRss = maxOf(peakRss, rssKib())
            engine = ProxyEngine().stats()
            engine.tcpActive >= MANY
        }
        if (load.isNotEmpty()) {
            // What the engine handed on to the connections, which read none of it: their buffers and the netstack's full.
            eventually("data in flight on every connection", MANY_OPEN_MS, diagnose = { "engine: ${ProxyEngine().stats()}" }) {
                ProxyEngine().stats().bytesDown - idle.bytesDown >= MANY * IN_FLIGHT_MIN_BYTES
            }
        }
        var most: EngineStats = engine
        repeat(SAMPLES) {
            Thread.sleep(1_000)
            peakRss = maxOf(peakRss, rssKib())
            val now = ProxyEngine().stats()
            if (now.tcpActive >= MANY && now.goHeldKib >= most.goHeldKib) most = now
        }
        holder.join()
        // Other apps' connections may come and go meanwhile: what remains open is allowed for, by the flows still tracked.
        var closed = ProxyEngine().stats()
        eventually("the $MANY connections closed", CLOSE_MS, diagnose = { "engine: $closed" }) {
            ProxyEngine().stats().also { closed = it }.tcpActive <= idle.tcpActive + OTHER_FLOWS
        }
        val allowed = idle.goroutines + GOROUTINES_PER_FLOW * (closed.flows - idle.flows).coerceAtLeast(0) + GOROUTINE_SLACK
        perf(
            "memory",
            "traffic" to traffic,
            "connections" to most.tcpActive,
            "load_bytes" to (load.lastOrNull() ?: 0),
            "rss_disconnected_kib" to disconnected,
            "rss_idle_kib" to idleRss,
            "rss_peak_kib" to peakRss,
            "go_held_idle_kib" to idle.goHeldKib,
            "go_held_kib" to most.goHeldKib,
            "go_heap_kib" to most.heapInuseKib,
            "go_stacks_kib" to most.stackInuseKib,
            "goroutines_idle" to idle.goroutines,
            "goroutines" to most.goroutines,
            "goroutines_closed" to closed.goroutines,
            "opening_ms" to held.substringAfter(" IN ", "?").substringBefore(' '),
        )
        assertTrue("every connection opened: $held", held.startsWith("HELD $MANY OF $MANY FAILED 0 "))
        if (load.isNotEmpty()) assertTrue("every connection read its $LOAD_BYTES bytes: $held", " DRAINED $MANY" in held)
        assertTrue(
            "goroutines left behind: ${closed.goroutines} once all closed, ${idle.goroutines} before; at most $allowed",
            closed.goroutines <= allowed,
        )
    }

    /**
     * Quality bar: under 2 % battery an hour above the phone's own, connected and idle with the screen off — battery only
     * a phone can show. The emulator gives what drives it: this process's CPU over [IDLE_MS] with the screen off (checked
     * off before and after), the traffic other apps sent through the VPN meanwhile, and the engine's goroutines before and
     * after: none may pile up — at most [GOROUTINES_PER_FLOW] for each flow more than before (a connection's handler and
     * its two copy loops, engine/tunnel/tcp.go), and [GOROUTINE_SLACK] for the Go runtime's own, which it starts on demand.
     */
    @Test(timeout = 600_000)
    fun idleWhileConnected() {
        app.connect(socks().id)
        Thread.sleep(SETTLE_MS)
        val before = ProxyEngine().stats()
        val power = app.context.getSystemService(PowerManager::class.java)
        Shell.run("input keyevent KEYCODE_SLEEP")
        try {
            // The power key's press, should the sleep key not have done it.
            if (runCatching { eventually("the screen off", SCREEN_MS) { !power.isInteractive } }.isFailure) {
                Shell.run("input keyevent KEYCODE_POWER")
            }
            eventually("the screen off", SCREEN_MS, diagnose = { Shell.script("dumpsys power | grep -i -E 'wakefulness|display'") }) {
                !power.isInteractive
            }
            val cpuBefore = cpuMillis()
            val start = SystemClock.elapsedRealtime()
            Thread.sleep(IDLE_MS)
            val cpu = cpuMillis() - cpuBefore
            val millis = SystemClock.elapsedRealtime() - start
            val screenOff = !power.isInteractive
            val after = ProxyEngine().stats()
            val status = app.controller.state.value.status
            val allowed = before.goroutines + GOROUTINES_PER_FLOW * (after.flows - before.flows).coerceAtLeast(0) + GOROUTINE_SLACK
            perf(
                "idle",
                "seconds" to millis / 1000,
                "cpu_ms" to cpu,
                "cpu_pct" to "%.3f".format(Locale.ROOT, cpu * 100.0 / millis),
                "cpu_s_per_hour" to "%.1f".format(Locale.ROOT, cpu / 1000.0 * 3_600_000 / millis),
                "bytes" to (after.bytesUp + after.bytesDown) - (before.bytesUp + before.bytesDown),
                "screen_off" to screenOff,
                "goroutines_before" to before.goroutines,
                "goroutines_after" to after.goroutines,
                "flows_before" to before.flows,
                "flows_after" to after.flows,
            )
            assertEquals("still connected after $millis ms idle, screen off", ConnectionStatus.Connected, status)
            assertTrue("the screen stayed off for the $millis ms", screenOff)
            assertTrue(
                "goroutines piled up: ${before.goroutines} before, ${after.goroutines} after; flows ${before.flows} → ${after.flows}; at most $allowed",
                after.goroutines <= allowed,
            )
        } finally {
            wakeAndUnlock()
        }
    }

    private fun wakeAndUnlock() {
        val power = app.context.getSystemService(PowerManager::class.java)
        val keyguard = app.context.getSystemService(KeyguardManager::class.java)
        if (power.isInteractive && !keyguard.isKeyguardLocked) return
        Shell.run("input keyevent KEYCODE_WAKEUP")
        Shell.run("wm dismiss-keyguard")
        val keyguardState = { Shell.script("dumpsys window | grep -i keyguard") }
        eventually("the phone unlocked", diagnose = keyguardState) { !keyguard.isKeyguardLocked }
    }

    private fun socks(): ProxyProfile =
        app.save("Perf SOCKS5", ProxyProtocol.SOCKS5, env.proxyHost, env.socksPort, env.socksUser, env.socksPassword)

    private fun http(): ProxyProfile =
        app.save("Perf HTTP", ProxyProtocol.HTTP, env.proxyHost, env.httpPort, env.httpUser, env.httpPassword)

    /** A shell client's one line ([FromShell]), its thread kept for [tearDown]. */
    private fun shell(vararg args: Any): String {
        clientThreads.addIfAbsent(Thread.currentThread())
        return FromShell.run(*args)
    }

    private fun perf(name: String, vararg values: Pair<String, Any?>) {
        Log.i(PERF_TAG, "PF-PERF $name " + values.joinToString(" ") { (key, value) -> "$key=${value ?: "?"}" })
    }

    private companion object {
        const val PERF_TAG = "ProxyFlowPerf"
        const val CONNECTS = 20

        /**
         * Attempts per protocol the emulator's own uplink may fail and still leave the figures standing: in one run
         * (41822664621) API 33's mobile data failed a fourth, where three were allowed. Each is left out and counted
         * (`uplink_drops`).
         */
        const val UPLINK_DROPS = 8
        const val SETUPS = 20
        const val PROBES = 5
        const val PROBE_TIMEOUT_MS = 5_000
        const val SILENT_PROBE_TIMEOUT_MS = 30_000
        const val BULK_BYTES = 16L * 1024 * 1024
        const val TRANSFERS = 3
        const val RATE_TIMEOUT_MS = 120_000
        const val PROXY_ONLY = "proxy_only"
        const val VPN = "vpn"
        val DIRECTIONS = listOf("down", "up")

        /** 50 Mbit/s is 6.25 MB a second, so 0.00625 GB. */
        const val BYTES_PER_SECOND_AT_50_MBIT = 6_250_000L
        const val GB_PER_SECOND_AT_50_MBIT = 0.00625

        /** The paced upload's length: 62.5 MB at 50 Mbit/s, inside the bulk server's limit of 64 MiB a request. */
        const val PACED_S = 10
        const val MANY = 200
        const val HOLD_S = 60
        const val LOAD_BYTES = 256L * 1024
        const val IN_FLIGHT_MIN_BYTES = 32L * 1024
        const val MANY_OPEN_MS = 90_000L
        const val CLOSE_MS = 30_000L

        /** Connections other apps may have open through the VPN when the test's own have closed. */
        const val OTHER_FLOWS = 5
        const val SAMPLES = 5
        const val GOROUTINES_PER_FLOW = 3
        const val GOROUTINE_SLACK = 20
        const val IDLE_MS = 5 * 60_000L
        const val SCREEN_MS = 10_000L
        const val SETTLE_MS = 3_000L
        const val CLIENT_JOIN_MS = 15_000L

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

        /** Megabits (10^6 bits) a second. */
        fun mbits(bytes: Long, millis: Long): String = "%.1f".format(Locale.ROOT, bytes * 8.0 / 1000.0 / millis.coerceAtLeast(1))
    }
}
