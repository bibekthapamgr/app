package app.proxyflow.core.vpn

import app.proxyflow.core.engine.EngineException
import app.proxyflow.core.engine.EngineSecret
import app.proxyflow.core.model.ConnectionError
import app.proxyflow.core.model.ConnectionState
import app.proxyflow.core.model.ProxyProfile
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

    @Volatile var startError: EngineException? = null

    @Volatile var running = false
        private set

    @Volatile var failure: ((ConnectionError) -> Unit)? = null
        private set

    /** For each probe and start: whether credentials were handed over. */
    val secrets = CopyOnWriteArrayList<Boolean>()

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
        check(protect(PROBE_SOCKET)) { "the pre-flight socket was not protected" }
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

/** The service's side: consent, interfaces and the notification, all recorded. */
class FakeHost(private val log: CallLog) : VpnHost {
    @Volatile var consent = true

    @Volatile var establishReturnsNull = false

    @Volatile var establishError: RuntimeException? = null

    @Volatile var foregroundAllowed = true

    val tuns = CopyOnWriteArrayList<FakeTun>()
    val statuses = CopyOnWriteArrayList<ConnectionState>()
    val problems = CopyOnWriteArrayList<ConnectionState>()
    val protectedSockets = CopyOnWriteArrayList<Int>()

    @Volatile var lastConfig: TunConfig? = null
        private set

    override fun hasConsent() = consent

    override fun establish(config: TunConfig): TunInterface? {
        lastConfig = config
        establishError?.let { throw it }
        if (establishReturnsNull) return null
        val tun = FakeTun(FIRST_FD + tuns.size, log)
        tuns += tun
        log.add("establish fd=${tun.fd}")
        return tun
    }

    override fun protectSocket(fd: Int): Boolean {
        protectedSockets += fd
        return true
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
    }
}

class FakeTun(override val fd: Int, private val log: CallLog) : TunInterface {
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
