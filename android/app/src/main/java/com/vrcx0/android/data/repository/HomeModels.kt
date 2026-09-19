package com.vrcx0.android.data.repository

import com.vrcx0.android.data.remote.FriendRecord
import com.vrcx0.android.data.remote.GroupSummary
import com.vrcx0.android.data.remote.WorldSummary
import com.vrcx0.android.data.remote.instanceAccessLabel
import com.vrcx0.android.data.remote.worldIdFromLocation

/**
 * Friends gathered in one instance, for the home tab.
 *
 * The question this answers is "where is everyone right now?", so the identity
 * is the **instance**, not the world: two friends in different instances of the
 * same world are not in the same place and must not be merged. The location
 * string already encodes that (`wrld_x:12345~region(jp)`), so it is used
 * verbatim as the key.
 */
data class WorldGathering(
    val location: String,
    val worldId: String,
    val worldName: String?,
    val thumbnailUrl: String,
    /** The friends present, in roster order. */
    val friends: List<FriendRecord>
) {
    val count: Int get() = friends.size

    /** "公开" / "好友+" / "群组公开" / "私密", decoded from the location. */
    val accessLabel: String get() = instanceAccessLabel(location)

    /**
     * Never render a raw `wrld_...`.
     *
     * The world-name lookup is a network round trip, so a gathering can be
     * on screen before its name arrives; showing "世界" for that moment is
     * better than showing an id the reader cannot use.
     */
    val displayName: String get() = worldName?.takeIf { it.isNotBlank() } ?: "世界"
}

/**
 * Groups the online friends by the instance they are in, busiest first.
 *
 * Deliberately pure and client-side. The server has
 * `app__world_friend_visits(worldId)` but that asks the opposite question --
 * given a world, which friends have *ever* been there -- so it cannot produce
 * "who is here now". Every field needed is already on the friend records
 * (`location`, `state`), so this costs no traffic at all.
 *
 * Sorting is by head-count descending, then by name: the whole point is that
 * the place with the most friends is the one worth joining, so it goes first.
 * Ties break on the rendered name so the order is stable between refreshes
 * rather than whatever order the map happened to iterate in.
 */
fun buildWorldGatherings(
    friends: Collection<FriendRecord>,
    worldNames: Map<String, WorldSummary> = emptyMap()
): List<WorldGathering> = friends
    .mapNotNull { friend ->
        val location = friend.location.orEmpty()
        val worldId = friend.worldId.ifBlank { worldIdFromLocation(location) }
        // Only a real in-world location counts. "offline", "private" and
        // "traveling" are states, not places, and must not become cards.
        if (!location.startsWith("wrld_") || !worldId.startsWith("wrld_")) return@mapNotNull null
        Triple(location, worldId, friend)
    }
    .groupBy({ it.first }, { it.third })
    .map { (location, present) ->
        val worldId = location.substringBefore(':')
        val summary = worldNames[worldId]
        WorldGathering(
            location = location,
            worldId = worldId,
            worldName = summary?.name,
            thumbnailUrl = summary?.thumbnailImageUrl.orEmpty(),
            friends = present
        )
    }
    .sortedWith(
        compareByDescending<WorldGathering> { it.count }
            .thenBy { it.displayName }
    )

/**
 * A group row on the home tab.
 *
 * [memberCount] is 0 until the group is hydrated, which is why the caption has
 * to cope with "unknown" rather than claiming "0 members".
 */
data class MyGroup(
    val id: String,
    val name: String?,
    val iconUrl: String,
    val memberCount: Long,
    val onlineMemberCount: Long,
    val isRepresenting: Boolean
) {
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: "群组"

    val memberCaption: String
        get() = when {
            memberCount <= 0 -> "加载中…"
            onlineMemberCount > 0 -> "$memberCount 成员 · $onlineMemberCount 在线"
            else -> "$memberCount 成员"
        }
}

/** Builds the group list from the resolved summaries, hydrated ones first. */
fun buildMyGroups(groupIds: List<String>, groups: Map<String, GroupSummary>): List<MyGroup> =
    groupIds.map { id ->
        val g = groups[id]
        MyGroup(
            id = id,
            name = g?.name,
            iconUrl = g?.iconUrl.orEmpty(),
            memberCount = g?.memberCount ?: 0,
            onlineMemberCount = g?.onlineMemberCount ?: 0,
            isRepresenting = g?.isRepresenting ?: false
        )
    }


/**
 * One "guess you want to go" card: the room of the friend the user knows
 * best, online right now.
 *
 * Ranking uses what the device actually knows -- favourited friends first,
 * then the VRChat friend number (lower = added earlier = better known). The
 * server has no play-time ledger yet (`game_log_sessions_query` answers empty
 * until desktop logs are forwarded and accumulated), so "played the longest"
 * is approximated, honestly, by familiarity -- and the ranking rule lives in
 * this one testable function so a real play-time signal can replace it later
 * without touching the UI.
 */
data class SuggestedRoom(
    val location: String,
    val worldId: String,
    val worldName: String?,
    val thumbnailUrl: String,
    val topFriendId: String,
    val topFriendName: String,
    val friendCount: Int,
    val accessLabel: String
)

fun buildSuggestedRooms(
    friends: Collection<FriendRecord>,
    favoriteFriendIds: Set<String>,
    rosterOrder: Map<String, Int> = emptyMap(),
    worldNames: Map<String, WorldSummary> = emptyMap()
): List<SuggestedRoom> {
    val ranked = friends
        .filter { (it.location.orEmpty()).startsWith("wrld_") }
        .sortedWith(
            compareByDescending<FriendRecord> { it.id in favoriteFriendIds }
                .thenBy { rosterOrder[it.id] ?: Int.MAX_VALUE }
        )
    val seen = LinkedHashMap<String, MutableList<FriendRecord>>()
    for (friend in ranked) {
        seen.getOrPut(friend.location) { mutableListOf() }.add(friend)
    }
    return seen.mapNotNull { (location, members) ->
        val top = members.first()
        val worldId = top.worldId.ifBlank { worldIdFromLocation(location) }
        if (worldId.isBlank()) return@mapNotNull null
        SuggestedRoom(
            location = location,
            worldId = worldId,
            worldName = worldNames[worldId]?.name,
            thumbnailUrl = worldNames[worldId]?.thumbnailImageUrl.orEmpty(),
            topFriendId = top.id,
            topFriendName = top.displayName,
            friendCount = members.size,
            accessLabel = instanceAccessLabel(location)
        )
    }
}
