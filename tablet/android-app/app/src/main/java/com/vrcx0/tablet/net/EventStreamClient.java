package com.vrcx0.tablet.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * 零依赖的 {@code /v1/stream} WebSocket 客户端。
 *
 * ## 为什么不在 JS 里 new WebSocket
 *
 * 浏览器 WebSocket 没法设 {@code Authorization} 头，token 只能拼进 query
 * （会进服务器访问日志，见服务端 {@code api.rs} 的注释）；原生 WebView 的
 * WebSocket 走系统证书库，自签证书直接被拒，而本 App 的握手是钉死的。
 * 所以握手放在原生：token 走 Bearer 头（不进 query，解决凭据泄漏），TLS 复用
 * {@link PinnedTls} 的证书钉（自签可连）。
 *
 * ## 约束
 *
 * 纯 JDK（不碰 android.*），与 {@link RemoteTransport} / {@link PinnedTls} 同一套，
 * 于是它能被宿主机的 JDK 直接编译。帧的"业务含义"完全不懂 —— 它只负责把收到的
 * text 消息原样交给监听者，重连、对账是 JS 侧的责任（对齐手机端 EventStreamClient）。
 *
 * ## 协议（RFC 6455 客户端侧）
 *
 *  · 握手 = HTTP/1.1 GET，带 Upgrade / Sec-WebSocket-Key / Authorization: Bearer；
 *  · 服务端只发 text（opcode 0x1）帧 + 周期 ping（0x9）+ close（0x8），且服务端→客户端
 *    不掩码；客户端→服务端（pong / close）必须掩码；
 *  · 支持分片（opcode 0x0 续帧累积到 FIN=1 才交付一条消息）；
 *  · 服务端不重放事件，滞后/重连后的全量重查是调用方的事。
 */
public final class EventStreamClient {

    public enum State { CONNECTING, OPEN, CLOSED, FAILED }

    public interface Listener {
        /** 连接状态变化。在 IO 线程回调，实现方需自行切回 UI 线程。 */
        void onState(State state);
        /** 一条完整 text 消息（已拼好分片）。 */
        void onFrame(String json);
        /** 传输层错误（可读给用户）。 */
        void onError(String message);
    }

    private static final String PATH = "/v1/stream";
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final Listener listener;
    private final SecureRandom rng = new SecureRandom();

    private volatile Thread ioThread;
    private volatile Socket socket;
    private volatile OutputStream out;
    private volatile boolean running;

    public EventStreamClient(Listener listener) {
        this.listener = listener;
    }

