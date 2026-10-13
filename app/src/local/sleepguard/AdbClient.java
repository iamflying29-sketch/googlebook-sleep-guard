package local.sleepguard;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.X509Certificate;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;

/**
 * Minimal adb client for Wireless debugging (adb over TLS), enough to run one shell command.
 * Protocol: CNXN -> server STLS -> client STLS -> TLS 1.3 with our client certificate (whose key
 * was paired with the device) -> server CNXN -> OPEN "exec:cmd" -> WRTE... -> CLSE.
 */
final class AdbClient implements Closeable {
    private static final int A_CNXN = 0x4e584e43, A_STLS = 0x534c5453, A_OPEN = 0x4e45504f,
            A_OKAY = 0x59414b4f, A_WRTE = 0x45545257, A_CLSE = 0x45534c43, A_AUTH = 0x48545541;
    private static final int A_VERSION = 0x01000001, A_STLS_VERSION = 0x01000000, MAX_PAYLOAD = 1024 * 1024;

    private final Socket raw;
    private DataInputStream in;
    private OutputStream out;

    private static final class Msg {
        int cmd, arg0, arg1;
        byte[] data;
    }

    private AdbClient(Socket raw) {
        this.raw = raw;
    }

    /** Connects and completes the TLS handshake with a PKCS#12 key store holding the paired key. */
    static AdbClient connect(String host, int port, byte[] pkcs12, char[] password, int timeoutMs) throws Exception {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), timeoutMs);
        s.setSoTimeout(timeoutMs);
        s.setTcpNoDelay(true);
        AdbClient c = new AdbClient(s);
        try {
            c.in = new DataInputStream(s.getInputStream());
            c.out = s.getOutputStream();
            c.write(A_CNXN, A_VERSION, MAX_PAYLOAD, "host::\0".getBytes(StandardCharsets.UTF_8));
            Msg m = c.read();
            if (m.cmd == A_AUTH) throw new IOException("device wants legacy RSA auth (not Wireless debugging TLS)");
            if (m.cmd != A_STLS) throw new IOException("unexpected reply " + Integer.toHexString(m.cmd));
            c.write(A_STLS, A_STLS_VERSION, 0, new byte[0]);

            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(new java.io.ByteArrayInputStream(pkcs12), password);
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, password);
            // adbd lists hashes of paired keys as acceptable issuers; the stock key manager would then
            // offer no certificate, so always present ours.
            final X509KeyManager base = (X509KeyManager) kmf.getKeyManagers()[0];
            final String alias = ks.aliases().nextElement();
            KeyManager[] keyManagers = {new X509KeyManager() {
                public String chooseClientAlias(String[] t, java.security.Principal[] i, Socket s2) { return alias; }
                public String chooseServerAlias(String t, java.security.Principal[] i, Socket s2) { return null; }
                public X509Certificate[] getCertificateChain(String a2) { return base.getCertificateChain(a2); }
                public String[] getClientAliases(String t, java.security.Principal[] i) { return new String[]{alias}; }
                public String[] getServerAliases(String t, java.security.Principal[] i) { return null; }
                public java.security.PrivateKey getPrivateKey(String a2) { return base.getPrivateKey(a2); }
            }};
            // adbd's certificate is self-signed and not pinned by adb itself either.
            TrustManager[] trustAll = {new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) { }
                public void checkServerTrusted(X509Certificate[] c, String a) { }
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }};
            SSLContext ctx = SSLContext.getInstance("TLSv1.3");
            ctx.init(keyManagers, trustAll, null);
            SSLSocket tls = (SSLSocket) ctx.getSocketFactory().createSocket(s, host, port, true);
            tls.setUseClientMode(true);
            tls.setEnabledProtocols(new String[]{"TLSv1.3"});
            tls.startHandshake();
            c.in = new DataInputStream(tls.getInputStream());
            c.out = tls.getOutputStream();
            m = c.read();
            if (m.cmd != A_CNXN) throw new IOException("no CNXN after TLS (key not paired?)");
            // Connecting is quick; a command may legitimately run quietly for a while (start.sh waits
            // up to ~30 s for another start/stop to finish, and a stop up to ~10 s), so reads get a
            // generous limit from here on - still bounded.
            s.setSoTimeout(75000);
            return c;
        } catch (Exception e) {
            c.close();
            throw e;
        }
    }

    /** Runs a command as the shell user and returns its output. */
    private int nextLocalId = 1;

    String exec(String command) throws IOException {
        int local = nextLocalId++; // a fresh stream id per command, so late packets can't mix streams
        write(A_OPEN, local, 0, ("exec:" + command + "\0").getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        int remote = 0;
        while (true) {
            Msg m = read();
            if (m.arg1 != local) continue; // belongs to an earlier stream
            if (m.cmd == A_OKAY) {
                remote = m.arg0;
            } else if (m.cmd == A_WRTE) {
                remote = m.arg0;
                result.write(m.data);
                write(A_OKAY, local, remote, new byte[0]);
            } else if (m.cmd == A_CLSE) {
                write(A_CLSE, local, m.arg0 != 0 ? m.arg0 : remote, new byte[0]);
                break;
            }
        }
        return result.toString(StandardCharsets.UTF_8.name());
    }

    private void write(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        int sum = 0;
        for (byte x : data) sum += x & 0xff;
        b.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(~cmd).put(data);
        out.write(b.array());
        out.flush();
    }

    private Msg read() throws IOException {
        byte[] h = new byte[24];
        in.readFully(h);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = b.getInt();
        m.arg0 = b.getInt();
        m.arg1 = b.getInt();
        int len = b.getInt();
        if (len < 0 || len > MAX_PAYLOAD) throw new IOException("bad payload length " + len);
        m.data = new byte[len];
        in.readFully(m.data);
        return m;
    }

    @Override
    public void close() {
        try {
            raw.close();
        } catch (IOException ignored) {
        }
    }

    /** VM-side test: AdbClient host port key.p12 password command */
    public static void main(String[] a) throws Exception {
        byte[] p12 = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(a[2]));
        try (AdbClient c = connect(a[0], Integer.parseInt(a[1]), p12, a[3].toCharArray(), 10000)) {
            System.out.print(c.exec(a[4]));
        }
    }
}
