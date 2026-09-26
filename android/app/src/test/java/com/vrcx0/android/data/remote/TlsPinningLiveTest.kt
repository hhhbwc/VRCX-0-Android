package com.vrcx0.android.data.remote

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The tests in [TlsPinningTest] pin down the *rules*. These check the rules
 * against a real server, which is the only place a claim about a handshake can
 * actually be settled: whether our trust manager is the thing that let the
 * connection through, or whether OkHttp was going to accept it anyway.
 *
 * Skipped unless a server is named, so an ordinary `testDebugUnitTest` run
 * stays offline and hermetic:
 *
 * ```
 * VRCX0_E2E_BASE=https://192.168.1.1:8443 \
 * VRCX0_E2E_PIN=<SPKI SHA-256, Base64> \
 *     ./gradlew testDebugUnitTest --tests '*TlsPinningLiveTest'
 * ```
 *
 * [WRONG_PIN] is a well-formed 32-byte digest that names nothing in particular.
 * It has to be valid Base64 of the right length, or `apply` would refuse to
 * install it and the test would pass for the wrong reason -- it has to be a
 * *usable* pin for the wrong key, which is the case worth proving.
 */
class TlsPinningLiveTest {

    private val baseUrl = System.getenv("VRCX0_E2E_BASE").orEmpty()
    private val pin = System.getenv("VRCX0_E2E_PIN").orEmpty()

    private fun configured() {
        assumeTrue(
            "set VRCX0_E2E_BASE and VRCX0_E2E_PIN to exercise a real server",
            baseUrl.isNotEmpty() && pin.isNotEmpty()
        )
    }

    private fun clientFor(forPin: String): OkHttpClient =
        TlsPinning.apply(
            OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS),
            baseUrl,
            forPin
        ).build()

    /** `HTTP <code>` on success; throws on a handshake that never completed. */
    private fun health(client: OkHttpClient): String =
        client.newCall(Request.Builder().url("$baseUrl/v1/health").build()).execute().use {
            "HTTP ${it.code}"
        }

    @Test
    fun `the pinned client reaches the server`() {
        configured()
        assertTrue(health(clientFor(pin)).startsWith("HTTP 200"))
    }

    @Test
    fun `a usable pin for the wrong key is refused`() {
        configured()
        // The point is not that *a* pin fails -- it is that a client which is
        // fully, correctly configured to pin, and hands over a valid digest,
        // still cannot reach a server whose key is different. Otherwise the
        // pin would be decoration.
        val attempt = runCatching { health(clientFor(WRONG_PIN)) }
        assertTrue("expected a refusal, got ${attempt.getOrNull()}", attempt.isFailure)
    }

    @Test
    fun `without a pin a self-signed server is refused`() {
        configured()
        // Guards the other direction, and the more dangerous one: that adding
        // support for pinning did not quietly widen what is trusted by default.
        val attempt = runCatching { health(clientFor("")) }
        assertTrue("expected a refusal, got ${attempt.getOrNull()}", attempt.isFailure)
    }

    private companion object {
        /** The same digest the offline tests use: valid, and not this server's. */
        const val WRONG_PIN = "aJ/mc4dohWlH9tyvzWvERcw1NGR7w5zXic29QUncGss="
    }
}
