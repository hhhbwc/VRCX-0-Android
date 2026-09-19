package com.vrcx0.android.data.repository

import com.vrcx0.android.data.remote.CombinedSnapshotOutput
import com.vrcx0.android.data.remote.CommandRunner
import com.vrcx0.android.data.remote.FriendRecord
import com.vrcx0.android.data.remote.wireJson

/**
 * Reads the signed-in user's friend roster.
 *
 * The source is `app__backend_runtime_combined_snapshot_get`, NOT
 * `app__social_friend_roster_baseline_get`.
 *
 * The baseline command hands a snapshot out exactly once; asked again it
 * answers `{"snapshot":null,"detail":"Superseded friend roster baseline."}`
 * (verified against a live server). A thin client that did not store that
 * baseline therefore receives an empty roster and rendered a blank friend list.
 * The combined snapshot always carries the full roster at
 * `authenticatedRuntimePhase.friendBaseline.snapshot.friendsById` (242 friends
 * on the test account), so it is the reliable source.
 *
 * It takes no arguments -- it is scoped by the tenant credential the runner
 * already carries -- so the arg map is empty.
 */
class FriendsRepository(private val runner: CommandRunner) {

    suspend fun roster(userId: String): FriendsRoster {
        val element = runner("app__backend_runtime_combined_snapshot_get", emptyMap())
        val baseline = wireJson
            .decodeFromString<CombinedSnapshotOutput>(element.toString())
            .authenticatedRuntimePhase
            ?.friendBaseline
        val friends: Map<String, FriendRecord> = baseline?.snapshot?.friendsById.orEmpty()
        return FriendsRoster(
            userId = userId,
            // No explicit freshness flag here, so treat "no friends decoded" as
            // the roster not being ready yet.
            stale = friends.isEmpty(),
            count = baseline?.count ?: friends.size.toLong(),
            detail = "",
            friends = friends,
            friendLogChanged = false
        )
    }
}

/**
 * The roster the UI works with: the friendId -> [FriendRecord] map plus the
 * envelope flags the server sent alongside it (`stale` means the data predates
 * the current backend session and may be stale).
 */
data class FriendsRoster(
    val userId: String,
    val stale: Boolean,
    val count: Long,
    val detail: String,
    val friends: Map<String, FriendRecord>,
    val friendLogChanged: Boolean
)
