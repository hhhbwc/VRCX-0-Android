package com.vrcx0.android.data.remote

import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the discriminator keys of the two polymorphic wire types.
 *
 * The server's `AuthOutcome` is `#[serde(tag = "status")]` and `StreamFrame` is
 * `#[serde(tag = "kind")]`. kotlinx-serialization defaults its discriminator key
 * to `"type"`, so without an explicit `@JsonClassDiscriminator` every response
 * is rejected with "class discriminator was missing" -- which for `/v1/auth/login`
 * means a 2FA challenge never becomes a Challenge, the sign-in just shows a raw
 * deserialization error, and no verification-code window ever opens.
 *
 * These tests use the literal payloads the server sends, so a regression here
 * fails loudly instead of only showing up at runtime.
 */
class ProtocolSerializationTest {

    @Test
    fun a_challenge_is_read_through_the_status_discriminator() {
        // Verbatim from a real server reply, which is what exposed the bug.
        val json = """{"status":"challenge","attemptId":"3","methods":["emailOtp"]}"""
        val outcome = wireJson.decodeFromString<AuthOutcome>(json)
        assertTrue(outcome is AuthOutcome.Challenge)
        val challenge = outcome as AuthOutcome.Challenge
        assertEquals("3", challenge.attemptId)
        assertEquals(listOf("emailOtp"), challenge.methods)
    }

    @Test
    fun an_authenticated_outcome_carries_the_identity() {
        val json = """{"status":"authenticated","userId":"usr_abc","displayName":"Someone"}"""
        val outcome = wireJson.decodeFromString<AuthOutcome>(json)
        assertTrue(outcome is AuthOutcome.Authenticated)
        val authed = outcome as AuthOutcome.Authenticated
        assertEquals("usr_abc", authed.userId)
        assertEquals("Someone", authed.displayName)
    }

    @Test
    fun a_failed_outcome_is_read_through_the_status_discriminator() {
        val json = """{"status":"failed","reason":"bad password","kind":"invalidCredentials"}"""
        val outcome = wireJson.decodeFromString<AuthOutcome>(json)
        assertTrue(outcome is AuthOutcome.Failed)
        assertEquals("invalidCredentials", (outcome as AuthOutcome.Failed).kind)
    }

    @Test
    fun a_stream_event_is_read_through_the_kind_discriminator() {
        val json =
            """{"kind":"event","event":"mutualGraphFetchStatus","payload":{"status":"running"},"seq":7}"""
        val frame = wireJson.decodeFromString<StreamFrame>(json)
        assertTrue(frame is StreamFrame.Event)
        val event = frame as StreamFrame.Event
        assertEquals("mutualGraphFetchStatus", event.event)
        assertEquals(7L, event.seq)
        assertTrue(event.payload.toString().contains("running"))
    }

    @Test
    fun a_stream_hello_is_read_through_the_kind_discriminator() {
        val json = """{"kind":"hello","protocolVersion":2,"appVersion":"1.0.0"}"""
        val frame = wireJson.decodeFromString<StreamFrame>(json)
        assertTrue(frame is StreamFrame.Hello)
        assertEquals(2, (frame as StreamFrame.Hello).protocolVersion)
    }

    @Test
    fun a_feed_query_omits_filters_when_none_are_selected() {
        // On the wire `filters: []` means "match nothing" and omitting the key
        // means "no filter". So an empty selection MUST NOT serialise as [].
        val input = FeedLatestQueryInput(userId = "usr_x", filters = null, maxRows = 100)
        val json = wireJson.encodeToJsonElement(input).toString()
        assertFalse("filters must be omitted, was: $json", json.contains("filters"))
        assertTrue(json.contains("\"userId\""))
        assertTrue(json.contains("\"maxRows\""))
    }

    @Test
    fun a_feed_query_keeps_selected_filters() {
        val input = FeedLatestQueryInput(
            userId = "usr_x",
            filters = listOf(FeedFilter.Gps, FeedFilter.Online),
            maxRows = 100
        )
        val json = wireJson.encodeToJsonElement(input).toString()
        assertTrue(json.contains("\"filters\""))
        assertTrue("GPS casing comes from @SerialName, was: $json", json.contains("GPS"))
    }

    @Test
    fun the_combined_snapshot_exposes_the_friend_roster() {
        val json = """
            {"authenticatedRuntimePhase":{"friendBaseline":{"count":2,"snapshot":{
            "friendsById":{"usr_a":{"id":"usr_a","displayName":"A","state":"online",
            "location":"wrld_1:1"}},"orderedFriendIds":["usr_a"]}}}}
        """.trimIndent()
        val friends = wireJson.decodeFromString<CombinedSnapshotOutput>(json)
            .authenticatedRuntimePhase?.friendBaseline?.snapshot?.friendsById
        assertEquals(1, friends?.size)
        assertEquals("A", friends?.get("usr_a")?.displayName)
        assertEquals("online", friends?.get("usr_a")?.state)
    }
}
