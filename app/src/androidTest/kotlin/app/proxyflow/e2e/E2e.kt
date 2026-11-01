package app.proxyflow.e2e

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.proxyflow.ProxyFlowApplication
import app.proxyflow.core.data.ConnectionController
import app.proxyflow.core.data.ProfileRepository
import app.proxyflow.core.engine.ProxyEngine
import app.proxyflow.core.model.ConnectionError
import app.proxyflow.core.model.ConnectionState
import app.proxyflow.core.model.ConnectionStatus
import app.proxyflow.core.model.ProxyProfile
import app.proxyflow.core.model.ProxyProfileInput
import app.proxyflow.core.model.ProxyProtocol
import app.proxyflow.core.vpn.ProxyFlowVpnService
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue

/**
 * What the CI runner set up for the end-to-end tests (.github/workflows/android.yml,
 * scripts/e2e_env.sh): a Dante SOCKS5 proxy and a Squid HTTP proxy, both with
 * sign-in, a proxy that accepts connections and never answers, a closed port, a
 * destination server reachable only by name through the proxies, and a sink beside it
 * that only lines sent while traffic is held go to. The runner writes it
 * to the emulator with adb, so no password appears on any command line; the tests read
 * it as the shell user.
 */
data class E2eConfig(
    val proxyHost: String,
    val socksPort: Int,
    val socksUser: String,
    val socksPassword: String,
    val httpPort: Int,
    val httpUser: String,
    val httpPassword: String,
    val silentPort: Int,
    val closedPort: Int,
    val destinationName: String,
    val destinationAddress: String,
    val destinationPort: Int,
    val sinkPort: Int,
) {
    companion object {
        private const val FILE = "/data/local/tmp/proxyflow-e2e.properties"

        /** The runner's setup as keys and values, or the test is skipped where there is none (a developer's device). */
        fun properties(): Map<String, String> {
            val values = Shell.run("cat $FILE").lines().mapNotNull { line ->
                line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() }
            }.toMap()
            assumeTrue("no proxy setup on this device ($FILE)", "socks.port" in values)
            return values
        }

        /** The runner's setup, or the test is skipped where there is none (a developer's device). */
        fun load(): E2eConfig {
            val values = properties()
            fun value(key: String) = checkNotNull(values[key]) { "$FILE has no $key" }
            return E2eConfig(
                proxyHost = value("proxy.host"),
                socksPort = value("socks.port").toInt(),
                socksUser = value("socks.user"),
                socksPassword = value("socks.password"),
                httpPort = value("http.port").toInt(),
                httpUser = value("http.user"),
                httpPassword = value("http.password"),
                silentPort = value("silent.port").toInt(),
                closedPort = value("closed.port").toInt(),
                destinationName = value("destination.name"),
                destinationAddress = value("destination.address"),
                destinationPort = value("destination.port").toInt(),
                sinkPort = value("sink.port").toInt(),
            )
        }
    }
}

/** Commands as the shell user: traffic from a user the VPN covers (ProxyFlow's own traffic bypasses it). */
object Shell {
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    fun run(command: String): String {
        val output = automation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes().decodeToString() }
    }

    /** A sh script, for pipes: the command line is split at spaces, so they become ${IFS}. */
    fun script(script: String): String = run("sh -c " + script.replace(" ", "\${IFS}"))

    /**
     * Sends one line to [host]:[port] with toybox's nc and returns what came back. The
     * destination answers "PROXYFLOW-DEST <line>" and closes; nothing comes back when the
     * connection never opens within [timeoutSeconds]. nc's input stays open for a moment
     * after the line: at end of input nc half-closes the connection, and proxies (Squid,
     * Dante) close a tunnel whose client half-closed, possibly before the answer is back.
     */
    fun exchange(host: String, port: Int, line: String, timeoutSeconds: Int = 10): String =
        script("(echo $line;sleep $HOLD_INPUT_S)|toybox nc -w $timeoutSeconds $host $port").trim()

    private const val HOLD_INPUT_S = 2
}

/** This process's open descriptors, by what they are (/proc/self/fd). */
object Descriptors {
    private fun links(): List<Pair<Int, String>> = File("/proc/self/fd").listFiles().orEmpty().mapNotNull { file ->
        runCatching { file.name.toInt() to Os.readlink(file.path) }.getOrNull()
    }

    /** VPN interface descriptors: exactly one while connected, none otherwise. */
    fun tun(): List<Int> = links().filter { it.second == "/dev/tun" }.map { it.first }

