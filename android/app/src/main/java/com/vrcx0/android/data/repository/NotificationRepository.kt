package com.vrcx0.android.data.repository

import com.vrcx0.android.data.remote.CommandRunner
import com.vrcx0.android.data.remote.NotificationListItemOutput
import com.vrcx0.android.data.remote.NotificationListQueryInput
import com.vrcx0.android.data.remote.NotificationMarkSeenBatchItem
import com.vrcx0.android.data.remote.wireJson
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Reads and acknowledges the notification store behind the top-bar bell.
 *
 * Same FLAT-command convention as [FeedRepository]: the query object travels
 * under the `query` key and MUST be pre-encoded with [wireJson] -- handing the
 * runner a raw `@Serializable` object would stringify it and the server would
 * reject it with "invalid type: string, expected struct".
 *
 * `app__notification_list_query` replies with a bare array of rows;
 * `app__notification_mark_seen_batch` is an INPUT_COMMAND -- the batch fields
 * go at the top level of the body and [com.vrcx0.android.data.remote.ArgForms]
 * wraps them under `input` on the wire (see [markSeen]).
 */
class NotificationRepository(private val runner: CommandRunner) {

    /** Newest notifications for [userId], unseen first-ish (server order). */
    suspend fun list(
        userId: String,
        limit: Int = 100
    ): List<NotificationListItemOutput> {
        val input = NotificationListQueryInput(userId = userId, limit = limit)
        val element = runner(
            "app__notification_list_query",
            mapOf("query" to wireJson.encodeToJsonElement(input))
        )
        return wireJson.decodeFromString(element.toString())
    }

    /**
     * Marks rows seen. Returns true when the command ran without error; a
     * failure is not fatal to the UI, the unread dot just survives until the
     * next attempt.
     *
     * Argument shape matters: `app__notification_mark_seen_batch` is in
     * [ArgForms.INPUT_COMMANDS], so [CommandRunner] callers pass the batch
     * fields (`items`) at the TOP LEVEL and `CommandArgs.encode` wraps them
     * under `input` on the wire. Passing `mapOf("input" to ...)` here would
     * double-wrap and the server would read an empty batch (verified live:
     * HTTP 502 "requires at least one notification").
     */
    suspend fun markSeen(rows: List<NotificationListItemOutput>): Boolean {
        if (rows.isEmpty()) return true
        val items = rows.filter { !it.seen }.map {
            NotificationMarkSeenBatchItem(
                id = it.id,
                version = it.version,
                location = it.location.ifBlank { "remote" }
            )
        }
        if (items.isEmpty()) return true
        return runCatching {
            runner(
                "app__notification_mark_seen_batch",
                // `items` must be the JSON ARRAY itself at the body's top
                // level: encode() then wraps it as {"input": {"items": [...]}}.
                // Encoding the wrapper *struct* here would nest it a second
                // time ("items": {"items": ...}) and the server would reject
                // it with "invalid type: map, expected a sequence".
                mapOf("items" to wireJson.encodeToJsonElement(items))
            )
        }.isSuccess
    }
}
