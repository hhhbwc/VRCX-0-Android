package com.vrcx0.tablet;

import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import com.vrcx0.tablet.net.EventStreamClient;
import com.vrcx0.tablet.net.PinnedTls;
import com.vrcx0.tablet.net.RemoteTransport;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 网页与数据面之间的那道桥。
 *
 * ## 为什么必须有这一层
 *
 * 平板界面是 HTML，而数据面要求 **SPKI 证书钉**（服务器是自签证书）。
 * 网页里的 fetch / EventSource 做不到这件事：它们只走系统信任库，自签证书
 * 直接被拒，浏览器也不提供任何 pin API。所以请求必须由原生发出去 ——
 * 这个类就是那个出口，网页通过 {@code window.VRCXNative.*} 调用它。
 *
 * ## 为什么是"回调式"而不是"返回式"
 *
 * {@code @JavascriptInterface} 的方法在 JavaBridge 线程上被调用，返回值会**阻塞
 * 网页侧**直到返回。所以网络调用绝不能同步返回（那会把渲染线程一起冻住）。
 * 约定：每个方法收一个 {@code reqId}，立刻返回，结果稍后经
 * {@code window.__vrcxResolve(reqId, payloadJson)} 送回去（两个参数，见 bridge.js）。
 *
 * ## 凭据不出网
 *
 * token 存在原生（{@link ServerConfig}），由 {@link RemoteTransport} 注入
 * Authorization 头。网页**永远拿不到它**：configJson() 只回 hasToken 布尔。
 * 所以即使网页里有脚本，也没有那串东西可偷。
 *
 * ## 错误形状
 *
 * 无论成功失败，回给网页的 payload 都是**一个 JSON 字符串**，由 JS 侧 parse：
 *   - 成功：服务端的原始响应体（本身是 JSON）；
 *   - 传输层失败：{@code {"error":"...","kind":"transport"}}
 *   - HTTP 非 2xx：{@code {"error":"<服务端 message>","status":N,"kind":"http"}}
 * JS 只要看有没有 {@code error} 字段就知道成败，不必分辨 HTTP 语义。
 */
public final class DataBridge {

    /** 网页侧注册的全局名。改了要同步 bridge.js。 */
    public static final String JS_NAME = "VRCXNative";

    private final ServerConfig config;
    private final WebView web;
    private final ExecutorService worker = Executors.newFixedThreadPool(3);
    private EventStreamClient stream;

    public DataBridge(ServerConfig config, WebView web) {
        this.config = config;
        this.web = web;
    }

    // ------------------------------------------------------------ 同步：不发网络

    /**
     * 当前配置。**不含 token**，只有 hasToken。
     *
     * 同步是刻意的：读 SharedPreferences 是内存操作，走异步反而让网页启动时
     * 多一次往返；而这里没有阻塞风险。
     */
    @JavascriptInterface
    public String configJson() {
        return config.toJson();
    }

    /**
     * 保存配置。入参是 {@code {"serverAddress":...,"certificatePin":...,"token":...}}
     * 的 JSON 串；token 字段可选（留空表示不动已有的凭据）。
     */
    @JavascriptInterface
    public String saveConfig(String json) {
        try {
            JSONObject obj = new JSONObject(json == null ? "{}" : json);
            if (obj.has("serverAddress")) {
                // 规范化放在这里而不是网页里：端口按 scheme 补的规则属于协议，
                // 和手机端 normalizeBaseUrl 是同一份语义，只该有一处实现。
                config.saveAddress(PinnedTls.normalizeBaseUrl(obj.optString("serverAddress", "")));
            }
            if (obj.has("certificatePin")) {
                String raw = obj.optString("certificatePin", "");
                PinnedTls.Parsed parsed = PinnedTls.parse(raw);
                if (parsed.isInvalid()) {
                    // 带着一个永不匹配的钉去握手，只会在深处以一条语焉不详的
                    // 证书错误浮出来。在这里就拒掉，把原因说清楚。
                    return error("指纹格式不对：" + parsed.error);
                }
                config.savePin(parsed.isPin() ? parsed.pinBase64 : "");
            }
            if (obj.has("token") && !obj.optString("token", "").trim().isEmpty()) {
                config.saveToken(obj.optString("token", ""));
            }
            return config.toJson();
        } catch (Exception e) {
            return error("配置保存失败: " + e.getMessage());
        }
    }