    fun sockets(): Int = links().count { it.second.startsWith("socket:") }

    fun threads(): Int = File("/proc/self/task").list()?.size ?: 0

    /**
     * Sockets of [uid] that accept connections (TCP in LISTEN) or datagrams (any UDP
     * socket), from /proc/net read as the shell (apps may not read it since Android 10).
     * Fails when a table can't be read, so an empty answer means none.
     */
    fun listening(uid: Int): List<String> = listOf("tcp", "tcp6", "udp", "udp6").flatMap { proto ->
        val table = Shell.run("cat /proc/net/$proto").lines()
        check(table.firstOrNull()?.trim()?.startsWith("sl") == true) { "/proc/net/$proto could not be read: ${table.take(2)}" }
        table.drop(1).mapNotNull { line ->
            val fields = line.trim().split(Regex("\\s+"))
            val owned = fields.size > 7 && fields[7].toIntOrNull() == uid
            val accepting = proto.startsWith("udp") || fields.getOrNull(3) == TCP_LISTEN
            if (owned && accepting) "$proto ${fields[1]}" else null
        }
    }

    private const val TCP_LISTEN = "0A"
}

/**
 * Android's VPN networks as ConnectivityManager reports them, independently of ProxyFlow's
 * own state. Polled, as the app itself does: network callbacks are never told about a VPN
 * that excludes the caller (since API 31 a request only matches networks that apply to its
 * own UID), and ProxyFlow's VPN excludes ProxyFlow. A sampler records every VPN network
 * that appears while this object exists.
 */
class VpnNetworks(private val context: Context) : AutoCloseable {
    private val seen = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var sampling = true
    private val sampler = thread(name = "vpn-networks", isDaemon = true) {
        while (sampling) {
            runCatching { current().forEach { seen += it.id } }
            Thread.sleep(SAMPLE_MS)
        }
    }

    data class Network(val id: String, val interfaceName: String?, val validated: Boolean)

    fun current(): List<Network> = now(context)

    /** How many VPN networks appeared since this object was made, each counted once. */
    val appearances: Int get() = seen.size

    fun present(): Boolean = current().isNotEmpty()

    override fun close() {
        sampling = false
        sampler.join(1_000)
    }

    companion object {
        private const val SAMPLE_MS = 20L

        @Suppress("DEPRECATION") // allNetworks: see above
        fun now(context: Context): List<Network> {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            return connectivity.allNetworks.mapNotNull { network ->
                val capabilities = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
                if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
                Network(
                    id = network.toString(),
                    interfaceName = connectivity.getLinkProperties(network)?.interfaceName,
                    validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                )
            }
        }
    }
}

/**
 * What a failed wait reports: the state, the process's VPN interfaces and service,
 * Android's VPN networks, the VPN layer's own lifecycle log and Android's record of the
 * service (bindings, start requests). No credentials appear in any of them.
 */
object Diagnostics {
    fun report(app: App): String = buildString {
        val state = app.controller.state.value
        appendLine("state: ${state.status} profile=${state.profileId} step=${state.step} error=${state.error}")
        appendLine(
            "service instances: ${ProxyFlowVpnService.liveInstances.get()}; interfaces established so far: " +
                "${ProxyFlowVpnService.establishedInterfaces.get()}; session jobs: ${app.runtime.session.activeJobs()}",
        )
        appendLine("tun descriptors: ${Descriptors.tun().map { "$it (${ProxyFlowVpnService.interfaceName(it)})" }}")
        appendLine("engine: ${runCatching { ProxyEngine().status().state }.getOrNull()}")
        appendLine("Android's VPN networks: ${runCatching { VpnNetworks.now(app.context) }.getOrElse { "unreadable: $it" }}")
        appendLine("VPN log, latest:")
        app.runtime.log.recent().takeLast(LOG_LINES).forEach { appendLine("  $it") }
        appendLine("dumpsys activity services:")
        Shell.run("dumpsys activity services ${app.packageName}").lines().filter {
            it.isNotBlank()
        }.take(DUMP_LINES).forEach { appendLine("  $it") }
    }

    private const val LOG_LINES = 50
    private const val DUMP_LINES = 60
}

/** Every state the controller published while it ran, so a test can prove one never appeared. */
class StateHistory(controller: ConnectionController) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val seen = CopyOnWriteArrayList<ConnectionState>()

    init {
        scope.launch { controller.state.collect { seen += it } }
    }

    val states: List<ConnectionState> get() = seen.toList()

    fun neverConnected(): Boolean = seen.none { it.status == ConnectionStatus.Connected }

    override fun close() = scope.cancel()
}

