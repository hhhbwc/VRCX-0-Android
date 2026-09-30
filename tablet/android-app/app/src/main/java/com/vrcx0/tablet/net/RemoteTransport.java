package com.vrcx0.tablet.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;

/**
 * 平板壳访问数据面 HTTP 接口的唯一出口。
 *
 * 与手机端 CommandClient 走的是同一套协议，只是没有 OkHttp：
 * 连接按 (地址, 指纹) 构造而不是做成单例 —— 指纹是绑在某一个主机上的，
 * 跨服务器复用一个客户端会把一台服务器的钉带到另一台上（手机端源码里
 * 对这一点有同样的注释）。
 *
 * 同样刻意用纯 JDK：能被宿主机 JDK 编译并直接对着真服务器验收。
 *
 * ## 凭据不出网
 *
 * token 只存在于这一层和原生配置里，**从不交给网页 JS**。网页只拿到
 * 命令的返回值；Authorization 头在原生侧注入。这比把 token 放进
 * localStorage 更安全 —— 网页里根本没有那串东西可偷。
 */
public final class RemoteTransport {

    public static final String PATH_HEALTH = "/v1/health";
    public static final String PATH_TENANTS = "/v1/tenants";
    public static final String PATH_COMMAND = "/v1/command";
    public static final String PATH_AUTH_STATUS = "/v1/auth/status";
    public static final String PATH_AUTH_ACCOUNTS = "/v1/auth/accounts";
    public static final String PATH_AUTH_LOGIN = "/v1/auth/login";
    public static final String PATH_AUTH_2FA = "/v1/auth/2fa";
    public static final String PATH_AUTH_LOGOUT = "/v1/auth/logout";

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    /**
     * 一次请求的结果：HTTP 状态码 + 原始响应体（成功体或服务端的 {"message":...}）。
     *
     * 构造函数是 public：调用方（如 DataBridge）在成功路径上需要**改写**响应体
     * （例如把租户响应里的 token 摘掉再回给网页），所以它得能造一个同样状态码的
     * 新结果。做成包内可见会让那个改写没法表达。
     */
    public static final class Response {
        public final int status;
        public final String body;

        public Response(int status, String body) {
            this.status = status;
            this.body = body;
        }

        public boolean isSuccessful() { return status >= 200 && status < 300; }

        @Override
        public String toString() { return "HTTP " + status + " " + body; }
    }

    private final String baseUrl;
    private final String pinBase64;
    private final String token;

    /**
     * @param baseUrl    已规范化的地址（见 {@link PinnedTls#normalizeBaseUrl}）
     * @param pinBase64  SPKI 指纹；空串表示不钉
     * @param token      租户凭据；空串表示尚未领取，请求不带 Authorization
     */
    public RemoteTransport(String baseUrl, String pinBase64, String token) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        this.baseUrl = base;

        PinnedTls.Parsed parsed = PinnedTls.parse(pinBase64 == null ? "" : pinBase64);
        if (parsed.isInvalid()) {
            // 宁可在这里炸，也不要带着一个永不匹配的钉去握手，然后在深处
            // 以一条语焉不详的证书错误浮出来。
            throw new IllegalArgumentException(parsed.error);
        }
        this.pinBase64 = parsed.pinBase64;
        this.token = token == null ? "" : token.trim();
    }

    public String baseUrl() { return baseUrl; }

    /** 是否已经在钉（https + 有效指纹）。明文 http 下指纹不生效，这里如实反映。 */
    public boolean isPinned() {
        return pinBase64 != null && baseUrl.toLowerCase().startsWith("https://");
    }

    public Response get(String path) throws IOException {
        return request("GET", path, null);
    }

    public Response post(String path, String bodyJson) throws IOException {
        return request("POST", path, bodyJson);
    }

    /**
     * 发一次请求。非 2xx **不抛异常** —— 服务端把失败原因写在
     * {@code {"message": ...}} 里，那是 UI 要显示的东西，不是异常。
     * 只有连不上、握手失败这类传输层问题才抛 IOException。
     */
    public Response request(String method, String path, String bodyJson) throws IOException {
        if (baseUrl.isEmpty()) throw new IOException("未配置服务器地址");

        String target = path.startsWith("/") ? baseUrl + path : baseUrl + "/" + path;
        HttpURLConnection conn = (HttpURLConnection) new URL(target).openConnection();
        try {
            conn.setRequestMethod(method);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("Accept", "application/json");
            if (!token.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }

            if (conn instanceof HttpsURLConnection) {
                HttpsURLConnection https = (HttpsURLConnection) conn;
                javax.net.ssl.SSLSocketFactory factory = null;
                try {
                    factory = PinnedTls.socketFactory(pinBase64);
                } catch (Exception e) {
                    throw new IOException("无法构造 TLS 上下文: " + e.getMessage(), e);
                }
                if (factory != null) https.setSSLSocketFactory(factory);
                javax.net.ssl.HostnameVerifier verifier = PinnedTls.hostnameVerifier(pinBase64);
                if (verifier != null) https.setHostnameVerifier(verifier);
            }

            if (bodyJson != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] payload = bodyJson.getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(payload.length);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(payload);
                }
            }

            int status = conn.getResponseCode();
            InputStream stream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            return new Response(status, readAll(stream));
        } finally {
            conn.disconnect();
        }
    }

    private static String readAll(InputStream stream) throws IOException {
        if (stream == null) return "";
        try (InputStream in = stream) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) buffer.write(chunk, 0, read);
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** `POST /v1/command` 的信封：{@code {"command": ..., "args": ...}}。 */
    public static String commandEnvelope(String command, String argsJson) {
        String args = (argsJson == null || argsJson.trim().isEmpty()) ? "{}" : argsJson.trim();
        return "{\"command\":" + quote(command) + ",\"args\":" + args + "}";
    }

    /** `POST /v1/tenants` 的信封；inviteCode 为空时**整个字段不发**。 */
    public static String tenantEnvelope(String label, String inviteCode) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"label\":").append(quote(label));
        if (inviteCode != null && !inviteCode.trim().isEmpty()) {
            sb.append(",\"inviteCode\":").append(quote(inviteCode.trim()));
        }
        sb.append('}');
        return sb.toString();
    }

    /**
     * 一个最小 JSON 字符串字面量编码器。
     *
     * 不能用 org.json / android.util.JsonWriter：这两个只在 Android 上存在，
     * 而这一层要在宿主机 JVM 上也能编译（验收需要）。手写转义总共就这几条规则。
     */
    public static String quote(String raw) {
        if (raw == null) return "null";
        StringBuilder sb = new StringBuilder(raw.length() + 2);
        sb.append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
