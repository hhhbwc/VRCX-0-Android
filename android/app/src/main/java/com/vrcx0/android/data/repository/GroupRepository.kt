package com.vrcx0.android.data.repository

import android.util.Log
import com.vrcx0.android.data.remote.CommandRunner
import com.vrcx0.android.data.remote.GroupPostsOutput
import com.vrcx0.android.data.remote.GroupSummary
import com.vrcx0.android.data.remote.unwrapDataEnvelope
import com.vrcx0.android.data.remote.wireJson
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Resolves `grp_...` ids into groups, and reads a group's posts.
 *
 * **Where the ids come from.** There is no "my groups" command on this server:
 * `app__vrchat_groups_get` answers *not implemented*. The only group source on
 * the wire is the signed-in user's snapshot, whose `presence.groups` lists the
 * 42 groups this account belongs to. Everything else here is per-group lookup.
 *
 * **Why the cap matters.** `app__vrchat_group_get` measures ~0.42 s and takes
 * one id per call, so hydrating all 42 serially would take ~18 s. A small
 * concurrency window plus a cache brings that down to a couple of seconds, once.
 */
class GroupRepository(private val runner: CommandRunner) {

    private val _groups = MutableStateFlow<Map<String, GroupSummary>>(emptyMap())

    /** groupId -> summary, for every group resolved so far. */
    val groups: StateFlow<Map<String, GroupSummary>> = _groups.asStateFlow()

    private val lock = Mutex()
    private val requested = mutableSetOf<String>()

    /**
     * Four at a time.
     *
     * Higher than the avatar resolver on purpose: the avatar command is served
     * from the server's own cache in ~3 ms, while this one is a live VRChat
     * round trip. Four parallel is enough to make 42 groups take seconds rather
     * than tens of seconds, without burying the router.
     */
    private val permits = Semaphore(4)

    fun cached(groupId: String): GroupSummary? = _groups.value[groupId]

    /** A display name for a group id, or `null` if it is not resolved yet. */
    fun cachedName(groupId: String): String? =
        _groups.value[groupId]?.name?.takeIf { it.isNotBlank() }

    /** Resolves every id not already asked for. Idempotent and safe to repeat. */
    suspend fun ensure(groupIds: Collection<String>) {
        val candidates = groupIds
            .map { it.trim() }
            .filter { it.startsWith("grp_") }
            .distinct()

        // De-duplication has to happen in one guarded pass: `Sequence.filter`
        // cannot host a suspend call, and a plain `filter` on the list would
        // race when two screens ask at the same time.
        val missing = lock.withLock { candidates.filter { requested.add(it) } }
        if (missing.isEmpty()) return

        val resolved = coroutineScope {
            missing.map { id ->
                async {
                    permits.withPermit {
                        runCatching {
                            val reply = runner("app__vrchat_group_get", mapOf("groupId" to id))
                            wireJson.decodeFromJsonElement<GroupSummary>(unwrapDataEnvelope(reply))
                        }.onFailure {
                            Log.w(TAG, "group $id failed: ${it.message}")
                        }.getOrNull()
                    }
                }
            }.awaitAll()
        }

        val found = resolved.filterNotNull()
            .filter { it.id.isNotBlank() || it.name.isNotBlank() }
            .associateBy { it.id }
        if (found.isNotEmpty()) _groups.update { it + found }

        // A group that failed to resolve gets its id back, so the next ensure()
        // retries it -- the same reasoning as the world resolver: a timeout or
        // a rate limit is not a stable answer, and "加载中…" forever is the
        // visible symptom.
        if (found.size < missing.size) {
            val resolvedIds = found.keys
            lock.withLock {
                missing.forEach { if (it !in resolvedIds) requested.remove(it) }
            }
        }
    }

    /**
     * A group's posts, newest first.
     *
     * Returns an empty list rather than throwing: a group with no posts, a
     * group the account cannot read, and a failed request all end up as "no
     * posts to show", and the caller only ever renders the list.
     */
    suspend fun posts(groupId: String): List<com.vrcx0.android.data.remote.GroupPost> =
        runCatching {
            val reply = runner("app__vrchat_group_posts_get", mapOf("groupId" to groupId))
            wireJson.decodeFromJsonElement<GroupPostsOutput>(unwrapDataEnvelope(reply)).posts
        }.getOrDefault(emptyList())

    fun clear() {
        _groups.value = emptyMap()
        requested.clear()
    }

    private companion object {
        const val TAG = "VrcxGroups"
    }
}
