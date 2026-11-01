package app.proxyflow.e2e;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.FileInputStream;
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
import java.util.List;
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
 * </pre>
 *
 * TYPE is a DNS record type number (1 A, 28 AAAA, 16 TXT, 65 HTTPS). tls trusts only the CA in
 * CA_PEM and checks the host name, as a browser checks a site: what it prints is the certificate
 * the destination itself presented, end to end through the tunnel.
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

    private static String exchange(InputStream in, OutputStream out, String line) throws Exception {
        out.write((line + "\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        ByteArrayOutputStream reply = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1 && b != '\n') reply.write(b);
        return reply.toString("US-ASCII").trim();
    }

    /** A connected UDP socket learns of an ICMP port unreachable on its next receive. */
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
