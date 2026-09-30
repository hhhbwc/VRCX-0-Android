package com.vrcx0.tablet.net;

import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Base64;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 用公钥指纹（SPKI）钉住服务器，让自签证书可以不依赖系统信任库被信任。
 *
 * 这段是**纯 JDK**（只用 javax.net.ssl / java.util.Base64 / java.security），
 * 刻意不碰任何 Android API —— 于是它能被宿主机 JDK 直接编译并对着真服务器跑
 * 验收（见 tools/BridgeTest.java）。手机端 Protocol.kt 里选择 java.util.Base64
 * 而不用 android.util.Base64 是同一个理由。
 *
 * ## 语义：配了指纹就**只认那把钥匙**（2026-09-23 从「命中即信任、否则默认校验」收紧）
 *
 * 与手机端 TlsPinning.kt 对齐 —— 那边 2026-09-22 已经改成这个语义，本类是补齐 M4：
 *
 *  - 配了指纹：对端证书的 SPKI 指纹**等于它** → 放行（自签也放行）；
 *    **不等 → 直接抛**，不再交回系统默认校验。理论上"默认校验兜底"在自签部署下
 *    结果一样（自签永远过不了默认校验），但语义上有缺口：哪天服务器换了张
 *    系统信任库认得的证书（比如公开 CA 签的），旧语义会**静默放行**一个
 *    用户没点过头的钥匙 —— 那不是钉，是"钉着玩"。严格语义下钉就是唯一事实。
 *  - 没配指纹：完全不装自定义 TrustManager / HostnameVerifier，走系统默认。
 *
 * ## 名字不查
 *
 * 证书是给 IP 签的（没有 SAN=IP），所以主机名校验必然失败。指纹命中时
 * 必须跳过名字校验，否则连接建不起来 —— 这也是 PC 端那边"不查名字"的原因。
 */
public final class PinnedTls {

    /** 唯一使用的哈希，和 OkHttp / Chromium 的 SPKI 列表一致。 */
    public static final String HASH = "sha256";

    private static final int KEY_BYTES = 32;

    private static final String HEX_HINT =
            "这看起来是证书本身的指纹（带冒号）。需要的是公钥 SPKI 指纹 —— 服务器脚本会把两个都打出来，取标注 SPKI 的那个。";
    private static final String BASE64_HINT = "不是合法的 Base64，检查一下是不是复制漏了字符";
    private static final String LENGTH_HINT = "SHA-256 指纹是 32 字节，Base64 后应为 44 个字符";

    private PinnedTls() {}

    /** 一次指纹解析的结果：[pinBase64] 为 null 表示未启用钉（空输入）。 */
    public static final class Parsed {
        public final String pinBase64;
        public final String error;

        private Parsed(String pinBase64, String error) {
            this.pinBase64 = pinBase64;
            this.error = error;
        }

        public boolean isBlank() { return pinBase64 == null && error == null; }
        public boolean isPin() { return pinBase64 != null; }
        public boolean isInvalid() { return error != null; }
    }

