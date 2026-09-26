package com.vrcx0.android.data.remote

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSession

/**
 * The fingerprint a person pastes is the one thing between this client and a
 * connection that only *looks* encrypted, so the parsing rules are pinned down
 * here rather than left to the request path to discover.
 *
 * The value used throughout is a real 32-byte digest, Base64 encoded, because
 * the length checks are part of what is being tested.
 */
class TlsPinningTest {

    /** 32 bytes -> 44 Base64 characters, unpadded form 43. */
    private val canonical = "h6gbBGGvL1hO4+1zWPZ2kRPOJ7FQKfJcMFYdR1v1c9I="
    private val unpadded = canonical.trimEnd('=')

    @Test
    fun `nothing pasted is not an error`() {
        assertEquals(TlsPinning.Spki.Blank, TlsPinning.parse(""))
        assertEquals(TlsPinning.Spki.Blank, TlsPinning.parse("   "))
        assertNull(TlsPinning.warning("http://192.168.1.1:8790", ""))
    }

    @Test
    fun `canonical digest parses to itself`() {
        assertEquals(TlsPinning.Spki.Pin(canonical), TlsPinning.parse(canonical))
    }

    @Test
    fun `missing padding is restored rather than rejected`() {
        // openssl's output arrives wrapped through chat apps and shells often
        // enough that refusing it would be pedantic; the digest is unambiguous.
        assertEquals(TlsPinning.Spki.Pin(canonical), TlsPinning.parse(unpadded))
    }

    @Test
    fun `the sha256 prefix and stray whitespace are tolerated`() {
        assertEquals(TlsPinning.Spki.Pin(canonical), TlsPinning.parse("sha256/$canonical"))
        assertEquals(TlsPinning.Spki.Pin(canonical), TlsPinning.parse("SHA256/$canonical"))
        assertEquals(TlsPinning.Spki.Pin(canonical), TlsPinning.parse("  $canonical\n"))
        assertEquals(TlsPinning.Spki.Pin(canonical), TlsPinning.parse("$unpadded "))
    }

    @Test
    fun `the certificate fingerprint with colons is named as the wrong one`() {
        // openssl prints this one as `-fingerprint -sha256`, so it is the most
        // likely thing to be copied. It looks plausible and would never match.
        val wrong = "AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:" +
            "AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89"
        val parsed = TlsPinning.parse(wrong)
        assertTrue(parsed is TlsPinning.Spki.Invalid)
        assertTrue((parsed as TlsPinning.Spki.Invalid).reason.contains("SPKI"))
    }

    @Test
    fun `a digest of the wrong size is rejected`() {
        // 16 bytes: valid Base64, impossible SHA-256. Both the padded and the
        // padding-stripped spellings have to land on the same answer.
        assertTrue(TlsPinning.parse("h6gbBGGvL1hO4+1zWPZ2kRP==") is TlsPinning.Spki.Invalid)
        assertTrue(TlsPinning.parse("h6gbBGGvL1hO4+1zWPZ2kRP") is TlsPinning.Spki.Invalid)
        assertTrue(TlsPinning.parse("h6gbBGGv") is TlsPinning.Spki.Invalid)
    }

    @Test
    fun `something that is not base64 is rejected`() {
        assertTrue(TlsPinning.parse("not a fingerprint!!") is TlsPinning.Spki.Invalid)
    }

    @Test
    fun `a pin on a plaintext address is flagged, since it can never apply`() {
        // TLS is not in play at all here, so the pin would be silently ignored
        // while the user believed the connection was verified.
        val warning = TlsPinning.warning("http://192.168.1.1:8790", canonical)
        assertTrue(warning != null && warning.contains("https"))
    }

    @Test
    fun `a pin on an https address is accepted`() {
        assertNull(TlsPinning.warning("https://vrcx.example.com", canonical))
        assertNull(TlsPinning.warning("https://vrcx.example.com:8443", canonical))
        assertNull(TlsPinning.warning("https://203.0.113.7", canonical))
    }

    @Test
    fun `an unusable fingerprint is reported instead of connecting unpinned`() {
        assertTrue(TlsPinning.warning("https://vrcx.example.com", "nonsense") != null)
    }

    @Test
    fun `apply installs the pin only when there is a pin to install`() {
        val plain = OkHttpClient.Builder().build()

        val pinned = TlsPinning.apply(OkHttpClient.Builder(), "https://vrcx.example.com", canonical)
            .build()
        assertNotEquals(plain.hostnameVerifier, pinned.hostnameVerifier)

        // No pin, or a pin that cannot be used: the client is left as it was,
        // because the decision to refuse belongs to the caller -- see warning().
        val untouched = TlsPinning.apply(OkHttpClient.Builder(), "https://vrcx.example.com", "")
            .build()
        assertEquals(plain.hostnameVerifier, untouched.hostnameVerifier)
    }

    @Test
    fun `a pin is not applied to a plaintext client`() {
        // There is no handshake to enforce anything in over http, so attaching a
        // trust manager would leave the code claiming a guarantee that is not
        // being provided. A client either carries a pin or is genuinely unpinned.
        val plain = OkHttpClient.Builder().build()
        val applied =
            TlsPinning.apply(OkHttpClient.Builder(), "http://192.168.1.1:8790", canonical).build()
        assertEquals(plain.hostnameVerifier, applied.hostnameVerifier)
        assertEquals(plain.certificatePinner, applied.certificatePinner)
    }

