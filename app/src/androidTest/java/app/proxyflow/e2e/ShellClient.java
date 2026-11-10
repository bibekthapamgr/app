package app.proxyflow.e2e;

import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.PortUnreachableException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

/**
 * A network client the device tests run as the shell user, whose traffic Android routes into
 * ProxyFlow's VPN (the app's own process, where the tests run, is outside it; ADR-0010). Plain
 * Java, no Kotlin, so the test APK alone is its class path (see Lab.kt):
 *
 * <pre>
 *   CLASSPATH=pf-client.apk app_process /system/bin app.proxyflow.e2e.ShellClient COMMAND ARGS...
 * </pre>
 *
 * Each command prints one line:
 *
 * <pre>
 *   tcp HOST PORT LINE TIMEOUT_MS             REPLY text | ERROR ...
 *   udp HOST PORT TIMEOUT_MS                  REPLY text | PORT_UNREACHABLE | TIMEOUT | ERROR ...
 *   dns SERVER NAME TYPE TIMEOUT_MS [tcp]     RCODE n ANSWERS a,b|- | TIMEOUT | ERROR ...
 *   resolve NAME                              ADDRESSES a,b | ERROR ...
 *   tls HOST PORT CA_PEM LINE TIMEOUT_MS      TLS protocol LEAF sha256 REPLY text | ERROR ...
 *   http HOST PORT PATH TIMEOUT_MS            HTTP status BODY text | ERROR ...
 *   https HOST PORT PATH TIMEOUT_MS           HTTPS protocol LEAF sha256 HTTP status BODY text | ERROR ...
 *   bulk HOST PORT up BYTES TIMEOUT_MS LINGER_MS     SENT n REPLY text | ERROR ...
 *   bulk HOST PORT down BYTES TIMEOUT_MS LINGER_MS   RECEIVED n | ERROR ...
 *   hold HOST PORT SECONDS                    ENDED ms closed|reset|data|timeout | ERROR ...
 *   rate HOST PORT up|down BYTES TIMEOUT_MS [via socks5|http]   RATE timed ms SETUP ms | ERROR ...
 *   pace HOST PORT BYTES_PER_S SECONDS TIMEOUT_MS                PACED bytes ms | ERROR ...
 *   connect HOST PORT TIMEOUT_MS [stamped]    CONNECTED ms | FAILED ms exception | UNRESOLVED exception
 *   many HOST PORT COUNT SECONDS [load BYTES] HELD opened OF count FAILED failures IN ms [DRAINED n] [first exception]
 * </pre>
 *
 * rate (M10) times one bulk transfer, without its set-up: an upload from the request line sent to the server's count,
 * a download from its first byte to its last ("timed" is the bytes that time covers); SETUP is what came before, from
 * the start (the lookup, the connection, any proxy handshake, the request). With via, the client signs in to the lab's
 * SOCKS5 or HTTP proxy itself (proxy.host and the protocol's port and sign-in, read from the lab's properties file, so
 * no password is on a command line) and asks it for HOST:PORT by name, as ProxyFlow does: the same transfer without
 * ProxyFlow, for while it is not connected. pace uploads BYTES_PER_S for SECONDS, evenly, so a test can read what a
 * given rate costs ProxyFlow. connect resolves HOST, then times the connection's set-up alone (through ProxyFlow, it is
 * accepted only once the proxy's tunnel is open, ADR-0002, so a refusing proxy shows as a quick failure); stamped, it
 * adds " AT t", t being when the connection began on Android's clock (SystemClock.elapsedRealtime). many looks
 * HOST up once and opens COUNT connections one after another, each asking the bulk server to hold it for SECONDS — or,
 * with load, to send it BYTES, which it leaves unread behind a small receive buffer, so they wait along the way,
 * ProxyFlow's buffers among them — prints how long opening them took, and closes each a moment before SECONDS after it
 * opened (with load, having read its BYTES first), so a test can read the engine's memory while all are open.
 *
 * bulk and hold talk to the lab's bulk server (scripts/e2e_servers.py): up sends BYTES after its
 * request line and prints the server's count, down reads BYTES, and both then keep the connection
 * open for LINGER_MS more (the server waits for the client to close), so the test can read the
 * engine's own count for it; hold keeps one connection open, sending nothing, and prints how long
 * it stayed open and how it ended.
 *
 * TYPE is a DNS record type number (1 A, 28 AAAA, 16 TXT, 65 HTTPS). tls trusts only the CA in
 * CA_PEM and checks the host name, as a browser checks a site: what it prints is the certificate
 * the destination itself presented, end to end through the tunnel.
 *
 * http and https (the live proxy check, scripts/live_proxy_check.sh) fetch one page the way a browser's first request
 * does — GET PATH, HTTP/1.0, Connection: close — and print the status and the body's first 200 characters, whitespace
 * collapsed (a site that answers with the caller's address shows which address the site saw). https trusts the
 * phone's own certificate store, as the phone's browser does, and checks the host name; it prints the protocol and
 * the certificate the site presented. Neither takes a line of input from the command line beyond the path.
 */
