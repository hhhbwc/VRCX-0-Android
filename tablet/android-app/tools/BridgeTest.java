import com.vrcx0.tablet.net.PinnedTls;
import com.vrcx0.tablet.net.RemoteTransport;

import java.io.IOException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * com.vrcx0.tablet.net 的宿主机验收。
 *
 * 这一层刻意写成纯 JDK，就是为了能在这里被直接编译、并对着**真服务器**跑 ——
 * 不是"能编译就算过"，而是真的证明：
 *
 *   1. 地址规范化 / 指纹解析的边界行为；
 *   2. 指纹命中时能连上自签证书的服务器并拿到 200；
 *   3. 指纹错的时候被拒（不然钉就是装饰）；
 *   4. 没配指纹时也被拒（默认校验确实还在，没被顺手关掉）；
 *   5. 没带租户凭据时 /v1/auth/status 与 /v1/command 返回 401
 *      （证明 Bearer 这条管线接对了地方）。
 *
 * ⚠️ 只调用**无副作用**的接口：/v1/health（公开）、/v1/auth/status 与
 * /v1/command（无凭据必然 401）。**绝不**碰 POST /v1/tenants ——
 * 那会在用户的服务器上真的占掉一个租户名额。
 *
 * 用法：
 *   javac -d out <两个 net 源文件> tools/BridgeTest.java
 *   java -cp out BridgeTest [https://host]
 */
public final class BridgeTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        // 强制 UTF-8 输出：中文 Windows 控制台默认 GBK，直接 println 会把断言名
        // 变成乱码，而乱码的验收日志等于没有日志。重定向到文件后按 UTF-8 读即可。
        System.setOut(new java.io.PrintStream(
                new java.io.FileOutputStream(java.io.FileDescriptor.out), true,
                java.nio.charset.StandardCharsets.UTF_8));

        String base = args.length > 0 ? args[0] : "https://<server-ip>";
        System.out.println("=== com.vrcx0.tablet.net 宿主机验收 ===");
        System.out.println("目标: " + base);
        System.out.println();

        unitTests();
        liveTests(base);

        System.out.println();
        System.out.println("合计 " + (pass + fail) + " 项，PASS " + pass + "，FAIL " + fail);
        if (fail > 0) System.exit(1);
    }

    // ------------------------------------------------------------------ 单元

    private static void unitTests() {
        System.out.println("--- 地址规范化（对应服务端 normalize_base_url）---");

        check("裸主机名补 http:// 且补 :8790（与手机端 ServerConfigStore.kt 一致）",
                "http://192.168.1.9:8790".equals(PinnedTls.normalizeBaseUrl("192.168.1.9")));
        check("https 不补端口（隐含 443）",
                "https://example.com".equals(PinnedTls.normalizeBaseUrl("https://example.com")));
        check("https 带路径不补端口",
                "https://example.com/api".equals(PinnedTls.normalizeBaseUrl("https://example.com/api")));
        check("末尾斜杠被吃掉",
                "https://example.com".equals(PinnedTls.normalizeBaseUrl("https://example.com/")));
        check("首尾空白被吃掉",
                "https://example.com".equals(PinnedTls.normalizeBaseUrl("  https://example.com  ")));
        check("空串仍是空串", "".equals(PinnedTls.normalizeBaseUrl("   ")));

        System.out.println("--- 指纹解析 ---");

        String good = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="; // 32 字节
        PinnedTls.Parsed p = PinnedTls.parse(good);
        check("合法 base64 指纹被接受", p.isPin() && good.equals(p.pinBase64));

        PinnedTls.Parsed blank = PinnedTls.parse("   ");
        check("空输入 = 不钉（不是错误）", blank.isBlank());

        PinnedTls.Parsed hex = PinnedTls.parse("3A:4B:5C:6D:7E:8F:90:A1:B2:C3:D4:E5:F6:07:18:29:3A:4B:5C:6D");
        check("带冒号的证书指纹被识破并给出提示",
                hex.isInvalid() && hex.error.contains("SPKI"));

        PinnedTls.Parsed shortPin = PinnedTls.parse("AAAA");
        check("长度不对（不是 32 字节）被拒", shortPin.isInvalid());

        PinnedTls.Parsed prefixed = PinnedTls.parse("sha256/" + good);
        check("OkHttp 风格的 sha256/ 前缀被剥掉",
                prefixed.isPin() && good.equals(prefixed.pinBase64));

        PinnedTls.Parsed trailing = PinnedTls.parse(good.substring(0, good.length() - 1));
        check("被吃掉 '=' 补位后仍可用（openssl/聊天软件会丢）",
                trailing.isPin() && good.equals(trailing.pinBase64));

        System.out.println("--- JSON 字面量 ---");
        check("引号与换行被转义",
                "\"a\\\"b\\nc\"".equals(RemoteTransport.quote("a\"b\nc")));
        check("command 信封形状正确",
                "{\"command\":\"app__x\",\"args\":{\"a\":1}}"
                        .equals(RemoteTransport.commandEnvelope("app__x", "{\"a\":1}")));
        check("args 为空时补 {}",
                "{\"command\":\"app__x\",\"args\":{}}"
                        .equals(RemoteTransport.commandEnvelope("app__x", "")));
        check("inviteCode 为空时整字段不发",
                "{\"label\":\"t\"}".equals(RemoteTransport.tenantEnvelope("t", "")));
        check("inviteCode 有值时发出",
                "{\"label\":\"t\",\"inviteCode\":\"C1\"}".equals(RemoteTransport.tenantEnvelope("t", " C1 ")));
    }

    // ------------------------------------------------------------------ 真机

    private static void liveTests(String base) {
        System.out.println();
        System.out.println("--- 对真服务器的 TLS 行为 ---");

        String realPin;
        try {
            realPin = derivePinWithTrustAll(base);
        } catch (Exception e) {
            fail("先用 trust-all 取服务器证书以推导指纹", e.toString());
            System.out.println("（网络不可达，跳过后续在线验收）");
            return;
        }
        check("能从服务器取到证书并算出 SPKI 指纹",
                realPin != null && realPin.length() == 44);

        // 1. 指纹命中 → 应该通
        RemoteTransport pinned = new RemoteTransport(base, realPin, "");
        check("isPinned() 在 https + 有效指纹下为 true", pinned.isPinned());
        try {
            RemoteTransport.Response r = pinned.get(RemoteTransport.PATH_HEALTH);
            boolean ok = r.status == 200 && r.body.contains("\"status\"");
            check("指纹命中 → GET /v1/health 200 且是健康报告", ok);
            if (!ok) System.out.println("       实际: " + r);
        } catch (Exception e) {
            fail("指纹命中 → GET /v1/health 应成功", e.toString());
        }

        // 2. 指纹错 → 必须被拒
        // 确定不同：首字符换掉，解码出的字节必然变，因此摘要必然不相等。
        String wrong = (realPin.charAt(0) == 'A' ? "B" : "A") + realPin.substring(1);
        boolean rejected;
        String detail;
        try {
            new RemoteTransport(base, wrong, "").get(RemoteTransport.PATH_HEALTH);
            rejected = false;
            detail = "居然连上了";
        } catch (IOException e) {
            rejected = true;
            detail = e.getClass().getSimpleName();
        }
        check("指纹错 → 被拒（否则钉形同虚设）", rejected, detail);

        // 3. 没配指纹 → 自签证书必须被系统默认校验挡住
        boolean rejectedNoPin;
        String detailNoPin;
        try {
            new RemoteTransport(base, "", "").get(RemoteTransport.PATH_HEALTH);
            rejectedNoPin = false;
            detailNoPin = "居然连上了";
        } catch (IOException e) {
            rejectedNoPin = true;
            detailNoPin = e.getClass().getSimpleName();
        }
        check("没配指纹 → 自签证书被默认校验拒绝（说明默认校验没被关掉）",
                rejectedNoPin, detailNoPin);

        // 4. 凭据管线：无 token 时受保护接口必须 401
        try {
            RemoteTransport.Response s = pinned.get(RemoteTransport.PATH_AUTH_STATUS);
            check("无凭据 GET /v1/auth/status → 401（Bearer 接到了受保护路由上）",
                    s.status == 401, "实际 " + s.status + " " + s.body);
        } catch (Exception e) {
            fail("无凭据 GET /v1/auth/status → 期望 401", e.toString());
        }

        try {
            RemoteTransport.Response s = pinned.post(RemoteTransport.PATH_COMMAND,
                    RemoteTransport.commandEnvelope("app__feed_latest_query", "{}"));
            check("无凭据 POST /v1/command → 401（命令通道也受保护）",
                    s.status == 401, "实际 " + s.status + " " + s.body);
        } catch (Exception e) {
            fail("无凭据 POST /v1/command → 期望 401", e.toString());
        }

        // 5. 明文 http 下指纹不生效，必须如实反映（不能假装在钉）
        RemoteTransport plain = new RemoteTransport("http://192.168.1.9:8790", realPin, "");
        check("明文 http 下 isPinned() 为 false（不谎报安全）", !plain.isPinned());
    }

    /** 只为推导指纹而用的一次性 trust-all。仅存在于验收脚本里，服务代码不含。 */
    private static String derivePinWithTrustAll(String base) throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[] { new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}
            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {}
            @Override
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        } }, new java.security.SecureRandom());

        HttpsURLConnection conn = (HttpsURLConnection) new java.net.URL(base + "/v1/health").openConnection();
        conn.setSSLSocketFactory(ctx.getSocketFactory());
        conn.setHostnameVerifier(new javax.net.ssl.HostnameVerifier() {
            @Override
            public boolean verify(String h, SSLSession s) { return true; }
        });
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(15_000);
        try {
            conn.getResponseCode();
            Certificate[] chain = conn.getServerCertificates();
            if (chain == null || chain.length == 0) return null;
            return PinnedTls.spkiSha256Base64(chain[0]);
        } finally {
            conn.disconnect();
        }
    }

    // ------------------------------------------------------------------ 断言

    private static void check(String name, boolean ok) {
        check(name, ok, null);
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  PASS  " + name);
        } else {
            fail++;
            System.out.println("  FAIL  " + name + (detail == null ? "" : "  <" + detail + ">"));
        }
    }

    private static void fail(String name, String detail) {
        fail++;
        System.out.println("  FAIL  " + name + "  <" + detail + ">");
    }
}
