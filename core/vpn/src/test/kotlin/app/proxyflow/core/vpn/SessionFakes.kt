package app.proxyflow.core.vpn

import app.proxyflow.core.engine.EngineSecret
import app.proxyflow.core.model.ConnectionError
import app.proxyflow.core.model.ConnectionState
import app.proxyflow.core.model.ProxyProfile
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow

/** One ordered record of what the engine, the host and the interfaces were asked to do. */
class CallLog {
    private val entries = CopyOnWriteArrayList<String>()

    fun add(entry: String) {
        entries += entry
    }

    val all: List<String> get() = entries.toList()

    fun indexOf(entry: String): Int = entries.indexOf(entry)

    fun count(prefix: String): Int = entries.count { it.startsWith(prefix) }
}

/** The engine: answers the pre-flight check with [answer] (after [gate], when set) and records every call. */
class FakeEngine(private val log: CallLog) : TunnelEngine {
    @Volatile var answer = PreflightAnswer(ok = true)

    @Volatile var gate: CountDownLatch? = null

    @Volatile var startError: Throwable? = null

    /** Runs inside each probe, after its socket was protected: a test can block or throw for one profile. */
    @Volatile var probeHook: (ProxyProfile) -> Unit = {}

    @Volatile var running = false
        private set

    @Volatile var failure: ((ConnectionError) -> Unit)? = null
        private set

    /** For each probe and start: whether credentials were handed over. */
    val secrets = CopyOnWriteArrayList<Boolean>()

    /** For each probe and start: the server it was given ("probe proxy.example.com"). */
    val servers = CopyOnWriteArrayList<String>()

    override fun tunConfig() = TunConfig(address4 = "198.18.0.1", address6 = "fd00:66::1", dnsServer = "198.18.0.2")

    override fun probe(
        profile: ProxyProfile,
        secret: EngineSecret?,
        target: String,
        timeoutMillis: Long,
        protect: (Int) -> Boolean,
    ): PreflightAnswer {
        log.add("probe ${profile.id} $target")
        secrets += secret != null
        servers += "probe ${profile.host}"
        check(protect(PROBE_SOCKET)) { "the pre-flight socket was not protected" }
        probeHook(profile)
        gate?.await(10, TimeUnit.SECONDS)
        secret?.close()
        return answer
    }

    override fun start(
        tun: TunInterface,
        config: TunConfig,
        profile: ProxyProfile,
        secret: EngineSecret?,
        protect: (Int) -> Boolean,
        onFailure: (ConnectionError) -> Unit,
    ) {
        log.add("start ${profile.id} fd=${tun.fd} mtu=${config.mtu}")
        secrets += secret != null
        servers += "start ${profile.host}"
        secret?.close()
        startError?.let { throw it }
        running = true
        failure = onFailure
    }

    override fun stop() {
        log.add("stop")
        running = false
    }

    companion object {
        const val PROBE_SOCKET = 77
    }
}

/**
 * The service's side: consent, interfaces and the notification, all recorded. Android's
 * VPN network is simulated as one ready network leading into the newest open interface,
 * or none with [routes] off. Establishing an interface while another is open is counted in
 * [overlaps]: Android would take the open one down at once and route the new one only
 * later, with apps' traffic on the normal network in between (ADR-0010 §5).
 */
class FakeHost(private val log: CallLog) : VpnHost {
    @Volatile var consent = true

    /** Android routes apps into the newest interface; off, into none. */
    @Volatile var routes = true

    @Volatile private var networksError: RuntimeException? = null

    @Volatile private var failingReads = 0 until 0
    private val networksReads = AtomicInteger()

    /** From now on, reading Android's VPN networks throws [error] once [after] more reads have worked, [times] times. */
    fun failNetworkReads(error: RuntimeException, after: Int = 0, times: Int = Int.MAX_VALUE - after) {
        networksReads.set(0)
        failingReads = after until after + times
        networksError = error
    }

    /** Interfaces established while another one was still open. */
    val overlaps = AtomicInteger()

    /** What a check's socket guard does when the session aborts it (the engine's call then returns). */
    @Volatile var onAbort: () -> Unit = {}

    @Volatile var establishReturnsNull = false

    @Volatile var establishError: RuntimeException? = null

    @Volatile var foregroundAllowed = true

    val tuns = CopyOnWriteArrayList<FakeTun>()
    val statuses = CopyOnWriteArrayList<ConnectionState>()
    val problems = CopyOnWriteArrayList<ConnectionState>()
    val protectedSockets = CopyOnWriteArrayList<Int>()

    /** Sockets the checks' guards were given, guards aborted, guards not yet closed. */
    val trackedSockets = CopyOnWriteArrayList<Int>()
    val aborts = AtomicInteger()
    val guardsOpen = AtomicInteger()

    @Volatile var lastConfig: TunConfig? = null
        private set

    override fun hasConsent() = consent

    override fun establish(config: TunConfig): TunInterface? {
        lastConfig = config
        establishError?.let { throw it }
        if (establishReturnsNull) return null
        tuns.firstOrNull { !it.closed }?.let { open ->
            overlaps.incrementAndGet()
            log.add("establish while fd=${open.fd} is open")
        }
        val tun = FakeTun(FIRST_FD + tuns.size, log)
        tuns += tun
        log.add("establish fd=${tun.fd}")
        return tun
    }

    override fun protectSocket(fd: Int): Boolean {
        protectedSockets += fd
        return true
    }

    override fun vpnNetworks(): List<VpnNetwork> {
        val error = networksError
        if (error != null && networksReads.getAndIncrement() in failingReads) throw error
        if (!routes) return emptyList()
        return listOfNotNull(tuns.lastOrNull { !it.closed }?.let { VpnNetwork(id = NETWORK_ID, interfaceName = it.name, ready = true) })
    }

    override fun socketGuard(): SocketGuard {
        guardsOpen.incrementAndGet()
        return object : SocketGuard {
            override fun track(fd: Int) {
                trackedSockets += fd
            }

            override fun abort() {
                aborts.incrementAndGet()
                onAbort()
            }

            override fun close() {
                guardsOpen.decrementAndGet()
            }
        }
    }

    override fun showStatus(state: ConnectionState): Boolean {
        statuses += state
        return foregroundAllowed
    }

    override fun notifyProblem(state: ConnectionState) {
        problems += state
    }

    override fun clearProblem() {
        log.add("clear problem")
    }

    override fun stop() {
        log.add("host stop")
    }

    companion object {
        const val FIRST_FD = 100
        const val NETWORK_ID = "netId 100"
    }
}

class FakeTun(override val fd: Int, private val log: CallLog) : TunInterface {
    override val name = "tun${fd - FakeHost.FIRST_FD}"

    /** Packets apps sent while no engine read the interface. */
    @Volatile var queued = 0

    override fun drain(): Int {
        log.add("drain fd=$fd")
        return queued.also { queued = 0 }
    }

    @Volatile var closed = false
        private set

    override fun close() {
        if (!closed) log.add("close fd=$fd")
        closed = true
    }
}

/** A state flow that also keeps every value written, so a test can prove a state never appeared. */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
class RecordingState(private val delegate: MutableStateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Idle)) :
    MutableStateFlow<ConnectionState> by delegate {
    private val written = CopyOnWriteArrayList<ConnectionState>()

    val history: List<ConnectionState> get() = written.toList()

    override var value: ConnectionState
        get() = delegate.value
        set(next) {
            written += next
            delegate.value = next
        }

    override fun compareAndSet(expect: ConnectionState, update: ConnectionState): Boolean {
        val done = delegate.compareAndSet(expect, update)
        if (done) written += update
        return done
    }
}
