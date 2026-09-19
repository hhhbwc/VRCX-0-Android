package com.vrcx0.android.data.remote

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun `apply installs a pinner only when there is a pin to install`() {
        val plain = OkHttpClient.Builder().build()

        val pinned = TlsPinning.apply(OkHttpClient.Builder(), "https://vrcx.example.com", canonical)
            .build()
        assertNotEquals(plain.certificatePinner, pinned.certificatePinner)

        // No pin, or a pin that cannot be used: the client is left as it was,
        // because the decision to refuse belongs to the caller -- see warning().
        val untouched = TlsPinning.apply(OkHttpClient.Builder(), "https://vrcx.example.com", "")
            .build()
        assertEquals(plain.certificatePinner, untouched.certificatePinner)
    }

    @Test
    fun `a pin is not applied to a plaintext client`() {
        // CertificatePinner is never consulted for http, so attaching one would
        // leave the code claiming a guarantee that is not being enforced. A
        // client either carries a pinner or is genuinely unpinned.
        val plain = OkHttpClient.Builder().build()
        val applied =
            TlsPinning.apply(OkHttpClient.Builder(), "http://192.168.1.1:8790", canonical).build()
        assertEquals(plain.certificatePinner, applied.certificatePinner)
    }

    @Test
    fun `apply and warning agree on when a pin is in force`() {
        val plain = OkHttpClient.Builder().build()
        val baseUrl = "https://vrcx.example.com"

        val pinned = TlsPinning.apply(OkHttpClient.Builder(), baseUrl, canonical).build()
        assertNull(TlsPinning.warning(baseUrl, canonical))
        assertNotEquals(plain.certificatePinner, pinned.certificatePinner)

        // An unusable fingerprint must not be installed either, or the user
        // would get a pinning failure with no hint that the pin was malformed.
        val rejected = TlsPinning.apply(OkHttpClient.Builder(), baseUrl, "nonsense").build()
        assertTrue(TlsPinning.warning(baseUrl, "nonsense") != null)
        assertEquals(plain.certificatePinner, rejected.certificatePinner)
    }
}
