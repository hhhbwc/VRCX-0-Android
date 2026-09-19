package com.vrcx0.android.data.repository

import com.vrcx0.android.data.remote.ArgForms
import com.vrcx0.android.data.remote.CommandRunner
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the browse-history repository to the server's wire shapes. Both commands
 * are INPUT_COMMANDS, so the repository must pass fields at the TOP LEVEL of
 * the args map (encode wraps them under `input`). Double-wrapping is the exact
 * bug that produced empty batches for notifications.
 */
class BrowseHistoryRepositoryTest {

    @Test
    fun record_passes_fields_at_top_level() {
        var captured: Map<String, Any?>? = null
        val runner = CommandRunner { command, args ->
            assertEquals("app__browse_history_record", command)
            captured = args
            buildJsonObject { }
        }
        val repo = BrowseHistoryRepository(runner)

        kotlinx.coroutines.runBlocking {
            repo.record("usr_me", "world", "wrld_x", "My World", "http://img")
        }

        assertTrue(ArgForms.usesInput("app__browse_history_record"))
        val args = captured!!
        assertEquals("usr_me", args["ownerUserId"])
        assertEquals("world", args["entityKind"])
        assertEquals("wrld_x", args["entityId"])
        assertEquals("My World", args["title"])
        // Must NOT be nested under an "input" key -- encode does that itself.
        assertTrue(args["input"] == null)
    }

    @Test
    fun query_passes_fields_at_top_level_and_decodes_items() {
        var capturedKind: Any? = null
        val runner = CommandRunner { command, args ->
            assertEquals("app__browse_history_query", command)
            capturedKind = args["entityKind"]
            // Bare page envelope, as the server answers.
            kotlinx.serialization.json.Json.Default.parseToJsonElement(
                """{"items":[{"entityKind":"world","entityId":"wrld_x","title":"My World","viewCount":3}]}"""
            )
        }
        val repo = BrowseHistoryRepository(runner)

        val items = kotlinx.coroutines.runBlocking { repo.query("usr_me", kind = "world") }

        assertTrue(ArgForms.usesInput("app__browse_history_query"))
        assertEquals("world", capturedKind)
        assertEquals(1, items.size)
        assertEquals("My World", items[0].title)
        assertEquals(3L, items[0].viewCount)
    }
}