/** The app as the tests drive it: its own repository, controller and VPN runtime. */
class App {
    val context: Context = ApplicationProvider.getApplicationContext()
    private val container = (context as ProxyFlowApplication).container
    val profiles: ProfileRepository = container.profileRepository
    val controller: ConnectionController = container.connectionController
    val runtime = container.vpnRuntime
    val packageName: String = context.packageName

    fun save(name: String, protocol: ProxyProtocol, host: String, port: Int, user: String = "", password: String = ""): ProxyProfile =
        runBlocking {
            profiles.save(
                null,
                ProxyProfileInput(
                    name = "$name ${UUID.randomUUID().toString().take(4)}",
                    protocol = protocol,
                    server = host,
                    port = port.toString(),
                    requiresSignIn = user.isNotEmpty(),
                    username = user,
                    password = password,
                ),
            )
        }

    fun deleteAllProfiles() = runBlocking { profiles.profiles.first().forEach { profiles.delete(it.id) } }

    fun await(timeoutMillis: Long = 45_000, what: String = "state", predicate: (ConnectionState) -> Boolean): ConnectionState =
        runBlocking {
            try {
                withTimeout(timeoutMillis) { controller.state.first(predicate) }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                throw AssertionError("timed out waiting for $what; state is ${controller.state.value}", e)
            }
        }

    fun awaitStatus(status: ConnectionStatus, timeoutMillis: Long = 45_000): ConnectionState =
        await(timeoutMillis, "status $status") { it.status == status }

    /**
     * Connects [profileId] and waits for the first state after it that matches [predicate].
     * Every attempt publishes Connecting first, so a state equal to the one before the call
     * is still told apart: it is a new object.
     */
    fun connectAndAwait(profileId: String, what: String, predicate: (ConnectionState) -> Boolean): ConnectionState {
        val before = controller.state.value
        controller.connect(profileId)
        return await(what = what) { it !== before && predicate(it) }
    }

    /**
     * Connects [profileId] and returns the Connected state. An attempt that the phone's own uplink
     * failed ("no route to the proxy": the emulator's network did not reach the runner for a moment,
     * as the API 34 and 35 images' sometimes do) is tried again once the proxy answers this process
     * directly (outside the VPN), at most [UPLINK_RETRIES] times, and logged; the evidence counts
     * these. Any other failure fails the test at once.
     */
    fun connect(profileId: String): ConnectionState {
        var ended = connectAndAwait(profileId, "Connected or a failed attempt") { it.profileId == profileId && it.connectedOrFailed() }
        var retries = 0
        while (ended.status != ConnectionStatus.Connected) {
            if (ended.error != UPLINK_DROPPED) throw AssertionError("connecting failed; state is $ended")
            if (retries == UPLINK_RETRIES) throw AssertionError("connecting failed ${retries + 1} times; state is $ended")
            // The failed attempt leaves nothing behind: a first attempt no interface, a switch only the one it holds.
            if (ended.status == ConnectionStatus.Disconnected) {
                eventually("the failed attempt left no interface", diagnose = { Diagnostics.report(this) }) { Descriptors.tun().isEmpty() }
            }
            retries++
            val waited = ended.profile?.let { awaitProxy(it.host, it.port) }
            Log.i(TAG, "uplink dropped: connecting again (retry $retries; the proxy answered this process again after $waited ms)")
            ended = connectAndAwait(profileId, "Connected or a failed attempt") { it.profileId == profileId && it.connectedOrFailed() }
        }
        logAndroidsView()
        return ended
    }

