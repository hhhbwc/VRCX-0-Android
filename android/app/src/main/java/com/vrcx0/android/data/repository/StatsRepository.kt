package com.vrcx0.android.data.repository

import com.vrcx0.android.data.remote.FeedFilter
import com.vrcx0.android.data.remote.FeedRowOutput
import com.vrcx0.android.data.remote.FriendLogRow
import com.vrcx0.android.data.remote.CommandRunner
import com.vrcx0.android.data.remote.wireJson
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Aggregates the data the server already accumulates into small "your VRChat in
 * numbers" statistics. Everything here reads data the server records on its own
 * (the social feed, the friend log, the browse history) -- no game client is
 * required, unlike game-log / instance-activity.
 *
 * Each number is a single cheap aggregation over one already-existing query;
 * nothing here writes or polls.
 */
class StatsRepository(private val runner: CommandRunner) {

    /** The account's activity, summed across the newest [maxRows] feed rows. */
    suspend fun feedStats(userId: String, maxRows: Long = 300): FeedStats {
        val feed = FeedRepository(runner)
        val rows = feed.latest(userId, maxRows = maxRows).rows
        return aggregateFeed(rows)
    }

    /** Additions / removals / renames / trust changes, from the friend log. */
    suspend fun friendLogStats(userId: String, maxEntries: Int = 300): FriendLogStats {
        val reply = runner(
            "app__friend_log_history_query",
            mapOf(
                "query" to kotlinx.serialization.json.buildJsonObject {
                    put("userId", kotlinx.serialization.json.JsonPrimitive(userId))
                    put("maxEntries", kotlinx.serialization.json.JsonPrimitive(maxEntries))
                }
            )
        )
        val rows = runCatching {
            wireJson.decodeFromJsonElement<List<FriendLogRow>>(reply)
        }.getOrDefault(emptyList())
        return aggregateFriendLog(rows)
    }

    /** Distinct worlds browsed + the most-visited one, from browse history. */
    suspend fun browseStats(userId: String): BrowseStats {
        val rows = BrowseHistoryRepository(runner).query(userId, limit = 200)
        return aggregateBrowse(rows)
    }
}

/** Feed aggregated into per-type counts. */
data class FeedStats(
    val total: Int = 0,
    val online: Int = 0,
    val offline: Int = 0,
    val location: Int = 0,
    val status: Int = 0,
    val avatar: Int = 0,
    val bio: Int = 0
) {
    val isEmpty: Boolean get() = total == 0
}

/** Friend-log aggregated into per-kind counts. */
data class FriendLogStats(
    val added: Int = 0,
    val removed: Int = 0,
    val renamed: Int = 0,
    val trustChanged: Int = 0
) {
    val isEmpty: Boolean get() = added == 0 && removed == 0 && renamed == 0 && trustChanged == 0
}

/** Browse history aggregated. */
data class BrowseStats(
    val distinctWorlds: Int = 0,
    val totalViews: Long = 0,
    val topWorld: String = ""
) {
    val isEmpty: Boolean get() = distinctWorlds == 0
}

internal fun aggregateFeed(rows: List<FeedRowOutput>): FeedStats {
    var online = 0; var offline = 0; var location = 0
    var status = 0; var avatar = 0; var bio = 0
    for (row in rows) {
        when (row.type?.lowercase()) {
            "online" -> online++
            "offline" -> offline++
            "location", "gps" -> location++
            "status" -> status++
            "avatar" -> avatar++
            "bio" -> bio++
        }
    }
    return FeedStats(
        total = rows.size,
        online = online, offline = offline, location = location,
        status = status, avatar = avatar, bio = bio
    )
}

internal fun aggregateFriendLog(rows: List<FriendLogRow>): FriendLogStats {
    var added = 0; var removed = 0; var renamed = 0; var trust = 0
    for (row in rows) {
        when (row.type.lowercase()) {
            "friend" -> added++
            "unfriend" -> removed++
            "displayname" -> renamed++
            "trustlevel" -> trust++
        }
    }
    return FriendLogStats(added = added, removed = removed, renamed = renamed, trustChanged = trust)
}

internal fun aggregateBrowse(rows: List<BrowseHistoryItem>): BrowseStats {
    val worlds = rows.filter { it.entityKind == "world" }
    if (worlds.isEmpty()) return BrowseStats()
    val total = worlds.sumOf { it.viewCount }
    val top = worlds.maxByOrNull { it.viewCount }?.title.orEmpty()
    return BrowseStats(distinctWorlds = worlds.size, totalViews = total, topWorld = top)
}
