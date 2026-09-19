package com.vrcx0.android.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the address rules, because getting them wrong is invisible until someone
 * tries to sign in and nothing happens.
 *
 * The contract has to match `normalize_base_url` in
 * `crates/runtime-host-desktop/src/game_log_remote_forwarder.rs`: a bare host
 * gets `http://` and the server's own port, and an `https://` address is left
 * alone so 443 stays implied.
 */
class NormalizeBaseUrlTest {

    @Test
    fun a_bare_host_gets_http_and_the_server_port() {
        // The common case: typing the address on a LAN and having it just work.
        assertEquals("http://192.168.1.1:8790", normalizeBaseUrl("192.168.1.1"))
    }

    @Test
    fun an_explicit_http_port_is_respected() {
        assertEquals("http://192.168.1.1:9000", normalizeBaseUrl("http://192.168.1.1:9000"))
    }

    @Test
    fun https_does_not_get_the_plaintext_port() {
        // The bug this exists to prevent: 8790 is the backend's own listener,
        // the one a TLS front end proxies to. Asking https://host:8790 to
        // connect fails, and it looked like the server was down.
        assertEquals("https://vrcx.example.com", normalizeBaseUrl("https://vrcx.example.com"))
    }

    @Test
    fun https_keeps_an_explicit_port() {
        // A NAT mapping that could not use 443 still has to be expressible.
        assertEquals("https://vrcx.example.com:52052",
            normalizeBaseUrl("https://vrcx.example.com:52052"))
    }

    @Test
    fun a_bare_host_is_never_upgraded_to_https() {
        // Guessing TLS would be worse than not guessing: the server speaks
        // plain HTTP unless something is put in front of it.
        assertEquals("http://vrcx.example.com:8790", normalizeBaseUrl("vrcx.example.com"))
    }

    @Test
    fun a_trailing_slash_is_dropped() {
        assertEquals("https://vrcx.example.com", normalizeBaseUrl("https://vrcx.example.com/"))
        assertEquals("http://192.168.1.1:8790", normalizeBaseUrl("192.168.1.1/"))
    }

    @Test
    fun surrounding_whitespace_is_ignored() {
        assertEquals("http://192.168.1.1:8790", normalizeBaseUrl("  192.168.1.1  "))
    }

    @Test
    fun a_path_survives_when_it_is_not_a_bare_host() {
        // Only the authority decides whether a port is missing; a path must not
        // be mistaken for one.
        assertEquals("https://vrcx.example.com/api",
            normalizeBaseUrl("https://vrcx.example.com/api"))
    }

    @Test
    fun blank_input_stays_blank() {
        assertEquals("", normalizeBaseUrl("   "))
        assertEquals("", normalizeBaseUrl(""))
    }
}
