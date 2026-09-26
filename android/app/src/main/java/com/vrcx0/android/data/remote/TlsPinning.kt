package com.vrcx0.android.data.remote

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

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
 * ## The handshake is where a pin has to be enforced
 *
 * A pin is enforced by the `X509TrustManager` the socket is built with, and by
 * nothing else. [trustManager] is that enforcement: with a fingerprint
 * configured it accepts the server's certificate if and only if the key matches,
 * and refuses every other certificate -- including ones the system store would
 * have accepted. With no fingerprint it is not installed at all and the
 * platform's own validation applies, unchanged.
 *
 * That "and only that key" half matters. A rule of the shape "accept on a match,
 * otherwise let the system decide" sounds safer and is not: against a
 * certificate the system store already trusts -- anything with a real CA behind
 * it -- it accepts a key the operator never pinned, so the pin becomes advisory
 * and a machine that can answer for the address while presenting *some* valid
 * certificate gets in. That is the exact attack a pin exists to prevent. Hence
 * a mismatch is refused rather than referred onward.
 *
 * ## Why there is no `CertificatePinner` here
 *
 * There used to be one, and it is worth stating why it is gone, because adding
 * it back looks obviously right and is not.
 *
 * OkHttp consults its `CertificatePinner` **after** the handshake has completed,
 * against the peer certificate chain it read out of the session. On the JVM, and
 * reliably reproducible against this project's own server, that chain comes back
 * **empty**: the same session, read one step earlier from inside OkHttp's own
 * hostname verifier, reports the certificate, while the `Handshake` object
 * built a moment before holds nothing. A pinner with no chain to compare against
 * matches nothing, so it fails closed -- every request dies with
 *
 * ```
 * SSLPeerUnverifiedException: Certificate pinning failure!
 *   Peer certificate chain:
 *   Pinned certificates for 192.168.1.1:
 *     sha256/<the correct pin>
 * ```
 *
 * which names the right pin and no reason, and cannot be acted on. Against a
 * certificate chain the system store trusts the same client reports four
 * certificates and the pinner works, so this is specific to the self-signed
 * case -- that is, to the only case this app has.
 *
 * So the pinner bought a second check that is unreachable where it is needed and
 * broken where it is not, at the cost of an opaque total failure. The trust
 * manager above already refuses anything but the pinned key, which is the
 * guarantee; one enforcement point that holds beats two where one cannot.
 * [TlsPinningTest] pins this decision down so it is not "fixed" back later.
 *
 * ## The name is not checked on a hit
 *
 * The certificate is issued to an IP address, and an address is not a name: a
 * hostname check compares against the `SAN` dNSName entries, so it would fail
 * on a perfectly correct certificate. On a pin hit the name check is therefore
 * skipped, for the same reason the desktop client skips it. The pin has already
 * established *which key* is on the other end, which is what a name was standing
 * in for.
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

    /** The only hash used here, and what Chromium's SPKI list takes. */
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
        // Re-encode so downstream receives the canonical form whatever was pasted.
        return Spki.Pin(Base64.getEncoder().encodeToString(bytes))
    }

    /**
     * The SPKI SHA-256 of a certificate's public key, Base64 encoded -- the
     * value a pin names, and the value [trustManager] compares against.
     *
     * Returns `null` for a certificate whose key cannot be encoded, which counts
     * as a mismatch everywhere below: an unreadable key is not a reason to trust
     * something.
     *
     * Also the only honest way to report a mismatch. When a connection fails
     * because the server was reinstalled with a new key, the fingerprint it
     * *did* present is what turns "it will not connect" into an actionable
     * message, so it goes into the exception.
     */
    fun spkiSha256Base64(cert: Certificate): String? = runCatching {
        // On X.509 this is the SubjectPublicKeyInfo DER, which is what the
        // server-side `openssl x509 -pubkey | openssl pkey -pubin -outform der`
        // prints the digest of.
        val spki = cert.publicKey.encoded
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(spki))
    }.getOrNull()

    /**
     * Trust the pinned key and nothing else.
     *
     * Returns `null` when [pinBase64] is empty, so a caller with no pin cannot
     * accidentally end up with a trust manager -- the absence of a pin stays the
     * absence of a trust manager, exactly as in [apply].
     */
    fun trustManager(pinBase64: String?): X509TrustManager? {
        if (pinBase64.isNullOrEmpty()) return null
        val fallback = defaultTrustManager() ?: return null
        return object : X509TrustManager {

            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                // Only reached for a client certificate, which this app never
                // presents. Delegated rather than accepted, so an unexpected one
                // is still refused.
                fallback.checkClientTrusted(chain, authType)
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val observed = chain?.firstOrNull()?.let { spkiSha256Base64(it) }
                if (observed != null && observed == pinBase64) return
                // Refused, not referred onward. See the class comment: falling
                // back to the system store here is what would turn the pin into
                // a suggestion.
                throw CertificateException(
                    "服务器公钥与配置的指纹不符。" +
                        "期望 $pinBase64，实际 ${observed ?: "无法读取服务器公钥"}。" +
                        "如果服务器重新签发过证书，需要更新指纹。"
                )
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = fallback.acceptedIssuers
        }
    }

    /**
     * A hostname verifier that accepts the name when the pin matches and defers
     * to the platform otherwise.
     *
     * Returns `null` when there is no pin, and the caller then leaves OkHttp's
     * own verifier in place -- an unpinned client must not gain a weaker name
     * check as a side effect of this file existing.
     *
     * With a pin configured the deferral is unreachable in practice, because
     * [trustManager] has already refused a mismatching certificate before the
     * name is looked at. It is kept because this verifier is also correct on its
     * own, and because the unpinned case must not change.
     */
    fun hostnameVerifier(pinBase64: String?): HostnameVerifier? {
        if (pinBase64.isNullOrEmpty()) return null
        return HostnameVerifier { hostname, session ->
            val hit = runCatching {
                val chain = session.peerCertificates
                chain.isNotEmpty() && spkiSha256Base64(chain[0]) == pinBase64
            }.getOrDefault(false)
            hit || HttpsURLConnection.getDefaultHostnameVerifier().verify(hostname, session)
        }
    }

    /**
     * Adds the pin to [builder], if there is one to add.
     *
     * Returns the builder untouched for an empty fingerprint, an unusable one,
     * or a plaintext address. The last case matters: there is no handshake to
     * enforce anything in over `http`, so attaching a trust manager there would
     * leave the code claiming a guarantee it does not provide. An unusable
     * fingerprint is likewise a configuration mistake, and [warning] is where the
     * user is told about it, rather than an exception surfacing from deep inside
     * a request.
     *
     * The result is that a client with no pin is exactly a client whose traffic
     * is unpinned -- there is no third state to misread.
     */
    fun apply(
        builder: OkHttpClient.Builder,
        baseUrl: String,
        fingerprint: String
    ): OkHttpClient.Builder {
        if (!baseUrl.startsWith("https://", ignoreCase = true)) return builder
        val pin = parse(fingerprint) as? Spki.Pin ?: return builder
        baseUrl.toHttpUrlOrNull() ?: return builder

        // The socket factory and the verifier travel together: the factory
        // decides which keys are acceptable at all, the verifier stops the name
        // check from rejecting a certificate that was issued to an address. If
        // the platform cannot build the socket factory, the builder is returned
        // unchanged and [warning] is what the user sees -- a client claiming to
        // be pinned while nothing enforces it is the one outcome worse than an
        // unpinned client.
        val trust = trustManager(pin.base64) ?: return builder
        val factory = socketFactory(trust) ?: return builder
        hostnameVerifier(pin.base64)?.let { builder.hostnameVerifier(it) }
        return builder.sslSocketFactory(factory, trust)
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

    /** The platform's own trust decisions, for everything the pin does not cover. */
    private fun defaultTrustManager(): X509TrustManager? = runCatching {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?) // null == the system's root store
        factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
    }.getOrNull()

    private fun socketFactory(trust: X509TrustManager): SSLSocketFactory? = runCatching {
        SSLContext.getInstance("TLS")
            .apply { init(null, arrayOf<TrustManager>(trust), SecureRandom()) }
            .socketFactory
    }.getOrNull()
}
