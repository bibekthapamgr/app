package app.proxyflow.e2e

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.proxyflow.ProxyFlowApplication
import app.proxyflow.core.data.ConnectionController
import app.proxyflow.core.data.ProfileRepository
import app.proxyflow.core.model.ConnectionState
import app.proxyflow.core.model.ConnectionStatus
import app.proxyflow.core.model.ProxyProfile
import app.proxyflow.core.model.ProxyProfileInput
import app.proxyflow.core.model.ProxyProtocol
import app.proxyflow.core.vpn.ProxyFlowVpnService
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
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
 * scripts/m5_device_env.sh): a Dante SOCKS5 proxy and a Squid HTTP proxy, both with
 * sign-in, a proxy that accepts connections and never answers, a closed port, and a
 * destination server reachable only by name through the proxies. The runner writes it
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
) {
    companion object {
        private const val FILE = "/data/local/tmp/proxyflow-e2e.properties"

        /** The runner's setup, or the test is skipped where there is none (a developer's device). */
        fun load(): E2eConfig {
            val values = Shell.run("cat $FILE").lines().mapNotNull { line ->
                line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() }
            }.toMap()
            assumeTrue("no proxy setup on this device ($FILE)", "socks.port" in values)
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
     * connection never opens within [timeoutSeconds].
     */
    fun exchange(host: String, port: Int, line: String, timeoutSeconds: Int = 10): String =
        script("echo $line | toybox nc -w $timeoutSeconds $host $port").trim()

    fun <T> withShellPermissions(block: () -> T): T {
        automation.adoptShellPermissionIdentity()
        try {
            return block()
        } finally {
            automation.dropShellPermissionIdentity()
        }
    }
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
     */
    fun listening(uid: Int): List<String> = listOf("tcp", "tcp6", "udp", "udp6").flatMap { proto ->
        Shell.run("cat /proc/net/$proto").lines().drop(1).mapNotNull { line ->
            val fields = line.trim().split(Regex("\\s+"))
            val owned = fields.size > 7 && fields[7].toIntOrNull() == uid
            val accepting = proto.startsWith("udp") || fields.getOrNull(3) == TCP_LISTEN
            if (owned && accepting) "$proto ${fields[1]}" else null
        }
    }

    private const val TCP_LISTEN = "0A"
}

/**
 * VPN networks as Android sees them, independently of ProxyFlow's own state. The
 * shell's permissions (NETWORK_SETTINGS) are needed to see a VPN that excludes this app.
 */
class VpnNetworks(context: Context) : AutoCloseable {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val appeared = AtomicInteger()
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            appeared.incrementAndGet()
        }
    }

    init {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        Shell.withShellPermissions { connectivity.registerNetworkCallback(request, callback) }
    }

    /** How many times a VPN network appeared since this object was made. */
    val appearances: Int get() = appeared.get()

    @Suppress("DEPRECATION") // allNetworks: the simplest complete list for a test
    fun present(): Boolean = Shell.withShellPermissions {
        connectivity.allNetworks.any { connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }

    override fun close() {
        Shell.withShellPermissions { connectivity.unregisterNetworkCallback(callback) }
    }
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

    fun connect(profileId: String): ConnectionState =
        connectAndAwait(profileId, "Connected") { it.status == ConnectionStatus.Connected && it.profileId == profileId }

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

    /** Disconnects and waits until the interface, the service and its notification are gone. */
    fun disconnectAndSettle() {
        controller.disconnect()
        awaitStatus(ConnectionStatus.Disconnected)
        eventually("the VPN service stops") { ProxyFlowVpnService.liveInstances.get() == 0 }
        eventually("the VPN interface closes") { Descriptors.tun().isEmpty() }
    }
}

/** Polls [condition] until it holds or [timeoutMillis] passes. */
fun eventually(what: String, timeoutMillis: Long = 10_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (!condition()) {
        if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting until $what")
        Thread.sleep(50)
    }
}