    @Test
    fun `apply and warning agree on when a pin is in force`() {
        val plain = OkHttpClient.Builder().build()
        val baseUrl = "https://vrcx.example.com"

        val pinned = TlsPinning.apply(OkHttpClient.Builder(), baseUrl, canonical).build()
        assertNull(TlsPinning.warning(baseUrl, canonical))
        assertNotEquals(plain.hostnameVerifier, pinned.hostnameVerifier)

        // An unusable fingerprint must not be installed either, or the user
        // would get a pinning failure with no hint that the pin was malformed.
        val rejected = TlsPinning.apply(OkHttpClient.Builder(), baseUrl, "nonsense").build()
        assertTrue(TlsPinning.warning(baseUrl, "nonsense") != null)
        assertEquals(plain.hostnameVerifier, rejected.hostnameVerifier)
    }

    // ---------------------------------------------------------------------
    // What the pin then *does*.
    //
    // The tests above cover reading a fingerprint and installing it. These cover
    // what it accepts and refuses, which is the whole guarantee: a self-signed
    // certificate is refused while the handshake runs, so the trust manager is
    // the only place a pin can act.
    //
    // The certificate is a throwaway one made for these tests (EC P-256,
    // self-signed, CN=vrcx0-test.invalid). It is deliberately not the
    // deployment's certificate, and no private key is in the repository -- a
    // pin is checked against the public half only.
    // ---------------------------------------------------------------------

    /** The pin that names [fixture]'s public key. */
    private val fixturePin = "aJ/mc4dohWlH9tyvzWvERcw1NGR7w5zXic29QUncGss="

    private val fixture: X509Certificate by lazy {
        val stream = javaClass.getResourceAsStream("/vrcx0-test-cert.pem")
            ?: error("vrcx0-test-cert.pem is missing from src/test/resources")
        stream.use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
    }

    @Test
    fun `the spki digest of a real certificate is the pin that names it`() {
        // This computation is what the operator's pasted value has to agree
        // with. If it drifts -- the wrong bytes, the wrong hash -- every pin
        // silently becomes a pin that never matches, and the connection fails
        // with a message about certificates that explains nothing.
        assertEquals(fixturePin, TlsPinning.spkiSha256Base64(fixture))
    }

    @Test
    fun `a pin hit is what lets a self-signed certificate through`() {
        val trust = TlsPinning.trustManager(fixturePin)
        assertNotNull(trust)
        // No exception. The system store has never seen this certificate, so
        // this call is exactly where an unpinned connection would die.
        trust!!.checkServerTrusted(arrayOf(fixture), "EC")
    }

    @Test
    fun `a pin that does not match is refused rather than referred onward`() {
        // `canonical` is a well-formed 32-byte digest naming some other key:
        // valid input, wrong key. The refusal must come from here and not from
        // the system store's opinion -- "accept on a match, otherwise let the
        // system decide" would accept *any* certificate the store trusts, which
        // is the impersonation a pin exists to stop.
        val wrong = TlsPinning.trustManager(canonical)
        assertNotNull(wrong)
        val thrown = assertThrows(CertificateException::class.java) {
            wrong!!.checkServerTrusted(arrayOf(fixture), "EC")
        }
        // The message has to name what was expected and what arrived, or the
        // user is left holding "it will not connect".
        assertTrue(thrown.message!!.contains(canonical))
        assertTrue(thrown.message!!.contains(fixturePin))
    }

    @Test
    fun `no pin means no trust manager, so nothing is weakened`() {
        assertNull(TlsPinning.trustManager(""))
        assertNull(TlsPinning.trustManager(null))
        assertNull(TlsPinning.hostnameVerifier(""))
        assertNull(TlsPinning.hostnameVerifier(null))
    }

    @Test
    fun `the hostname check is satisfied by a pin hit`() {
        // The certificate is issued to an address rather than a name, so the
        // name check cannot pass on its own. On a hit it is skipped: the pin has
        // already established which key is on the other end, which is the thing
        // the name was standing in for.
        val verifier = TlsPinning.hostnameVerifier(fixturePin)
        assertNotNull(verifier)
        assertTrue(verifier!!.verify("192.168.1.1", sessionPresenting(fixture)))
    }

    @Test
    fun `apply installs the socket factory and the verifier, and no CertificatePinner`() {
        val plain = OkHttpClient.Builder().build()
        val pinned = TlsPinning.apply(
            OkHttpClient.Builder(),
            "https://vrcx.example.com",
            fixturePin
        ).build()

        assertNotEquals(plain.hostnameVerifier, pinned.hostnameVerifier)

        // The absence of a pinner is deliberate and load-bearing -- do not
        // "restore" one. OkHttp reads the peer chain after the handshake, and
        // against this project's own server that chain comes back empty (the
        // same session reports it a moment later from inside the hostname
        // verifier), so a pinner matches nothing and fails every request closed
        // with "Certificate pinning failure!" and no reason. See the class
        // comment on TlsPinning. The trust manager is the enforcement point, and
        // it refuses anything but the pinned key.
        assertEquals(plain.certificatePinner, pinned.certificatePinner)

        // `sslSocketFactory` deliberately not compared: OkHttp builds a new one
        // for every client, so the instances always differ and an inequality
        // would hold even if `apply` had done nothing. What the socket factory
        // does is covered by the trust-manager tests above.
    }

    /** An [SSLSession] that knows only which certificates were presented. */
    private fun sessionPresenting(vararg certs: java.security.cert.Certificate): SSLSession {
        val handler = InvocationHandler { _, method, _ ->
            when (method.name) {
                "getPeerCertificates" -> certs
                "getPeerHost" -> "192.168.1.1"
                else -> null
            }
        }
        return Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(SSLSession::class.java),
            handler
        ) as SSLSession
    }
}
