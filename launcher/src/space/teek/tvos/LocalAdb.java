package space.teek.tvos;

import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

/**
 * A tiny ADB client that talks to the TV's own debugging service (127.0.0.1:5555), the same way
 * LADB/Shizuku do. The first time, the TV asks "Allow debugging?" (tick Always allow); after that
 * teekOS can run shell commands the TV doesn't allow ordinary apps (force-stop apps, trim caches).
 * Works only where network debugging is on. Key pair lives in the app's private files.
 */
final class LocalAdb {
    private LocalAdb() { }

    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f,
            OKAY = 0x59414b4f, CLSE = 0x45534c43, WRTE = 0x45545257;
    private static final int VERSION = 0x01000000, MAXDATA = 4096;
    private static final int TOKEN = 1, SIGNATURE = 2, RSAPUBLICKEY = 3;
    /** ASN.1 DigestInfo prefix for SHA-1: the token is signed as if it were a SHA-1 digest. */
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    static final class NotAllowed extends IOException { NotAllowed(String m) { super(m); } }

    /** Run one shell command and return its output. Blocks (call off the main thread). */
    static String shell(Context c, String cmd, int authWaitMs) throws IOException {
        KeyPair kp = keys(c);
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", 5555), 1500);
            s.setSoTimeout(authWaitMs);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            send(out, CNXN, VERSION, MAXDATA, "host::\0".getBytes(StandardCharsets.UTF_8));
            boolean sentSig = false, sentKey = false;
            while (true) {
                Msg m = read(in);
                if (m.cmd == CNXN) break;
                if (m.cmd != AUTH || m.arg0 != TOKEN) throw new IOException("unexpected " + Integer.toHexString(m.cmd));
                if (!sentSig) {
                    send(out, AUTH, SIGNATURE, 0, sign(kp.getPrivate(), m.data));
                    sentSig = true;
                } else if (!sentKey) {   // key not known yet: the TV now asks the user
                    send(out, AUTH, RSAPUBLICKEY, 0, publicKey((RSAPublicKey) kp.getPublic()));
                    sentKey = true;
                } else throw new NotAllowed("debugging not allowed on the TV");
            }
            s.setSoTimeout(60000);
            send(out, OPEN, 1, 0, ("shell:" + cmd + "\0").getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream res = new ByteArrayOutputStream();
            int remote = 0;
            while (true) {
                Msg m = read(in);
                if (m.cmd == OKAY) { remote = m.arg0; continue; }
                if (m.cmd == WRTE) { res.write(m.data); send(out, OKAY, 1, m.arg0, new byte[0]); continue; }
                if (m.cmd == CLSE) { send(out, CLSE, 1, remote, new byte[0]); break; }
            }
            return res.toString("UTF-8");
        } catch (java.net.SocketTimeoutException e) {
            throw new NotAllowed("no answer to the debugging prompt");
        }
    }

    /** Is the TV's debugging service listening at all? */
    static boolean available() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", 5555), 400);
            return true;
        } catch (IOException e) { return false; }
    }

    // ---------------------------------------------------------------- wire format

    private static final class Msg { int cmd, arg0, arg1; byte[] data; }

    private static void send(OutputStream o, int cmd, int a0, int a1, byte[] data) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        int sum = 0;
        for (byte x : data) sum += x & 0xff;
        b.putInt(cmd).putInt(a0).putInt(a1).putInt(data.length).putInt(sum).putInt(~cmd).put(data);
        o.write(b.array());
        o.flush();
    }

    private static Msg read(InputStream in) throws IOException {
        DataInputStream d = new DataInputStream(in);
        byte[] h = new byte[24];
        d.readFully(h);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = b.getInt();
        m.arg0 = b.getInt();
        m.arg1 = b.getInt();
        int len = b.getInt();
        m.data = new byte[len];
        d.readFully(m.data);
        return m;
    }

    // ---------------------------------------------------------------- keys

    private static KeyPair keys(Context c) throws IOException {
        File pub = new File(c.getFilesDir(), "adbkey.pub.der"), priv = new File(c.getFilesDir(), "adbkey.der");
        try {
            KeyFactory kf = KeyFactory.getInstance("RSA");
            if (pub.exists() && priv.exists()) {
                return new KeyPair(kf.generatePublic(new X509EncodedKeySpec(readAll(pub))),
                        kf.generatePrivate(new PKCS8EncodedKeySpec(readAll(priv))));
            }
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            KeyPair kp = g.generateKeyPair();
            try (FileOutputStream o = new FileOutputStream(pub)) { o.write(kp.getPublic().getEncoded()); }
            try (FileOutputStream o = new FileOutputStream(priv)) { o.write(kp.getPrivate().getEncoded()); }
            return kp;
        } catch (IOException e) { throw e; } catch (Exception e) { throw new IOException(e); }
    }

    private static byte[] readAll(File f) throws IOException {
        try (FileInputStream i = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            new DataInputStream(i).readFully(b);
            return b;
        }
    }

    private static byte[] sign(PrivateKey k, byte[] token) throws IOException {
        try {
            Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            c.init(Cipher.ENCRYPT_MODE, k);   // PKCS#1 v1.5 type 1 with a private key = signature
            c.update(SHA1_PREFIX);
            return c.doFinal(token);
        } catch (Exception e) { throw new IOException(e); }
    }

    /** Android's adb public key format (mincrypt RSAPublicKey, base64) plus a name. */
    private static byte[] publicKey(RSAPublicKey k) {
        int words = 64;   // 2048-bit
        BigInteger n = k.getModulus(), r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = n.mod(r32).modInverse(r32).negate().mod(r32);
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words).putInt(n0inv.intValue());
        b.put(le(n, 256)).put(le(rr, 256));
        b.putInt(k.getPublicExponent().intValue());
        String s = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " teekOS@tv\0";
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] le(BigInteger v, int len) {
        byte[] be = v.toByteArray(), out = new byte[len];
        for (int i = 0; i < len && i < be.length; i++) out[i] = be[be.length - 1 - i];
        return out;
    }
}
