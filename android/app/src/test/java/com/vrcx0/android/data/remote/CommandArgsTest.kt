package com.vrcx0.android.data.remote

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The command argument encoder.
 *
 * This is where the app's most expensive silent failures have come from: every
 * caller wraps optional lookups in `runCatching{}.getOrNull()`, so a rejected
 * argument shows up as missing data rather than an error. Both bugs pinned here
 * were found on a real device only by reading `adb logcat`:
 *
 *  1. a `@Serializable` query was sent as its `toString()` text;
 *  2. after fixing that by removing the catch-all, plain `String` arguments --
 *     which the catch-all had been handling all along -- started throwing, and
 *     every world-name / avatar / profile lookup quietly returned nothing.
 */
class CommandArgsTest {

    @Test
    fun a_plain_string_argument_is_encoded_as_a_string() {
        // Scalar args are the common case: `{"url": "https://..."}`,
        // `{"userId": "usr_..."}`, `{"worldId": "wrld_..."}`.
        val out = CommandArgs.encode("app__vrchat_user_get", mapOf("userId" to "usr_abc"))
        assertEquals(
            """{"input":{"userId":"usr_abc"}}""",
            out.toString()
        )
    }

    @Test
    fun a_list_of_strings_under_a_flat_command_stays_a_json_array() {
        // `world_summaries_get` is FLAT: `{"worldIds": ["wrld_a","wrld_b"]}`.
        // A stringified list would be accepted by nothing.
        val out = CommandArgs.encode(
            "app__world_summaries_get",
            mapOf("worldIds" to listOf("wrld_a", "wrld_b"))
        )
        assertEquals("""{"worldIds":["wrld_a","wrld_b"]}""", out.toString())
    }

    @Test
    fun an_input_command_is_wrapped_and_a_flat_one_is_not() {
        // Getting this backwards fails with `missing input argument`.
        val wrapped = CommandArgs.encode(
            "app__external_api_image_data_url_get",
            mapOf("url" to "https://api.vrchat.cloud/x")
        )
        assertEquals("""{"input":{"url":"https://api.vrchat.cloud/x"}}""", wrapped.toString())

        val flat = CommandArgs.encode("app__feed_latest_query", mapOf("query" to "x"))
        assertEquals("""{"query":"x"}""", flat.toString())
    }

    @Test
    fun a_pre_encoded_query_object_keeps_its_structure() {
        // The feed query must go as an object, never as a string. This is how
        // FeedRepository builds it: encode first, then pass the JsonElement.
        val encoded: JsonElement = buildJsonObject {
            put("userId", "usr_a")
            put("maxRows", 50)
        }
        val out = CommandArgs.encode("app__feed_latest_query", mapOf("query" to encoded))
        assertEquals("""{"query":{"userId":"usr_a","maxRows":50}}""", out.toString())
    }

    @Test
    fun numbers_and_booleans_survive_unquoted() {
        val out = CommandArgs.encode("app__x", mapOf("maxRows" to 50, "onlyOnline" to true))
        assertEquals("""{"maxRows":50,"onlyOnline":true}""", out.toString())
    }

    @Test
    fun null_becomes_an_empty_object_not_the_string_null() {
        // `filters: null` must be omitted-looking to the server, never "null".
        val out = CommandArgs.encode("app__feed_latest_query", mapOf("filters" to null))
        assertEquals("""{"filters":{}}""", out.toString())
    }

    @Test
    fun a_type_with_no_encoding_rule_still_fails_loudly() {
        // The guard that matters: an un-encoded @Serializable object has to
        // throw rather than be stringified into a server-side type error.
        data class Query(val userId: String)

        val thrown = assertThrows(IllegalArgumentException::class.java) {
            CommandArgs.encode("app__feed_latest_query", mapOf("query" to Query("usr_a")))
        }
        assertTrue(thrown.message.orEmpty().contains("unencodable command argument: Query"))
    }

    @Test
    fun nested_maps_are_encoded_recursively() {
        val out = CommandArgs.encode(
            "app__x",
            mapOf("filter" to mapOf("state" to "online", "ids" to listOf("a")))
        )
        assertEquals("""{"filter":{"state":"online","ids":["a"]}}""", out.toString())
    }
}
