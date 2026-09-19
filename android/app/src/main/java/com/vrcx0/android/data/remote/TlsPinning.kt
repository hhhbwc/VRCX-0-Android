package com.vrcx0.android.data.remote

import okhttp3.CertificatePinner
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.util.Base64

/**
 * Pins the server's front end by its public key, so a self-signed certificate
 * can be trusted without trusting the phone's whole certificate store.
 *
 * The alternative to pinning is telling OkHttp to accept every certificate,
 * which is not a smaller version of this -- it is the opposite of it. Any
 * machine that can answer for the server's address could then read and rewrite
 * the traffic, credentials included, and the user would see nothing. A pin
 * names exactly one key: if the server's key is what answers, the connection
 * proceeds; if anything else answers, it fails loudly. A tamperer cannot make
 * their own key match a hash they cannot invert.
 *
 * This is the same trust model the project already uses for OpenWrt hosts
 * (`plink -hostkey SHA256:...`), which is why the fingerprint the operator
 * pastes is comparable across the two.
 *
 * ## Which fingerprint
 *
 * The **SPKI** digest: SHA-256 over the SubjectPublicKeyInfo, Base64 encoded.
 * `vps-tls-setup.sh` prints it, and `openssl x509` calls the other one
 * (SHA-256 over the whole DER certificate, displayed as hex with colons) a
 * "fingerprint" too. Both are 32 bytes; only the SPKI one is a pin. Pasting
 * the wrong one is the likeliest mistake here, so [parse] recognises that shape
 * by its colons and says so, rather than letting the connection fail later
 * with a message about certificates that does not explain which one to use.
 */
object TlsPinning {

    /** The only hash OkHttp pins by, and what Chromium's SPKI list takes. */
    const val HASH = "sha256"

    private const val KEY_BYTES = 32

    private const val HEX_HINT =
        "这看起来是证书本身的指纹（带冒号）。需要的是公钥 SPKI 指纹 —— 服务器脚本会把两个都打出来，取标注 SPKI 的那个。"
    private const val BASE64_HINT = "不是合法的 Base64，检查一下是不是复制漏了字符"
    private const val LENGTH_HINT = "SHA-256 指纹是 32 字节，Base64 后应为 44 个字符"
    private const val PLAINTEXT_HINT =
        "证书指纹只在 https 地址下生效。当前地址是明文 http，指纹不会起作用。"
    private const val UNPARSABLE_HINT = "服务器地址解析不出主机名，指纹无法应用"

    /** What a pasted fingerprint turned out to be. */
    sealed interface Spki {
        /** Nothing pasted: plaintext, or TLS left to the system store. */
        data object Blank : Spki

        /** The canonical Base64 SPKI digest, ready for a pin. */
        data class Pin(val base64: String) : Spki

        /** Pasted something, but not a usable SPKI digest. [reason] is for the user. */
        data class Invalid(val reason: String) : Spki
    }

    /**
     * Reads whatever the operator pasted.
     *
     * Deliberately forgiving about formatting -- leading/trailing whitespace,
     * the `sha256/` prefix OkHttp writes in its own error messages, and a
     * wrapped line are all accepted -- but strict about the digest itself.
     * Being lenient about the value would mean a pin that silently never
     * matches, which is worse than refusing it.
     */
    fun parse(input: String): Spki {
        var value = input.trim()
        if (value.isEmpty()) return Spki.Blank

        if (value.length > HASH.length + 1 &&
            value.regionMatches(0, "$HASH/", 0, HASH.length + 1, ignoreCase = true)
        ) {
            value = value.substring(HASH.length + 1)
        }
        value = value.filterNot { it.isWhitespace() }
        if (value.isEmpty()) return Spki.Blank

        if (value.contains(':')) return Spki.Invalid(HEX_HINT)

        // Decoding rather than counting characters: a length check alone would
        // accept 44 characters of anything, and the failure would only surface
        // as a TLS error against the real server. Padding is reconstructed
        // first, because openssl and chat apps both drop it.
        val unpadded = value.trimEnd('=')
        val padded = unpadded + "=".repeat((4 - unpadded.length % 4) % 4)

        val bytes = runCatching {
            Base64.getDecoder().decode(padded)
        }.getOrNull() ?: return Spki.Invalid(BASE64_HINT)

        if (bytes.size != KEY_BYTES) return Spki.Invalid(LENGTH_HINT)
        // Re-encode so OkHttp receives the canonical form whatever was pasted.
        return Spki.Pin(Base64.getEncoder().encodeToString(bytes))
    }

    /**
     * Adds the pin to [builder], if there is one to add.
     *
     * Returns the builder untouched for an empty fingerprint, an unusable one,
     * or a plaintext address. The last case matters: `CertificatePinner` is
     * never consulted over `http`, so attaching one there would leave the code
     * claiming a guarantee it does not provide. An unusable fingerprint is likewise
     * a configuration mistake, and [warning] is where the user is told about it,
     * rather than an exception surfacing from deep inside a request.
     *
     * The result is that a client with no pinner is exactly a client whose
     * traffic is not pinned -- there is no third state to misread.
     */
    fun apply(
        builder: OkHttpClient.Builder,
        baseUrl: String,
        fingerprint: String
    ): OkHttpClient.Builder {
        if (!baseUrl.startsWith("https://", ignoreCase = true)) return builder
        val pin = parse(fingerprint) as? Spki.Pin ?: return builder
        val host = baseUrl.toHttpUrlOrNull()?.host ?: return builder
        return builder.certificatePinner(
            CertificatePinner.Builder().add(host, "$HASH/${pin.base64}").build()
        )
    }

    /**
     * What is wrong with this address/pin pair, or `null` when it is workable.
     *
     * Two failures are worth catching before a request is made, because neither
     * announces itself afterwards:
     *
     *  - a pin given for an `http` address, where TLS is not in play at all and
     *    the pin is quietly ignored -- the user believes the traffic is
     *    protected and nothing is checking;
     *  - a fingerprint that cannot become a pin, which would otherwise show up
     *    as an unexplained connection error.
     */
    fun warning(baseUrl: String, fingerprint: String): String? =
        when (val parsed = parse(fingerprint)) {
            Spki.Blank -> null
            is Spki.Invalid -> parsed.reason
            is Spki.Pin -> when {
                !baseUrl.startsWith("https://", ignoreCase = true) -> PLAINTEXT_HINT
                baseUrl.toHttpUrlOrNull() == null -> UNPARSABLE_HINT
                else -> null
            }
        }
}