    @JavascriptInterface
    public String clearToken() {
        config.clearToken();
        return config.toJson();
    }

    @JavascriptInterface
    public String forgetAll() {
        config.clear();
        return config.toJson();
    }

    /**
     * 打开事件流（{@code /v1/stream}）。
     *
     * 收回 DataBridge 注释里记录的「受控破例」：原本 JS 里 {@code new WebSocket} 直连，
     * token 只能拼进 query（会进服务器访问日志），且原生 WebView 的 WS 走系统证书库、
     * 自签被拒。现改为原生 WebSocket 客户端（EventStreamClient）：token 走
     * {@code Authorization} 头（不进 query，凭据不再泄漏），TLS 复用 PinnedTls 的证书钉
     * （自签可连）。收到的帧与状态经 window.__vrcxStreamFrame / __vrcxStreamOpen /
     * __vrcxStreamClose 推回网页；conn.js 的流逻辑对原生缺失是防御式的（typeof 检查）。
     */
    @JavascriptInterface
    public void openStream() {
        if (stream != null) stream.stop();
        final String addr = config.address();
        final String pin = config.pin();
        final String tok = config.token();
        stream = new EventStreamClient(new EventStreamClient.Listener() {
            @Override
            public void onState(EventStreamClient.State state) {
                final boolean opened = state == EventStreamClient.State.OPEN;
                final boolean closed = state == EventStreamClient.State.CLOSED
                        || state == EventStreamClient.State.FAILED;
                if (!opened && !closed) return; // CONNECTING 不推，避免抖动
                web.post(new Runnable() {
                    @Override
                    public void run() {
                        web.evaluateJavascript(
                                opened
                                        ? "window.__vrcxStreamOpen && window.__vrcxStreamOpen();"
                                        : "window.__vrcxStreamClose && window.__vrcxStreamClose();",
                                null);
                    }
                });
            }

            @Override
            public void onFrame(final String json) {
                web.post(new Runnable() {
                    @Override
                    public void run() {
                        web.evaluateJavascript(
                                "window.__vrcxStreamFrame && window.__vrcxStreamFrame("
                                        + RemoteTransport.quote(json) + ");",
                                null);
                    }
                });
            }

            @Override
            public void onError(final String message) {
                web.post(new Runnable() {
                    @Override
                    public void run() {
                        web.evaluateJavascript(
                                "window.__vrcxStreamError && window.__vrcxStreamError("
                                        + RemoteTransport.quote(message) + ");",
                                null);
                    }
                });
            }
        });
        stream.start(addr, pin, tok);
    }

    /** 关闭事件流。 */
    @JavascriptInterface
    public void closeStream() {
        if (stream != null) {
            stream.stop();
            stream = null;
        }
        web.post(new Runnable() {
            @Override
            public void run() {
                web.evaluateJavascript(
                        "window.__vrcxStreamClose && window.__vrcxStreamClose();", null);
            }
        });
    }

    // ------------------------------------------------------------ 异步：网络

    /** GET /v1/health —— 公开接口，不需要凭据，用来判断服务器可达/版本。 */
    @JavascriptInterface
    public void health(final String reqId) {
        run(reqId, new Call() {
            @Override
            public RemoteTransport.Response call(RemoteTransport t) throws Exception {
                return t.get(RemoteTransport.PATH_HEALTH);
            }
        });
    }

    /** GET /v1/auth/status —— 用 401/200 判断凭据是否还有效。 */
    @JavascriptInterface
    public void authStatus(final String reqId) {
        run(reqId, new Call() {
            @Override
            public RemoteTransport.Response call(RemoteTransport t) throws Exception {
                return t.get(RemoteTransport.PATH_AUTH_STATUS);
            }
        });
    }

