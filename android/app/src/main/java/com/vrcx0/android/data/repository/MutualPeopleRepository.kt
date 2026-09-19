package com.vrcx0.android.data.repository

import android.util.Log
import com.vrcx0.android.data.remote.CommandRunner
import com.vrcx0.android.data.remote.UserProfile
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
 * The two things a screen needs to render a person it only knows by id: a name
 * and a face.
 *
 * Kept together rather than as two caches because they come from the same
 * command and are always wanted together -- see [MutualPeopleRepository] for
 * why that matters.
 */
data class PersonSummary(
    val id: String,
    val displayName: String,
    /** `iconUrl`; blank when the account has no profile picture. */
    val iconUrl: String,
    /** Fallback face for a user whose profile image is missing or unreadable. */
    val avatarThumbnailUrl: String
) {
    /** Whichever image is worth trying first. */
    val imageUrl: String get() = iconUrl.ifBlank { avatarThumbnailUrl }
}

/**
 * Resolves `usr_...` ids into names **and avatars** for the mutual-friends
 * graph.
 *
 * ## Why one command, not two
 *
 * `app__vrchat_user_get` answers with the full user record: `displayName`,
 * `iconUrl` and `currentAvatarThumbnailImageUrl` all arrive in the same reply.
 * The graph needs all three -- the list shows a face beside every name, and the
 * detail card behind a tap shows the same face larger -- so splitting this into
 * a name lookup and an avatar lookup would double the round trips for data that
 * was already in hand. This replaced a names-only repository for exactly that
 * reason.
 *
 * ## Why it is needed at all
 *
 * A graph snapshot carries **ids only**. A *friend's* name and icon can be
 * taken from the roster, but a **mutual** (a friend of a friend) exists nowhere
 * else on the device, and there is no batch lookup for them. Without this, the
 * screen fell back to printing a truncated id (`usr_a1b2c3…`), which is what
 * the user reported: *"点击展开后为什么名字显示的是 usr 什么什么的，要显示正常
 * 的名字"*.
 *
 * Note that "take it from the roster" is itself only a when-it-works path: the
 * roster can be absent (no snapshot yet), or the person may have been unfriended
 * since the snapshot was taken. [get] therefore always goes to the server for
 * anyone whose name has not been resolved yet, regardless of whether they look
 * like a friend.
 *
 * ## Caching and retry
 *
 * Resolved people are cached for the session in a [StateFlow] which the UI
 * observes, so a name lands the moment it arrives rather than at the end of the
 * batch. Ids that fail are released so a later call retries them -- a timeout,
 * a rate limit or a cold server cache is not a stable answer, and treating it
 * as one is how a row stays blank forever.
 *
 * There is no TTL: within one session a VRChat display name is stable enough,
 * and the snapshot itself is minutes old by the time it is rendered.
 */
class MutualPeopleRepository(private val runner: CommandRunner) {

    private val lock = Mutex()
    private val requested = mutableSetOf<String>()

    private val _people = MutableStateFlow<Map<String, PersonSummary>>(emptyMap())

    /** Resolved `userId -> [PersonSummary]`, updated as lookups land. */
    val people: StateFlow<Map<String, PersonSummary>> = _people.asStateFlow()

    /** Four live fetches at a time; the router sits between here and VRChat. */
    private val permits = Semaphore(4)

    /** A resolved name, without suspending. `null` when not looked up yet. */
    fun nameOf(userId: String): String? =
        _people.value[userId]?.displayName?.takeIf { it.isNotBlank() }

    /** A resolved person, without suspending. */
    fun get(userId: String): PersonSummary? = _people.value[userId]

    /**
     * Resolves every id in [userIds] not already asked for. Idempotent; ids
     * that fail are forgotten so a later call retries them.
     */
    suspend fun ensure(userIds: Collection<String>) {
        val missing = mutableListOf<String>()
        lock.withLock {
            for (id in userIds) {
                val key = id.trim()
                if (key.startsWith("usr_") && requested.add(key)) missing.add(key)
            }
        }
        if (missing.isEmpty()) return

        val resolved = coroutineScope {
            missing.map { id ->
                async {
                    permits.withPermit { fetch(id) }
                }
            }.awaitAll()
        }

        val found: Map<String, PersonSummary> = missing.zip(resolved)
            .mapNotNull { (id, person) -> person?.let { id to it } }
            .toMap()
        if (found.isNotEmpty()) _people.update { it + found }

        // Let failures retry on a later ensure().
        if (found.size < missing.size) {
            val ok = found.keys
            lock.withLock { missing.removeAll { it !in ok } }
        }
    }

    /**
     * Resolves a single id and returns it, for the one-off tap-through case.
     *
     * Unlike [ensure] this reports failure by returning `null` rather than
     * scheduling a retry, because the caller is holding the answer already.
     */
    suspend fun fetchNow(userId: String): PersonSummary? =
        fetch(userId.trim()).also { person ->
            if (person != null) _people.update { it + (userId.trim() to person) }
        }

    private suspend fun fetch(userId: String): PersonSummary? = runCatching {
        val reply = runner("app__vrchat_user_get", mapOf("userId" to userId))
        val profile = wireJson.decodeFromJsonElement<UserProfile>(unwrapDataEnvelope(reply))
        PersonSummary(
            id = userId,
            displayName = profile.displayName,
            iconUrl = profile.iconUrl.ifBlank { profile.userIcon },
            avatarThumbnailUrl = profile.currentAvatarThumbnailImageUrl
                .ifBlank { profile.currentAvatarImageUrl }
        ).takeIf { it.displayName.isNotBlank() || it.imageUrl.isNotBlank() }
    }.onFailure {
        Log.w(TAG, "user lookup failed for $userId: ${it.javaClass.simpleName}: ${it.message}")
    }.getOrNull()

    /** Drops everything, e.g. when switching accounts. */
    fun clear() {
        _people.value = emptyMap()
        requested.clear()
    }

    private companion object {
        const val TAG = "VrcxMutualPeople"
    }
}