    /**
     * Android's own account of ProxyFlow's VPN, for the evidence (scripts/m6_evidence.py): which uids it routes into the
     * VPN and whether it lets apps bypass it (Android 13 and later say), from the VPN network's capabilities, and its DNS
     * servers, with ProxyFlow's own uid. Android tells only callers with the shell's network permission which uids a
     * network serves, so the shell's permissions are borrowed for the call. Logged only: the evidence checks it.
     */
    @Suppress("DEPRECATION") // allNetworks: see VpnNetworks
    private fun logAndroidsView() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val view = runCatching {
            automation.adoptShellPermissionIdentity()
            try {
                val connectivity = context.getSystemService(ConnectivityManager::class.java)
                val vpn = connectivity.allNetworks.firstOrNull {
                    connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                }
                if (vpn == null) {
                    "no VPN network"
                } else {
                    val capabilities = connectivity.getNetworkCapabilities(vpn).toString()
                    val dns = connectivity.getLinkProperties(vpn)?.dnsServers.orEmpty().joinToString(" ") { it.hostAddress.orEmpty() }
                    val fields = VIEW_FIELDS.mapNotNull { it.find(capabilities)?.value }
                    (fields.ifEmpty { listOf(capabilities.take(VIEW_CHARS)) } + "DnsAddresses: [ $dns ]").joinToString(" | ")
                }
            } finally {
                automation.dropShellPermissionIdentity()
            }
        }.getOrElse { "unreadable: $it" }
        Log.i(TAG, "android's view of the VPN (own uid ${Process.myUid()}): $view")
    }

    /** Waits until [host]:[port] accepts a connection from this process, which ProxyFlow's VPN leaves out; the time taken. */
    private fun awaitProxy(host: String, port: Int): Long {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < UPLINK_WAIT_MS) {
            val reached = runCatching { Socket().use { it.connect(InetSocketAddress(host, port), 1_000) } }.isSuccess
            if (reached) return System.currentTimeMillis() - start
            Thread.sleep(500)
        }
        return -1
    }

    private fun ConnectionState.connectedOrFailed() = status == ConnectionStatus.Connected ||
        (status == ConnectionStatus.Disconnected || status == ConnectionStatus.Blocked) && error != null

    /** An attempt that must fail before connecting: the state it ends in. */
    fun connectExpectingFailure(profileId: String): ConnectionState = connectAndAwait(profileId, "a failed attempt") {
        it.status == ConnectionStatus.Disconnected && it.error != null
    }

    /** Lets Android allow (or refuse) ProxyFlow's VPN without its dialog: the consent the dialog records. */
    fun setVpnConsent(allowed: Boolean) {
        Shell.run("appops set $packageName ACTIVATE_VPN ${if (allowed) "allow" else "ignore"}")
    }

    fun allowNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
    }

    /** ProxyFlow's notification with [id], if it is showing. */
    fun notification(id: Int): Notification? =
        context.getSystemService(NotificationManager::class.java).activeNotifications.firstOrNull { it.id == id }?.notification

    fun notificationTitle(id: Int): String? = notification(id)?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    /**
     * Disconnects and waits until the interface, the service and Android's VPN network are gone; a
     * timeout reports what was left. Android removes its network a moment after the interface
     * closes, and a test that starts meanwhile would count the old one as its own. A test's own
     * check uses the default; before a test, a longer wait lets one test's failure stay its own
     * instead of failing every test after it.
     */
    fun disconnectAndSettle(timeoutMillis: Long = 10_000) {
        controller.disconnect()
        awaitStatus(ConnectionStatus.Disconnected)
        val diagnose = { Diagnostics.report(this) }
        eventually("the VPN interface closes", timeoutMillis, diagnose) { Descriptors.tun().isEmpty() }
        eventually("the VPN service stops", timeoutMillis, diagnose) { ProxyFlowVpnService.liveInstances.get() == 0 }
        eventually("Android has no VPN network", timeoutMillis, diagnose) { VpnNetworks.now(context).isEmpty() }
    }
}

private const val TAG = "ProxyFlowE2E"

/** The parts of the VPN network's capabilities (their text) the evidence reads. */
private val VIEW_FIELDS = listOf(
    Regex("Uids: <[^>]*>"),
    Regex("OwnerUid: \\d+"),
    Regex("VpnTransportInfo\\{[^}]*\\}"),
)
private const val VIEW_CHARS = 600

/** What the engine reports when the phone has no route to the proxy at all: its own uplink is down. */
private val UPLINK_DROPPED = ConnectionError("PROXY_UNREACHABLE", "no route to the proxy")
private const val UPLINK_RETRIES = 2
private const val UPLINK_WAIT_MS = 15_000L

/** How long a test's setup waits for an earlier test's VPN service and interface to go. */
const val SETTLE_BEFORE_TEST_MS = 30_000L

/** Polls [condition] until it holds or [timeoutMillis] passes; the failure carries [diagnose]'s report. */
fun eventually(what: String, timeoutMillis: Long = 10_000, diagnose: () -> String = { "" }, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (!condition()) {
        if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting until $what\n${diagnose()}")
        Thread.sleep(50)
    }
}
