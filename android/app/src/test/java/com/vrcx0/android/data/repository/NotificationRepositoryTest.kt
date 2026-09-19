package com.vrcx0.android.data.repository

import com.vrcx0.android.data.remote.ArgForms
import com.vrcx0.android.data.remote.CommandRunner
import com.vrcx0.android.data.remote.NotificationListItemOutput
import com.vrcx0.android.data.remote.NotificationMarkSeenBatchItem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the notification repository to the server's wire shapes, mirroring what
 * the desktop bindings declare for `app__notification_list_query` (bare array
 * reply, query under the `query` key) and `app__notification_mark_seen_batch`
 * (items under the `input` key). These are exactly the shapes that would
 * silently break if someone re-encoded the query as a stringified struct.
 */
class NotificationRepositoryTest {

    private val sampleRow = """
        {
          "id": "not_1", "version": 3, "type": "boop",
          "title": "Boop", "message": "booped you",
          "seen": false, "expired": false,
          "senderUserId": "usr_a", "senderUsername": "Tester",
          "location": "remote", "createdAt": "2026-09-19T02:00:00.000Z"
        }
    """.trimIndent()

    @Test
    fun list_sends_query_object_and_decodes_bare_array() {
        var seenCommand: String? = null
        var seenQuery: JsonObject? = null
        val runner = CommandRunner { command, args ->
            seenCommand = command
            @Suppress("UNCHECKED_CAST")
            seenQuery = (args["query"] as JsonObject)
            JsonArray(emptyList())
        }
        val repo = NotificationRepository(runner)

        val rows = kotlinx.coroutines.runBlocking { repo.list("usr_me") }

        assertEquals("app__notification_list_query", seenCommand)
        // The query must travel as a real JSON object (pre-encoded with
        // wireJson), NOT as a stringified struct.
        assertEquals("usr_me", seenQuery!!.jsonObject["userId"]!!.jsonPrimitive.content)
        assertTrue(rows.isEmpty())
    }

    @Test
    fun list_decodes_row_fields() {
        val runner = CommandRunner { _, _ ->
            kotlinx.serialization.json.Json.Default.parseToJsonElement(
                "[$sampleRow]"
            )
        }
        val repo = NotificationRepository(runner)

        val rows = kotlinx.coroutines.runBlocking { repo.list("usr_me", limit = 10) }

        assertEquals(1, rows.size)
        val row = rows.first()
        assertEquals("not_1", row.id)
        assertEquals(3, row.version)
        assertEquals("boop", row.type)
        assertEquals("Tester", row.senderUsername)
        assertEquals(false, row.seen)
        assertEquals("remote", row.location)
    }

    @Test
    fun mark_seen_skips_seen_rows_and_sends_items_at_top_level() {
        val rows = listOf(
            NotificationListItemOutput(id = "not_seen", version = 1, seen = true, location = "remote"),
            NotificationListItemOutput(id = "not_unseen", version = 7, seen = false, location = "local"),
            NotificationListItemOutput(id = "not_blank", version = 2, seen = false, location = "")
        )
        var captured: JsonArray? = null
        val runner = CommandRunner { command, args ->
            assertEquals("app__notification_mark_seen_batch", command)
            captured = (args["items"] as JsonArray)
            JsonObject(emptyMap())
        }
        val repo = NotificationRepository(runner)

        val ok = kotlinx.coroutines.runBlocking { repo.markSeen(rows) }

        assertTrue(ok)
        // The command is an INPUT_COMMAND: CommandArgs.encode wraps the body
        // under `input` on the wire, so the repository must pass `items` (the
        // JSON ARRAY) at the top level. Nesting it any deeper -- whether as
        // {"input": ...} or {"items": {"items": ...}} -- makes the server see
        // an empty or wrong-typed batch (live-verified 502s).
        assertTrue(ArgForms.usesInput("app__notification_mark_seen_batch"))
        val items = captured!!
        assertEquals(2, items.size)
        val first = items[0].jsonObject
        assertEquals("not_unseen", first["id"]!!.jsonPrimitive.content)
        assertEquals(7, first["version"]!!.jsonPrimitive.content.toInt())
        assertEquals("local", first["location"]!!.jsonPrimitive.content)
        // A blank `location` falls back to "remote" (the common table).
        val second = items[1].jsonObject
        assertEquals("not_blank", second["id"]!!.jsonPrimitive.content)
        assertEquals("remote", second["location"]!!.jsonPrimitive.content)
    }

    @Test
    fun mark_seen_item_shape_matches_server_input() {
        val item = NotificationMarkSeenBatchItem(id = "not_1", version = 4, location = "remote")
        val encoded = kotlinx.serialization.json.Json.encodeToJsonElement(
            NotificationMarkSeenBatchItem.serializer(), item
        ).jsonObject
        assertEquals(setOf("id", "version", "location"), encoded.keys)
    }
}
