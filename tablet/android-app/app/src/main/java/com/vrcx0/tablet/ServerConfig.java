package com.vrcx0.tablet;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 平板端自己持有的三样东西：连哪台服务器、怎么信任它的证书、服务器发给它的租户凭据。
 *
 * 与手机端 {@code ServerConfigStore.kt}（AndroidX DataStore）**键名与语义一一对应**，
 * 只是这里用 SharedPreferences —— 平板壳刻意不引 AndroidX，配置只有三个字符串，
 * 为此拉进 DataStore + coroutines 不划算。键名对齐是为了让两端可以互相搬迁配置。
 *
 * token 不是"登录产物"：它在任何人登录 VRChat 之前就从 POST /v1/tenants 拿到，
 * 用来向服务器标识这台客户端 —— 这正是让一个用户的会话不可能被另一个用户
 * 摸到的机制（服务器只存它的 SHA-256 摘要，永远发不出第二份）。
 *
 * 指纹放在这里和地址放这里的理由一样：两者都描述"服务器"，两者都由拿着手机的
 * 人决定。它是个公开值（公钥的哈希），不是需要藏起来的秘密。
 */
public final class ServerConfig {

    private static final String STORE = "server";
    private static final String KEY_ADDRESS = "server_address";
    private static final String KEY_TOKEN = "server_token";
    private static final String KEY_PIN = "server_certificate_pin";

    private final SharedPreferences prefs;

    public ServerConfig(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(STORE, Context.MODE_PRIVATE);
    }

    public String address() { return prefs.getString(KEY_ADDRESS, ""); }

    public String token() { return prefs.getString(KEY_TOKEN, ""); }

    public String pin() { return prefs.getString(KEY_PIN, ""); }

    /** 地址与凭据都齐了才算配好（指纹可选：只有自签 https 才需要）。 */
    public boolean isComplete() {
        return !address().trim().isEmpty() && !token().trim().isEmpty();
    }

    public void saveAddress(String address) {
        prefs.edit().putString(KEY_ADDRESS, address == null ? "" : address.trim()).apply();
    }

    public void savePin(String pin) {
        prefs.edit().putString(KEY_PIN, pin == null ? "" : pin.trim()).apply();
    }

    public void saveToken(String token) {
        prefs.edit().putString(KEY_TOKEN, token == null ? "" : token.trim()).apply();
    }

    /**
     * 丢掉凭据但保留地址。
     *
     * 服务器拒绝一个已存的 token 时用（租户被吊销，或 token 从另一台设备轮换过）。
     * 地址仍然有效，所以下一步是重新领一个名额，而不是让用户重敲一遍主机名。
     */
    public void clearToken() {
        prefs.edit().remove(KEY_TOKEN).apply();
    }

    /** 连地址带指纹一起忘掉。 */
    public void clear() {
        prefs.edit().remove(KEY_ADDRESS).remove(KEY_TOKEN).remove(KEY_PIN).apply();
    }

    /**
     * 给网页看的配置快照。
     *
     * **不含 token 本身，只有 hasToken**：凭据只活在原生侧，由 RemoteTransport
     * 注入 Authorization 头。网页里没有那串东西，也就无从被偷 —— 这比把它放进
     * localStorage 让 JS 自己拼请求头要安全。
     */
    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"serverAddress\":").append(RemoteTransportJson.quote(address()));
        sb.append(",\"certificatePin\":").append(RemoteTransportJson.quote(pin()));
        sb.append(",\"hasToken\":").append(!token().trim().isEmpty());
        sb.append(",\"complete\":").append(isComplete());
        sb.append('}');
        return sb.toString();
    }

    /** 复用 net 层那个手写 JSON 转义器，避免在这里再抄一遍。 */
    private static final class RemoteTransportJson {
        static String quote(String raw) {
            return com.vrcx0.tablet.net.RemoteTransport.quote(raw);
        }
    }
}