    /** 启动连接（异步）。重复调用会先停掉旧的。 */
    public void start(String baseUrl, String pinBase64, String token) {
        stop();
        running = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                connect(normalize(baseUrl), pinBase64, token);
            }
        }, "vrcx-stream");
        t.setDaemon(true);
        ioThread = t;
        t.start();
    }

    /** 停止连接（同步关 socket + 打断 IO 线程）。 */
    public void stop() {
        running = false;
        Socket s = socket;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) { /* 已关 */ }
        }
        Thread t = ioThread;
        if (t != null) t.interrupt();
        ioThread = null;
    }

    // ---------------------------------------------------------------- 连接

    private void connect(String baseUrl, String pinBase64, String token) {
        listener.onState(State.CONNECTING);
        Socket sock = null;
        try {
            URI uri = new URI(baseUrl);
            String scheme = uri.getScheme().toLowerCase();
            String host = uri.getHost();
            int port = uri.getPort() < 0
                    ? (scheme.equals("https") ? 443 : 80)
                    : uri.getPort();

            if (scheme.equals("https")) {
                SSLSocketFactory factory = PinnedTls.socketFactory(pinBase64);
                SSLSocket ssl = (SSLSocket) (factory != null
                        ? factory.createSocket(host, port)
                        : SSLSocketFactory.getDefault().createSocket(host, port));
                ssl.startHandshake();
                sock = ssl;
            } else {
                sock = new Socket(host, port);
            }
            socket = sock;
            sock.setSoTimeout(0); // 长连接，存活靠服务端 ping；断网由 read 失败感知
            OutputStream os = sock.getOutputStream();
            out = os;
            InputStream is = sock.getInputStream();

            String key = randomKey();
            writeHandshake(os, host, port, key, token);
            if (!readHandshake(is, key)) {
                listener.onError("握手失败（非 101 或 Sec-WebSocket-Accept 不匹配）");
                listener.onState(State.FAILED);
                return;
            }
            listener.onState(State.OPEN);
            readLoop(is);
        } catch (Exception e) {
            if (running) {
                listener.onError(e.getMessage() == null ? e.toString() : e.getMessage());
                listener.onState(State.FAILED);
            }
        } finally {
            closeQuietly(sock);
            socket = null;
            out = null;
            if (running) listener.onState(State.CLOSED);
        }
    }

    private void writeHandshake(OutputStream os, String host, int port, String key, String token)
            throws IOException {
        String hostHeader = (port == 80 || port == 443)
                ? host : host + ":" + port;
        StringBuilder req = new StringBuilder();
        req.append("GET ").append(PATH).append(" HTTP/1.1\r\n");
        req.append("Host: ").append(hostHeader).append("\r\n");
        req.append("Upgrade: websocket\r\n");
        req.append("Connection: Upgrade\r\n");
        req.append("Sec-WebSocket-Key: ").append(key).append("\r\n");
        req.append("Sec-WebSocket-Version: 13\r\n");
        if (token != null && !token.isEmpty()) {
            req.append("Authorization: Bearer ").append(token).append("\r\n");
        }
        req.append("\r\n");
        os.write(req.toString().getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    /** 读到空行；校验 101 + Sec-WebSocket-Accept。
     *  必须逐字节读，不能用 BufferedReader：它会预读底层流，把紧跟在 101 响应之后的
     *  二进制帧字节吞进自己的内部缓冲，导致后面的 readLoop 丢帧（Hello 帧甚至首事件）。 */
    private boolean readHandshake(InputStream is, String key) throws IOException {
        MessageDigest sha1;
        try {
            sha1 = MessageDigest.getInstance("SHA-1");
        } catch (Exception e) {
            return false;
        }
        String expected = Base64.getEncoder()
                .encodeToString(sha1.digest((key + GUID).getBytes(StandardCharsets.UTF_8)));

        java.io.ByteArrayOutputStream head = new java.io.ByteArrayOutputStream();
        int b;
        while ((b = is.read()) != -1) {
            head.write(b);
            int n = head.size();
            if (n >= 4) {
                byte[] a = head.toByteArray();
                if (a[n - 4] == '\r' && a[n - 3] == '\n' && a[n - 2] == '\r' && a[n - 1] == '\n') {
                    break;
                }
            }
        }
        String headers = new String(head.toByteArray(), StandardCharsets.US_ASCII);
        String statusLine = null;
        String accept = null;
        for (String line : headers.split("\r\n")) {
            if (statusLine == null) statusLine = line;
            int c = line.indexOf(':');
            if (c < 0) continue;
            if (line.substring(0, c).trim().toLowerCase().equals("sec-websocket-accept")) {
                accept = line.substring(c + 1).trim();
            }
        }
        return statusLine != null && statusLine.contains("101") && expected.equals(accept);
    }

    // ---------------------------------------------------------------- 读帧

    private void readLoop(InputStream is) throws IOException {
        java.io.ByteArrayOutputStream frag = null; // 分片累积（从 opcode 0x1 起）
        while (running) {
            int b0 = is.read();
            if (b0 < 0) break;
            int b1 = is.read();
            if (b1 < 0) break;

            boolean fin = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0f;
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7f;
            if (len == 126) len = readU16(is);
            else if (len == 127) len = readU64(is);
            // 服务端→客户端不掩码（RFC 规定）；防御性读掉 mask key 不 unmask。
            if (masked) skip(is, 4);
            byte[] payload = readN(is, len);

            if (opcode == 0x8) break;                 // close
            if (opcode == 0x9) { sendFrame((byte) 0xA, payload); continue; } // ping → pong
            if (opcode == 0xA) continue;              // pong：忽略

            if (opcode == 0x1) {                      // text 首帧
                frag = new java.io.ByteArrayOutputStream();
                frag.write(payload, 0, payload.length);
            } else if (opcode == 0x0) {               // 续帧
                if (frag == null) continue;           // 没有首帧，丢弃
                frag.write(payload, 0, payload.length);
            } else {
                continue;                            // 其他 opcode 忽略
            }

            if (fin && frag != null) {
                String text = new String(frag.toByteArray(), StandardCharsets.UTF_8);
                frag = null;
                listener.onFrame(text);
            }
        }
    }

    // ---------------------------------------------------------------- 写帧（客户端→服务端，必须掩码）

    private void sendFrame(byte opcode, byte[] payload) {
        if (out == null) return;
        try {
            byte[] p = (payload == null) ? new byte[0] : payload;
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            b.write(0x80 | (opcode & 0x0f)); // FIN + opcode
            int len = p.length;
            if (len < 126) {
                b.write(0x80 | len); // MASK=1
            } else if (len < 65536) {
                b.write(0x80 | 126);
                b.write((len >> 8) & 0xff);
                b.write(len & 0xff);
            } else {
                b.write(0x80 | 127);
                for (int i = 7; i >= 0; i--) b.write((int) ((len >>> (8 * i)) & 0xff));
            }
            byte[] mask = new byte[4];
            rng.nextBytes(mask);
            b.write(mask);
            for (int i = 0; i < len; i++) b.write(p[i] ^ mask[i & 3]);
            synchronized (this) {
                out.write(b.toByteArray());
                out.flush();
            }
        } catch (IOException ignored) {
            // 写失败会在下一次 read 时以断连体现，这里不单独处理
        }
    }

    // ---------------------------------------------------------------- 小工具

    private static String normalize(String baseUrl) {
        String v = baseUrl == null ? "" : baseUrl.trim();
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v.isEmpty() ? "/" : v;
    }

    private String randomKey() {
        byte[] raw = new byte[16];
        rng.nextBytes(raw);
        return Base64.getEncoder().encodeToString(raw);
    }

    private static long readU16(InputStream is) throws IOException {
        int hi = is.read();
        int lo = is.read();
        if (hi < 0 || lo < 0) throw new IOException("流意外结束");
        return ((long) (hi & 0xff) << 8) | (lo & 0xff);
    }

    private static long readU64(InputStream is) throws IOException {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            int b = is.read();
            if (b < 0) throw new IOException("流意外结束");
            v = (v << 8) | (b & 0xff);
        }
        return v;
    }

    private static byte[] readN(InputStream is, long len) throws IOException {
        if (len < 0 || len > 1L << 30) throw new IOException("帧过长");
        byte[] buf = new byte[(int) len];
        int off = 0;
        while (off < buf.length) {
            int n = is.read(buf, off, buf.length - off);
            if (n < 0) throw new IOException("流意外结束");
            off += n;
        }
        return buf;
    }

    private static void skip(InputStream is, int n) throws IOException {
        for (int i = 0; i < n; i++) {
            if (is.read() < 0) throw new IOException("流意外结束");
        }
    }

    private static void closeQuietly(Socket s) {
        if (s == null) return;
        try { s.close(); } catch (IOException ignored) { /* 已关 */ }
    }
}