    /**
     * GET /v1/auth/accounts —— 服务器上**已保存凭据**的账号，供「选择账号」用。
     *
     * 与手机端 LoginScreen 的顺序一致：服务器持有会话，所以常见路径是"点一个名字"
     * 而不是"敲用户名密码"。返回体里没有任何凭据（服务端只回
     * userId / displayName / username / iconUrl），选账号登录走的是
     * authLogin 带 userId 的那条分支。
     */
    @JavascriptInterface
    public void authAccounts(final String reqId) {
        run(reqId, new Call() {
            @Override
            public RemoteTransport.Response call(RemoteTransport t) throws Exception {
                return t.get(RemoteTransport.PATH_AUTH_ACCOUNTS);
            }
        });
    }

    @JavascriptInterface
    public void authLogout(final String reqId) {        run(reqId, new Call() {
            @Override
            public RemoteTransport.Response call(RemoteTransport t) throws Exception {
                return t.post(RemoteTransport.PATH_AUTH_LOGOUT, "{}");
            }
        });
    }

    /**
     * POST /v1/auth/login。
     *
     * 返回值就是这个接口的原始响应，包含 {@code {"status":"authenticated"}} /
     * {@code {"status":"challenge","attemptId":...,"methods":[...]}} /
     * {@code {"status":"failed",...}} 三种之一 —— 网页按 status 分支即可，
     * 那一套判别键在服务端是 #[serde(tag = "status")]，不要自己另造。
     */
    @JavascriptInterface
    public void authLogin(final String reqId, final String username, final String password,
                          final boolean saveCredentials, final String userId) {
        run(reqId, new Call() {
            @Override
            public RemoteTransport.Response call(RemoteTransport t) throws Exception {
                JSONObject body = new JSONObject();
                body.put("username", username == null ? "" : username);
                body.put("password", password == null ? "" : password);
                body.put("saveCredentials", saveCredentials);
                if (userId != null && !userId.trim().isEmpty()) body.put("userId", userId.trim());
                return t.post(RemoteTransport.PATH_AUTH_LOGIN, body.toString());
            }
        });
    }

    /** POST /v1/auth/2fa —— 二次验证（mfa / emailOtp / totp 等）。 */
    @JavascriptInterface
    public void auth2fa(final String reqId, final String attemptId, final String method,
                        final String code) {
        run(reqId, new Call() {
            @Override
            public RemoteTransport.Response call(RemoteTransport t) throws Exception {
                JSONObject body = new JSONObject();
                body.put("attemptId", attemptId == null ? "" : attemptId);
                body.put("method", method == null ? "" : method);
                body.put("code", code == null ? "" : code);
                return t.post(RemoteTransport.PATH_AUTH_2FA, body.toString());
            }
        });
    }

    /**
     * POST /v1/tenants —— 领一个租户名额。
     *
     * 成功时**凭据直接存进原生**，回给网页的只有 tenantId/label（不带 token）。
     * 这是"凭据不出网"那条约定的落点。
     */
    @JavascriptInterface
    public void claimTenant(final String reqId, final String label, final String inviteCode) {
        run(reqId, new Call() {
            @Override
            public RemoteTransport.Response call(RemoteTransport t) throws Exception {
                RemoteTransport.Response r = t.post(
                        RemoteTransport.PATH_TENANTS,
                        RemoteTransport.tenantEnvelope(
                                (label == null || label.trim().isEmpty()) ? "平板" : label.trim(),
                                inviteCode));
                if (r.isSuccessful()) {
                    JSONObject obj = new JSONObject(r.body);
                    String token = obj.optString("token", "");
                    if (!token.isEmpty()) config.saveToken(token);
                    // 回给网页时把 token 摘掉：它已经在原生配置里了，
                    // 没有任何理由再进一次 JS 的堆。
                    obj.remove("token");
                    obj.put("hasToken", !token.isEmpty());
                    return new RemoteTransport.Response(r.status, obj.toString());
                }
                return r;
            }
        });
    }