    /**
     * 读运维粘贴进来的那一串指纹。
     *
     * 对格式刻意宽容（首尾空白、OkHttp 自己错误信息里的 {@code sha256/} 前缀、
     * 被换行折断的一行都接受），对摘要本身严格：宽松地接受一个值，等于得到一个
     * 永不匹配的钉，那比直接拒绝更糟。
     */
    public static Parsed parse(String input) {
        String value = input == null ? "" : input.trim();
        if (value.isEmpty()) return new Parsed(null, null);

        if (value.length() > HASH.length() + 1
                && value.regionMatches(true, 0, HASH + "/", 0, HASH.length() + 1)) {
            value = value.substring(HASH.length() + 1);
        }
        value = value.replaceAll("\\s+", "");
        if (value.isEmpty()) return new Parsed(null, null);

        if (value.indexOf(':') >= 0) return new Parsed(null, HEX_HINT);

        // 先解码再判长度：只看字符数会放过 44 个任意字符，而那种错误要等到
        // 对着真服务器握手时才以一条看不懂的 TLS 报错浮出来。补位先做，
        // 因为 openssl 和聊天软件都会把 '=' 吃掉。
        String unpadded = value;
        while (unpadded.endsWith("=")) unpadded = unpadded.substring(0, unpadded.length() - 1);
        int pad = (4 - unpadded.length() % 4) % 4;
        StringBuilder sb = new StringBuilder(unpadded);
        for (int i = 0; i < pad; i++) sb.append('=');

        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(sb.toString());
        } catch (IllegalArgumentException e) {
            return new Parsed(null, BASE64_HINT);
        }
        if (bytes.length != KEY_BYTES) return new Parsed(null, LENGTH_HINT);
        // 重新编码，让下游永远拿到规范形式，无论粘进来的是什么。
        return new Parsed(Base64.getEncoder().encodeToString(bytes), null);
    }

    /** 一个证书公钥的 SPKI SHA-256（Base64），就是可以被钉的那个值。 */
    public static String spkiSha256Base64(Certificate cert) {
        try {
            byte[] spki = cert.getPublicKey().getEncoded(); // X.509 下即 SubjectPublicKeyInfo DER
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(spki);
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 服务器地址规范化 —— 对应服务端自己的 normalize_base_url，也对应手机端
     * ServerConfigStore.kt 里那个同名函数：光敲主机名就够，端口按 scheme 补。
     *
     * 默认端口属于 scheme：8790 是服务器自己的**明文**监听口（前面该站一个反向
     * 代理，且永不可从公网直达）。把它接到 https:// 上是让 443 上的 TLS 前端把
     * 明文从后端端口交回来 —— 不可能成立的组合。所以 https:// 原样保留（隐含
     * 443），只有 http:// 才补 :8790。
     */
    public static String normalizeBaseUrl(String input) {
        String value = input == null ? "" : input.trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        if (value.isEmpty()) return "";

        String lower = value.toLowerCase();
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            value = "http://" + value;
            lower = value.toLowerCase();
        }
        String scheme = lower.substring(0, lower.indexOf("://"));
        String rest = value.substring(value.indexOf("://") + 3);
        String authority = rest;
        int slash = rest.indexOf('/');
        if (slash >= 0) authority = rest.substring(0, slash);

        if (!authority.isEmpty() && authority.indexOf(':') < 0 && !scheme.equals("https")) {
            value = value + ":8790";
        }
        return value;
    }

    /**
     * 让 {@code HttpsURLConnection} 用「只认钉住的那把钥匙」的信任链。
     *
     * [pinBase64] 为 null 时返回 null，调用方据此完全不做改动 —— 一个没有钉的
     * 客户端就是"流量没被钉"的客户端，不存在第三种需要误读的状态。
     *
     * 配了钉就**没有兜底**：指纹不匹配直接抛，让连接失败在用户配置错的
     * 那一刻暴露出来，而不是静默落到"系统认得就放行"上。
     */
    public static SSLSocketFactory socketFactory(String pinBase64) throws Exception {
        if (pinBase64 == null || pinBase64.isEmpty()) return null;

        X509TrustManager pinned = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType)
                    throws java.security.cert.CertificateException {
                throw new java.security.cert.CertificateException(
                        "client certificate requested — unexpected for this server");
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType)
                    throws java.security.cert.CertificateException {
                if (!matches(chain, pinBase64)) {
                    throw new java.security.cert.CertificateException(
                            "SPKI pin mismatch: 服务器公钥与配置的指纹不符");
                }
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                // 钉住的只有那一把钥匙；这里返回空不参与信任链构造，
                // 真正的判定全在 checkServerTrusted。
                return new X509Certificate[0];
            }
        };

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[] { pinned }, new java.security.SecureRandom());
        return ctx.getSocketFactory();
    }

    /**
     * 指纹命中时跳过主机名校验（证书是签给 IP 的，没有 SAN=IP）。
     * 配了钉就没有"落回默认校验"这条路：取不到证书或指纹不符都判 false。
     * [pinBase64] 为 null 时返回 null，调用方不设这个 verifier。
     */
    public static HostnameVerifier hostnameVerifier(final String pinBase64) {
        if (pinBase64 == null || pinBase64.isEmpty()) return null;
        return new HostnameVerifier() {
            @Override
            public boolean verify(String hostname, SSLSession session) {
                try {
                    Certificate[] chain = session.getPeerCertificates();
                    return chain != null && chain.length > 0
                            && pinBase64.equals(spkiSha256Base64(chain[0]));
                } catch (Exception ignored) {
                    return false;
                }
            }
        };
    }

    private static boolean matches(Certificate[] chain, String pinBase64) {
        if (chain == null || chain.length == 0) return false;
        return pinBase64.equals(spkiSha256Base64(chain[0]));
    }
}
