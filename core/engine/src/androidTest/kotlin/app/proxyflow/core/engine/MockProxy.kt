package app.proxyflow.core.engine

import java.io.Closeable
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.net.ssl.SSLContext

/** A TCP server on 127.0.0.1 that sends back what it receives. */
class EchoServer : Closeable {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val pool: ExecutorService = Executors.newCachedThreadPool()
    private val sockets = Collections.synchronizedSet(mutableSetOf<Socket>())
    val address: InetSocketAddress get() = InetSocketAddress(server.inetAddress, server.localPort)

    init {
        pool.execute {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                sockets += s
                pool.execute {
                    s.use { runCatching { it.getInputStream().copyTo(it.getOutputStream()) } }
                    sockets -= s
                }
            }
        }
    }

    override fun close() {
        server.close()
        synchronized(sockets) { sockets.forEach { runCatching { it.close() } } }
        pool.shutdownNow()
    }
}

/**
 * A mock SOCKS5 (RFC 1928/1929) or HTTP CONNECT (Basic) proxy on 127.0.0.1,
 * written independently of the engine's Go client, optionally behind TLS.
 * Destination names are looked up in [hosts]; IP destinations are dialled as
 * given unless [hosts] maps them. It records what each client asked for.
 */
class MockProxy(
    val protocol: Protocol,
    private val username: String? = null,
    private val password: String? = null,
    private val hosts: Map<String, InetSocketAddress> = emptyMap(),
    tls: SSLContext? = null,
    private val silent: Boolean = false,
) : Closeable {
    enum class Protocol { SOCKS5, HTTP }

    /** What one client asked for. Holds the credentials it sent so tests can check them. */
    data class Record(val target: String, val addressType: String, val username: String?, val password: String?, val outcome: String)

    private val server: ServerSocket = (tls?.serverSocketFactory ?: javax.net.ServerSocketFactory.getDefault())
        .createServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val pool: ExecutorService = Executors.newCachedThreadPool()
    private val sockets = Collections.synchronizedSet(mutableSetOf<Socket>())
    val records = CopyOnWriteArrayList<Record>()
    val port: Int get() = server.localPort

    init {
        pool.execute {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                sockets += client
                pool.execute {
                    try {
                        if (silent) {
                            val sink = ByteArray(4096)
                            while (client.getInputStream().read(sink) >= 0) Unit // read, never answer
                        } else {
                            when (protocol) {
                                Protocol.SOCKS5 -> socks5(client)
                                Protocol.HTTP -> http(client)
                            }
                        }
                    } catch (_: Exception) {
                        // A client that goes away mid-handshake is normal in these tests.
                    } finally {
                        runCatching { client.close() }
                        sockets -= client
                    }
                }
            }
        }
    }

    override fun close() {
        server.close()
        synchronized(sockets) { sockets.forEach { runCatching { it.close() } } }
        pool.shutdownNow()
    }

    private fun socks5(client: Socket) {
        val input = DataInputStream(client.getInputStream())
        val output = client.getOutputStream()
        check(input.readUnsignedByte() == 5)
        val methods = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
        val wantsSignIn = username != null
        val method = when {
            wantsSignIn && 2.toByte() in methods -> 2
            !wantsSignIn && 0.toByte() in methods -> 0
            else -> 0xFF
        }
        output.write(byteArrayOf(5, method.toByte()))
        var user: String? = null
        var pass: String? = null
        if (method == 0xFF) {
            records += Record("", "", null, null, "auth-missing")
            return
        }
        if (method == 2) {
            check(input.readUnsignedByte() == 1)
            user = String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) })
            pass = String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) })
            if (user != username || pass != password) {
                output.write(byteArrayOf(1, 1))
                records += Record("", "", user, pass, "auth-failed")
                return
            }
            output.write(byteArrayOf(1, 0))
        }
        check(input.readUnsignedByte() == 5)
        val command = input.readUnsignedByte()
        input.readUnsignedByte()
        val (host, type) = when (input.readUnsignedByte()) {
            1 -> InetAddress.getByAddress(ByteArray(4).also { input.readFully(it) }).hostAddress!! to "ipv4"
            4 -> InetAddress.getByAddress(ByteArray(16).also { input.readFully(it) }).hostAddress!! to "ipv6"
            3 -> String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) }) to "domain"
            else -> error("bad address type")
        }
        val port = input.readUnsignedShort()
        val target = if (':' in host) "[$host]:$port" else "$host:$port"
        val destination = resolve(host, port)
        if (command != 1 || destination == null) {
            output.write(byteArrayOf(5, if (command != 1) 7 else 4, 0, 1, 0, 0, 0, 0, 0, 0))
            records += Record(target, type, user, pass, "unreachable")
            return
        }
        val upstream = runCatching { Socket().apply { connect(destination, 5000) } }.getOrNull()
        if (upstream == null) {
            output.write(byteArrayOf(5, 5, 0, 1, 0, 0, 0, 0, 0, 0))
            records += Record(target, type, user, pass, "refused")
            return
        }
        output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 0))
        records += Record(target, type, user, pass, "ok")
        relay(input, output, upstream)
    }

    private fun http(client: Socket) {
        val input = client.getInputStream()
        val output = client.getOutputStream()
        val head = readHead(input)
        val requestLine = head.first()
        val parts = requestLine.split(' ')
        if (parts.size != 3 || parts[0] != "CONNECT") {
            output.write("HTTP/1.1 405 Method Not Allowed\r\n\r\n".toByteArray())
            return
        }
        val authority = parts[1]
        val authorization = head.drop(1).firstOrNull { it.startsWith("Proxy-Authorization:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()
        var user: String? = null
        var pass: String? = null
        if (authorization != null && authorization.startsWith("Basic ")) {
            val decoded = String(Base64.getDecoder().decode(authorization.removePrefix("Basic ")))
            user = decoded.substringBefore(':')
            pass = decoded.substringAfter(':')
        }
        if (username != null && (user != username || pass != password)) {
            output.write("HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"mock\"\r\n\r\n".toByteArray())
            records += Record(authority, "authority", user, pass, if (authorization == null) "auth-missing" else "auth-failed")
            return
        }
        val host = authority.substringBeforeLast(':').removePrefix("[").removeSuffix("]")
        val port = authority.substringAfterLast(':').toInt()
        val destination = resolve(host, port)
        val upstream = destination?.let { runCatching { Socket().apply { connect(it, 5000) } }.getOrNull() }
        if (upstream == null) {
            output.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
            records += Record(authority, "authority", user, pass, "unreachable")
            return
        }
        output.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
        records += Record(authority, "authority", user, pass, "ok")
        relay(input, output, upstream)
    }

    private fun readHead(input: InputStream): List<String> {
        val lines = mutableListOf<String>()
        val line = StringBuilder()
        var total = 0
        while (true) {
            val b = input.read()
            if (b < 0 || ++total > 16384) error("bad request head")
            if (b == '\n'.code) {
                val text = line.toString().removeSuffix("\r")
                if (text.isEmpty()) return lines
                lines += text
                line.clear()
            } else {
                line.append(b.toChar())
            }
        }
    }

    private fun resolve(host: String, port: Int): InetSocketAddress? {
        hosts[host]?.let { return it }
        return if (host.any { it.isLetter() } && ':' !in host) null else InetSocketAddress(InetAddress.getByName(host), port)
    }

    private fun relay(input: InputStream, output: OutputStream, upstream: Socket) {
        sockets += upstream
        try {
            val back = pool.submit {
                runCatching { upstream.getInputStream().copyTo(output) }
                runCatching { output.close() }
            }
            runCatching { input.copyTo(upstream.getOutputStream()) }
            runCatching { upstream.shutdownOutput() }
            back.get()
        } finally {
            runCatching { upstream.close() }
            sockets -= upstream
        }
    }
}
