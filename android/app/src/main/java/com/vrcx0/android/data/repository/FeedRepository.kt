package com.vrcx0.android.data.repository

import com.vrcx0.android.data.remote.CommandRunner
import com.vrcx0.android.data.remote.FeedCursorInput
import com.vrcx0.android.data.remote.FeedFilter
import com.vrcx0.android.data.remote.FeedLatestQueryInput
import com.vrcx0.android.data.remote.FeedQueryMode
import com.vrcx0.android.data.remote.FeedReadModelOutput
import com.vrcx0.android.data.remote.FeedRowOutput
import com.vrcx0.android.data.remote.FeedRowsQueryInput
import com.vrcx0.android.data.remote.FeedSearchQueryInput
import com.vrcx0.android.data.remote.wireJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Reads the activity feed for the signed-in user.
 *
 * Every method maps to a FLAT command: the whole query object travels under the
 * `query` key. That object MUST be pre-encoded with [wireJson] -- the runner
 * only understands primitives, collections and [JsonElement], so handing it a
 * raw `@Serializable` data class would stringify it via `toString()` and the
 * server would reject it with "invalid type: string, expected struct".
 *
 * The server answers `latest`/`rows` with a `{rows, maxSequence, ...}` envelope,
 * but `search` with a **bare array** of rows; both are normalised here into
 * [FeedReadModelOutput].
 */
class FeedRepository(private val runner: CommandRunner) {

    /** First page: the newest `maxRows` rows for [userId], optionally filtered. */
    suspend fun latest(
        userId: String,
        filters: List<FeedFilter> = emptyList(),
        maxRows: Long = 100
    ): FeedReadModelOutput {
        val input = FeedLatestQueryInput(
            userId = userId,
            filters = filters.ifEmpty { null },
            maxRows = maxRows
        )
        return decode(runner("app__feed_latest_query", query(input)))
    }

    /**
     * Older page anchored at [cursor] (from a previous [latest] or [rows] result).
     *
     * `mode = "lookup"` replays the persisted store from the cursor; this is the
     * "load older" path. A `null` cursor means there is nothing older.
     */
    suspend fun rows(
        userId: String,
        cursor: FeedCursorInput?,
        maxEntries: Long = 100,
        filters: List<FeedFilter> = emptyList()
    ): FeedReadModelOutput {
        val input = FeedRowsQueryInput(
            userId = userId,
            mode = FeedQueryMode.Lookup,
            filters = filters.ifEmpty { null },
            maxEntries = maxEntries,
            cursor = cursor
        )
        return decode(runner("app__feed_rows_query", query(input)))
    }

    /**
     * Text search across the feed.
     *
     * Matching is case-insensitive against the row's text fields; `WRLD_`/`GRP_`
     * prefixes match a location, `PRIVATE`/`PUBLIC` match the avatar ownership of
     * the row's owner. Returns the newest `maxRows` matches.
     *
     * Unlike [latest], this command replies with a bare JSON array of rows
     * (verified against a live server), so it is decoded as a list and wrapped.
     */
    suspend fun search(
        userId: String,
        search: String,
        maxRows: Long = 100,
        filters: List<FeedFilter> = emptyList()
    ): FeedReadModelOutput {
        val input = FeedSearchQueryInput(
            userId = userId,
            search = search,
            filters = filters.ifEmpty { null },
            maxRows = maxRows
        )
        val element = runner("app__feed_search_query", query(input))
        val rows = wireJson.decodeFromString<List<FeedRowOutput>>(element.toString())
        return FeedReadModelOutput(rows = rows)
    }

    /**
     * Wraps a query object as the FLAT `{ "query": {...} }` argument map.
     *
     * `inline` + `reified` matters: it keeps the concrete type at the call site
     * so [encodeToJsonElement] picks that type's serializer. A plain `Any`
     * parameter would resolve the polymorphic `Any` serializer instead.
     */
    private inline fun <reified T> query(input: T): Map<String, Any?> =
        mapOf("query" to wireJson.encodeToJsonElement(input))

    private fun decode(element: JsonElement): FeedReadModelOutput =
        wireJson.decodeFromString(element.toString())
}