    /**
     * POST /v1/command —— 唯一的业务命令出口。
     *
     * {@code argsJson} 是命令参数对象；服务端两种参数形态（平铺 / 包 input）的
     * 处理在服务端侧，这里原样透传即可 —— 注意手机端的 ArgForms 是脚本生成的
     * 客户端侧包装表，平板这边由 bridge.js 按同一张表在 JS 里包，见 scripts/bridge.js。
     */
    @JavascriptInterface
    public void command(final String reqId, final String command, final String argsJson) {
        run(reqId, new Call() {
            @Override
            public RemoteTransport.Response call(RemoteTransport t) throws Exception {
                return t.post(RemoteTransport.PATH_COMMAND,
                        RemoteTransport.commandEnvelope(command, argsJson));
            }
        });
    }

    /** 逃生口：需要某个尚未单独封装的路由时用。仍然走同一套钉与凭据。 */
    @JavascriptInterface
    public void request(final String reqId, final String method, final String path,
                        final String bodyJson) {
        run(reqId, new Call() {
            @Override
            public RemoteTransport.Response call(RemoteTransport t) throws Exception {
                String m = (method == null || method.trim().isEmpty()) ? "GET" : method.trim().toUpperCase();
                return t.request(m, path, bodyJson);
            }
        });
    }

    // ------------------------------------------------------------ 内部

    private interface Call {
        RemoteTransport.Response call(RemoteTransport transport) throws Exception;
    }

    private void run(final String reqId, final Call call) {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                RemoteTransport transport;
                try {
                    transport = new RemoteTransport(config.address(), config.pin(), config.token());
                } catch (Exception e) {
                    resolve(reqId, "{\"error\":" + RemoteTransport.quote(e.getMessage())
                            + ",\"kind\":\"config\"}");
                    return;
                }
                try {
                    RemoteTransport.Response r = call.call(transport);
                    if (r.isSuccessful()) {
                        resolve(reqId, r.body == null || r.body.isEmpty() ? "{}" : r.body);
                    } else {
                        resolve(reqId, "{\"error\":"
                                + RemoteTransport.quote(readMessage(r.body))
                                + ",\"status\":" + r.status + ",\"kind\":\"http\"}");
                    }
                } catch (Exception e) {
                    // 连不上 / 握手失败 / 指纹不匹配都落在这里。指纹不匹配是最值得
                    // 说清楚的一种：让人一眼看出该去核对什么。
                    String hint = e.getMessage() == null ? e.toString() : e.getMessage();
                    if (hint.contains("PKIX") || hint.contains("Trust") || hint.contains("certificate")) {
                        hint = "TLS 校验失败（" + hint + "）。核对证书指纹是否与服务器一致。";
                    }
                    resolve(reqId, "{\"error\":" + RemoteTransport.quote(hint)
                            + ",\"kind\":\"transport\"}");
                }
            }
        });
    }

    /** 服务端把失败原因写在 {"message":...} 里，取出来直接给用户看。 */
    private static String readMessage(String body) {
        if (body == null || body.trim().isEmpty()) return "请求失败";
        try {
            String msg = new JSONObject(body).optString("message", "");
            if (!msg.isEmpty()) return msg;
        } catch (Exception ignored) {
            // 不是 JSON 就直接把原文给出去，总比一句"请求失败"有信息量。
        }
        return body;
    }

    /** 把结果送回网页。必须回到 UI 线程再 evaluateJavascript。 */
    private void resolve(final String reqId, final String payloadJson) {
        final String script = "window.__vrcxResolve("
                + RemoteTransport.quote(reqId == null ? "" : reqId)
                + ","
                + RemoteTransport.quote(payloadJson) + ");";
        web.post(new Runnable() {
            @Override
            public void run() {
                try {
                    web.evaluateJavascript(script, null);
                } catch (Exception ignored) {
                    // WebView 已销毁（旋转/退出）时静默丢弃，不该因此崩掉进程。
                }
            }
        });
    }

    private static String error(String message) {
        return "{\"error\":" + RemoteTransport.quote(message) + ",\"kind\":\"config\"}";
    }

    public void shutdown() {
        worker.shutdownNow();
    }
}