public final class ShellClient {
    private ShellClient() {}

    public static void main(String[] args) {
        String out;
        try {
            out = run(args);
        } catch (Throwable t) {
            out = "ERROR " + t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()).replace('\n', ' ');
        }
        System.out.println(out);
        System.out.flush();
        System.exit(0);
    }

    private static String run(String[] a) throws Exception {
        switch (a.length > 0 ? a[0] : "") {
            case "tcp":
                return tcp(a[1], Integer.parseInt(a[2]), a[3], Integer.parseInt(a[4]));
            case "udp":
                return udp(a[1], Integer.parseInt(a[2]), Integer.parseInt(a[3]));
            case "dns":
                return dns(a[1], a[2], Integer.parseInt(a[3]), Integer.parseInt(a[4]), a.length > 5 && a[5].equals("tcp"));
            case "resolve":
                return resolve(a[1]);
            case "tls":
                return tls(a[1], Integer.parseInt(a[2]), a[3], a[4], Integer.parseInt(a[5]));
            case "http":
                return http(a[1], Integer.parseInt(a[2]), a[3], Integer.parseInt(a[4]), false);
            case "https":
                return http(a[1], Integer.parseInt(a[2]), a[3], Integer.parseInt(a[4]), true);
            case "bulk":
                return bulk(a[1], Integer.parseInt(a[2]), a[3], Long.parseLong(a[4]), Integer.parseInt(a[5]), Integer.parseInt(a[6]));
            case "hold":
                return hold(a[1], Integer.parseInt(a[2]), Integer.parseInt(a[3]));
            case "rate":
                return rate(a[1], Integer.parseInt(a[2]), a[3], Long.parseLong(a[4]), Integer.parseInt(a[5]),
                        a.length > 7 && a[6].equals("via") ? a[7] : null);
            case "pace":
                return pace(a[1], Integer.parseInt(a[2]), Long.parseLong(a[3]), Integer.parseInt(a[4]), Integer.parseInt(a[5]));
            case "connect":
                return connect(a[1], Integer.parseInt(a[2]), Integer.parseInt(a[3]), a.length > 4 && a[4].equals("stamped"));
            case "many":
                return many(a[1], Integer.parseInt(a[2]), Integer.parseInt(a[3]), Integer.parseInt(a[4]),
                        a.length > 6 && a[5].equals("load") ? Long.parseLong(a[6]) : 0);
            default:
                return "ERROR unknown command";
        }
    }

    private static String tcp(String host, int port, String line, int timeout) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeout);
            socket.setSoTimeout(timeout);
            return "REPLY " + exchange(socket.getInputStream(), socket.getOutputStream(), line);
        }
    }

    private static String bulk(String host, int port, String direction, long bytes, int timeout, int linger) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeout);
            socket.setSoTimeout(timeout);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            byte[] buffer = new byte[65536];
            if (direction.equals("up")) {
                out.write(("UP " + bytes + "\n").getBytes(StandardCharsets.US_ASCII));
                for (int i = 0; i < buffer.length; i++) buffer[i] = (byte) i;
                long left = bytes;
                while (left > 0) {
                    int n = (int) Math.min(buffer.length, left);
                    out.write(buffer, 0, n);
                    left -= n;
                }
                out.flush();
                ByteArrayOutputStream reply = new ByteArrayOutputStream();
                int b;
                while ((b = in.read()) != -1 && b != '\n') reply.write(b);
                Thread.sleep(linger);
                return "SENT " + bytes + " REPLY " + reply.toString("US-ASCII").trim();
            }
            out.write(("DOWN " + bytes + "\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            long received = 0;
            int n;
            while (received < bytes && (n = in.read(buffer, 0, (int) Math.min(buffer.length, bytes - received))) != -1) received += n;
            Thread.sleep(linger);
            return "RECEIVED " + received;
        }
    }

    private static String rate(String host, int port, String direction, long bytes, int timeout, String via) throws Exception {
        long start = System.nanoTime();
        try (Socket socket = new Socket()) {
            if (via == null) {
                socket.connect(new InetSocketAddress(host, port), timeout);
                socket.setSoTimeout(timeout);
            } else {
                Properties lab = lab();
                boolean socks = via.equals("socks5");
                if (!socks && !via.equals("http")) return "ERROR via " + via + "?";
                String proxy = lab.getProperty("proxy.host");
                socket.connect(new InetSocketAddress(proxy, Integer.parseInt(lab.getProperty(socks ? "socks.port" : "http.port"))), timeout);
                socket.setSoTimeout(timeout);
                if (socks) {
                    socks5(socket, lab.getProperty("socks.user"), lab.getProperty("socks.password"), host, port);
                } else {
                    httpConnect(socket, lab.getProperty("http.user"), lab.getProperty("http.password"), host, port);
                }
            }
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            byte[] buffer = new byte[65536];
            for (int i = 0; i < buffer.length; i++) buffer[i] = (byte) i;
            if (direction.equals("up")) {
                out.write(("UP " + bytes + "\n").getBytes(StandardCharsets.US_ASCII));
                out.flush();
                long clock = System.nanoTime(); // the transfer from here; the set-up and the request line before it
                long left = bytes;
                while (left > 0) {
                    int n = (int) Math.min(buffer.length, left);
                    out.write(buffer, 0, n);
                    left -= n;
                }
                out.flush();
                String reply = line(in);
                long end = System.nanoTime();
                if (!reply.equals("GOT " + bytes)) return "ERROR the bulk server answered " + reply + " to " + bytes + " bytes";
                return "RATE " + bytes + " " + (end - clock) / 1_000_000 + " SETUP " + (clock - start) / 1_000_000;
            }
            out.write(("DOWN " + bytes + "\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            int first = in.read(buffer, 0, (int) Math.min(buffer.length, bytes));
            if (first <= 0) return "ERROR the bulk server sent nothing";
            long clock = System.nanoTime(); // from the first byte: the set-up and the first round trip before it
            long received = first;
            int n;
            while (received < bytes && (n = in.read(buffer, 0, (int) Math.min(buffer.length, bytes - received))) != -1) received += n;
            long end = System.nanoTime();
            if (received != bytes) return "ERROR RECEIVED " + received + " of " + bytes;
            return "RATE " + (bytes - first) + " " + (end - clock) / 1_000_000 + " SETUP " + (clock - start) / 1_000_000;
        }
    }

    private static String pace(String host, int port, long rate, int seconds, int timeout) throws Exception {
        long bytes = rate * seconds;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeout);
            socket.setSoTimeout(timeout);
            OutputStream out = socket.getOutputStream();
            out.write(("UP " + bytes + "\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            byte[] chunk = new byte[16384];
            long start = System.nanoTime();
            long sent = 0;
            while (sent < bytes) {
                int n = (int) Math.min(chunk.length, bytes - sent);
                out.write(chunk, 0, n);
                sent += n;
                long wait = start + sent * 1_000_000_000L / rate - System.nanoTime(); // until this many bytes are due
                if (wait > 0) Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
            }
            out.flush();
            String reply = line(socket.getInputStream());
            long ms = (System.nanoTime() - start) / 1_000_000;
            return reply.equals("GOT " + bytes) ? "PACED " + bytes + " " + ms : "ERROR the bulk server answered " + reply + " to " + bytes + " bytes";
        }
    }

    /** The lab's setup (scripts/e2e_env.sh), which the shell user may read: the proxies' address, ports and sign-ins. */
    private static Properties lab() throws IOException {
        Properties lab = new Properties();
        try (FileInputStream in = new FileInputStream(LAB_PROPERTIES)) {
            lab.load(in);
        }
        return lab;
    }

    private static final String LAB_PROPERTIES = "/data/local/tmp/proxyflow-e2e.properties";

    /** RFC 1928 and 1929: signs in by name and password, then asks for a tunnel to host:port by name, as ProxyFlow does. */
    private static void socks5(Socket socket, String user, String password, String host, int port) throws IOException {
        OutputStream out = socket.getOutputStream();
        DataInputStream in = new DataInputStream(socket.getInputStream()); // unbuffered: nothing past the reply is read
        out.write(new byte[] {5, 1, 2});
        out.flush();
        if (in.readUnsignedByte() != 5 || in.readUnsignedByte() != 2) throw new IOException("the proxy refused sign-in by name and password");
        byte[] name = user.getBytes(StandardCharsets.UTF_8);
        byte[] secret = password.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream signIn = new ByteArrayOutputStream();
        signIn.write(1);
        signIn.write(name.length);
        signIn.write(name, 0, name.length);
        signIn.write(secret.length);
        signIn.write(secret, 0, secret.length);
        out.write(signIn.toByteArray());
        out.flush();
        if (in.readUnsignedByte() != 1 || in.readUnsignedByte() != 0) throw new IOException("the proxy refused the sign-in");
        byte[] target = host.getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream request = new ByteArrayOutputStream();
        request.write(new byte[] {5, 1, 0, 3}, 0, 4);
        request.write(target.length);
        request.write(target, 0, target.length);
        request.write(port >> 8);
        request.write(port & 0xFF);
        out.write(request.toByteArray());
        out.flush();
        if (in.readUnsignedByte() != 5) throw new IOException("not a SOCKS5 reply");
        int reply = in.readUnsignedByte();
        if (reply != 0) throw new IOException("the proxy answered " + reply + " to the CONNECT");
        in.readUnsignedByte();
        int type = in.readUnsignedByte();
        int length;
        if (type == 1) {
            length = 4;
        } else if (type == 4) {
            length = 16;
        } else if (type == 3) {
            length = in.readUnsignedByte();
        } else {
            throw new IOException("SOCKS5 address type " + type);
        }
        in.readFully(new byte[length + 2]); // the bound address and port
    }

    /** An HTTP CONNECT to host:port by name, signed in (Basic), as ProxyFlow asks; the 200's head read to its end. */
    private static void httpConnect(Socket socket, String user, String password, String host, int port) throws IOException {
        String target = host + ":" + port;
        String credentials = Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().write(("CONNECT " + target + " HTTP/1.1\r\nHost: " + target + "\r\nProxy-Authorization: Basic "
                + credentials + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        InputStream in = socket.getInputStream();
        StringBuilder head = new StringBuilder();
        while (head.length() < 4 || !head.substring(head.length() - 4).equals("\r\n\r\n")) {
            int b = in.read();
            if (b == -1 || head.length() > 8192) throw new IOException("the proxy ended its answer early: " + head.toString().split("\r\n")[0]);
            head.append((char) b);
        }
        String status = head.toString().split("\r\n")[0];
        if (!status.startsWith("HTTP/1.1 200") && !status.startsWith("HTTP/1.0 200")) throw new IOException("the proxy answered " + status);
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream reply = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1 && b != '\n') reply.write(b);
        return reply.toString("US-ASCII").trim();
    }

    private static String connect(String host, int port, int timeout, boolean stamped) {
        InetSocketAddress address;
        try {
            address = new InetSocketAddress(InetAddress.getByName(host), port);
        } catch (Exception e) {
            return "UNRESOLVED " + e.getClass().getSimpleName();
        }
        // Stamped: when the connection began, on Android's clock (elapsedRealtime, the tests'), after the client's own
        // start-up, for a test that sets it against when the session changed state (M10-92).
        String began = stamped ? " AT " + SystemClock.elapsedRealtime() : "";
        long start = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(address, timeout);
            return "CONNECTED " + (System.nanoTime() - start) / 1_000_000 + began;
        } catch (Exception e) {
            return "FAILED " + (System.nanoTime() - start) / 1_000_000 + " " + e.getClass().getSimpleName() + began;
        }
    }

    private static String many(String host, int port, int count, int seconds, long load) throws Exception {
        // One lookup for them all, before the first connection: through ProxyFlow it answers one placeholder address.
        InetAddress address = InetAddress.getByName(host);
        List<Socket> open = new ArrayList<>();
        List<Long> openedAt = new ArrayList<>();
        int failed = 0;
        int drained = 0;
        String first = "";
        long start = System.nanoTime();
        try {
            for (int i = 0; i < count; i++) {
                Socket socket = new Socket();
                try {
                    if (load > 0) socket.setReceiveBufferSize(LOAD_RECEIVE_BUFFER); // before connecting: the window it offers
                    socket.connect(new InetSocketAddress(address, port), 15_000);
                    socket.getOutputStream().write(((load > 0 ? "DOWN " + load : "HOLD " + seconds) + "\n").getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    open.add(socket);
                    openedAt.add(System.nanoTime());
                } catch (IOException e) {
                    failed++;
                    if (first.isEmpty()) first = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()).replace('\n', ' ');
                    socket.close();
                }
            }
            long openingMs = (System.nanoTime() - start) / 1_000_000;
            // Each closes MANY_CLOSE_EARLY_S before SECONDS after it opened, so the server logs a hold closed by the client,
            // never ended early (the network-change evidence's sign, scripts/m7_evidence.py), and all are open together
            // from the last one opened until the first closes. A loaded one reads its bytes first, so the server logs it
            // sent in full (scripts/m7_evidence.py checks every download was).
            byte[] buffer = new byte[65536];
            for (int i = 0; i < open.size(); i++) {
                long waitMs = (openedAt.get(i) + (seconds - MANY_CLOSE_EARLY_S) * 1_000_000_000L - System.nanoTime()) / 1_000_000;
                if (waitMs > 0) Thread.sleep(waitMs);
                Socket socket = open.get(i);
                if (load > 0) {
                    try {
                        socket.setSoTimeout(30_000);
                        long received = 0;
                        int n;
                        while (received < load && (n = socket.getInputStream().read(buffer, 0, (int) Math.min(buffer.length, load - received))) != -1) received += n;
                        if (received == load) drained++;
                    } catch (IOException e) {
                        if (first.isEmpty()) first = "draining: " + e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()).replace('\n', ' ');
                    }
                }
                socket.close();
            }
            return "HELD " + open.size() + " OF " + count + " FAILED " + failed + " IN " + openingMs + (load > 0 ? " DRAINED " + drained : "")
                    + (first.isEmpty() ? "" : " " + first);
        } finally {
            for (Socket socket : open) socket.close();
        }
    }

    /** A loaded connection's receive buffer: Android rounds it up to its minimum, a few KiB, far below what it is sent. */
    private static final int LOAD_RECEIVE_BUFFER = 4096;

    private static final int MANY_CLOSE_EARLY_S = 2;

    private static String hold(String host, int port, int seconds) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 10_000);
            socket.setSoTimeout((seconds + 5) * 1000);
            socket.getOutputStream().write(("HOLD " + seconds + "\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            long start = System.nanoTime();
            String how;
            try {
                how = socket.getInputStream().read() == -1 ? "closed" : "data";
            } catch (SocketTimeoutException e) {
                how = "timeout";
            } catch (IOException e) {
                how = "reset";
            }
            return "ENDED " + (System.nanoTime() - start) / 1_000_000 + " " + how;
        }
    }

    private static String exchange(InputStream in, OutputStream out, String line) throws Exception {
        out.write((line + "\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        ByteArrayOutputStream reply = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1 && b != '\n') reply.write(b);
        return reply.toString("US-ASCII").trim();
    }

    /**
     * A connected UDP socket learns of an ICMP port unreachable on its next call: a receive throws
     * PortUnreachableException, and on Android a second send fails with ECONNREFUSED, which is the
     * same ICMP message reported by the kernel (the socket's pending error), so both mean the same.
     */
    private static String udp(String host, int port, int timeout) throws Exception {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(InetAddress.getByName(host), port);
            socket.setSoTimeout(timeout);
            byte[] probe = "proxyflow-udp".getBytes(StandardCharsets.US_ASCII);
            byte[] buffer = new byte[512];
            try {
                socket.send(new DatagramPacket(probe, probe.length));
                Thread.sleep(200);
                socket.send(new DatagramPacket(probe, probe.length));
                DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
                socket.receive(reply);
                return "REPLY " + new String(reply.getData(), 0, reply.getLength(), StandardCharsets.US_ASCII).trim();
            } catch (PortUnreachableException e) {
                return "PORT_UNREACHABLE";
            } catch (SocketTimeoutException e) {
                return "TIMEOUT";
            } catch (IOException e) {
                if (String.valueOf(e.getMessage()).contains("ECONNREFUSED")) return "PORT_UNREACHABLE";
                throw e;
            }
        }
    }

    private static String dns(String server, String name, int type, int timeout, boolean overTcp) throws Exception {
        byte[] query = query(name, type);
        byte[] answer;
        try {
            if (overTcp) {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(server, 53), timeout);
                    socket.setSoTimeout(timeout);
                    OutputStream out = socket.getOutputStream();
                    out.write(new byte[] {(byte) (query.length >> 8), (byte) query.length});
                    out.write(query);
                    out.flush();
                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    answer = new byte[in.readUnsignedShort()];
                    in.readFully(answer);
                }
            } else {
                try (DatagramSocket socket = new DatagramSocket()) {
                    socket.setSoTimeout(timeout);
                    socket.send(new DatagramPacket(query, query.length, InetAddress.getByName(server), 53));
                    DatagramPacket reply = new DatagramPacket(new byte[4096], 4096);
                    socket.receive(reply);
                    answer = new byte[reply.getLength()];
                    System.arraycopy(reply.getData(), 0, answer, 0, reply.getLength());
                }
            }
        } catch (SocketTimeoutException e) {
            return "TIMEOUT";
        }
        if (answer.length < 12 || answer[0] != query[0] || answer[1] != query[1] || (answer[2] & 0x80) == 0) {
            return "ERROR not an answer to the query";
        }
        int rcode = answer[3] & 0x0F;
        int count = ((answer[6] & 0xFF) << 8) | (answer[7] & 0xFF);
        int at = query.length; // the question is echoed as sent
        List<String> addresses = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            at = skipName(answer, at);
            int rtype = ((answer[at] & 0xFF) << 8) | (answer[at + 1] & 0xFF);
            int length = ((answer[at + 8] & 0xFF) << 8) | (answer[at + 9] & 0xFF);
            at += 10;
            if ((rtype == 1 && length == 4) || (rtype == 28 && length == 16)) {
                byte[] raw = new byte[length];
                System.arraycopy(answer, at, raw, 0, length);
                addresses.add(InetAddress.getByAddress(raw).getHostAddress());
            } else {
                addresses.add("type" + rtype);
            }
            at += length;
        }
        return "RCODE " + rcode + " ANSWERS " + (addresses.isEmpty() ? "-" : String.join(",", addresses));
    }

    private static byte[] query(String name, int type) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int id = new Random().nextInt(0xFFFF);
        out.write(id >> 8);
        out.write(id);
        out.write(0x01); // recursion desired
        out.write(0x00);
        out.write(0);
        out.write(1); // one question
        for (int i = 0; i < 6; i++) out.write(0);
        for (String label : name.split("\\.")) {
            byte[] bytes = label.getBytes(StandardCharsets.US_ASCII);
            out.write(bytes.length);
            out.write(bytes, 0, bytes.length);
        }
        out.write(0);
        out.write(type >> 8);
        out.write(type);
        out.write(0);
        out.write(1); // class IN
        return out.toByteArray();
    }

    private static int skipName(byte[] m, int at) {
        while (true) {
            int length = m[at] & 0xFF;
            if ((length & 0xC0) == 0xC0) return at + 2;
            if (length == 0) return at + 1;
            at += 1 + length;
        }
    }

    private static String resolve(String name) throws Exception {
        List<String> out = new ArrayList<>();
        for (InetAddress address : InetAddress.getAllByName(name)) out.add(address.getHostAddress());
        return "ADDRESSES " + String.join(",", out);
    }

    private static String http(String host, int port, String path, int timeout, boolean tls) throws Exception {
        Socket plain = new Socket();
        plain.connect(new InetSocketAddress(host, port), timeout);
        plain.setSoTimeout(timeout);
        Socket socket = plain;
        String front = "";
        if (tls) {
            // The phone's own trust store, as its browser uses it (no lab CA): the platform's default context.
            SSLSocket ssl = (SSLSocket) SSLContext.getDefault().getSocketFactory().createSocket(plain, host, port, true);
            SSLParameters parameters = ssl.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            ssl.setSSLParameters(parameters);
            ssl.startHandshake();
            Certificate leaf = ssl.getSession().getPeerCertificates()[0];
            StringBuilder sha = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(leaf.getEncoded())) sha.append(String.format("%02x", b));
            front = "HTTPS " + ssl.getSession().getProtocol() + " LEAF " + sha + " ";
            socket = ssl;
        }
        try (Socket s = socket) {
            OutputStream out = s.getOutputStream();
            out.write(("GET " + path + " HTTP/1.0\r\nHost: " + host + "\r\nUser-Agent: ProxyFlow-live-check\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while (all.size() < 65536 && (n = s.getInputStream().read(buffer)) != -1) all.write(buffer, 0, n);
            String response = all.toString("ISO-8859-1");
            int headersEnd = response.indexOf("\r\n\r\n");
            String statusLine = response.contains("\r\n") ? response.substring(0, response.indexOf("\r\n")) : response;
            String[] status = statusLine.split(" ");
            String code = status.length > 1 && statusLine.startsWith("HTTP/") ? status[1] : "?";
            String body = headersEnd < 0 ? "" : response.substring(headersEnd + 4);
            body = body.replaceAll("\\s+", " ").trim();
            if (body.length() > 200) body = body.substring(0, 200);
            return front + "HTTP " + code + " BODY " + body;
        }
    }

    private static String tls(String host, int port, String caPem, String line, int timeout) throws Exception {
        Certificate ca;
        try (FileInputStream in = new FileInputStream(caPem)) {
            ca = CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        store.setCertificateEntry("lab-ca", ca);
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trust.getTrustManagers(), null);
        Socket plain = new Socket();
        plain.connect(new InetSocketAddress(host, port), timeout);
        plain.setSoTimeout(timeout);
        try (SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket(plain, host, port, true)) {
            SSLParameters parameters = socket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS"); // the host name is checked, as browsers do
            socket.setSSLParameters(parameters);
            socket.startHandshake();
            Certificate leaf = socket.getSession().getPeerCertificates()[0];
            StringBuilder sha = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(leaf.getEncoded())) sha.append(String.format("%02x", b));
            String protocol = socket.getSession().getProtocol();
            String reply = exchange(socket.getInputStream(), socket.getOutputStream(), line);
            return "TLS " + protocol + " LEAF " + sha + " REPLY " + reply;
        }
    }
}
