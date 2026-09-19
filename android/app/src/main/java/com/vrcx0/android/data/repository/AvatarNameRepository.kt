package com.vrcx0.android.data.repository

import com.vrcx0.android.data.remote.AvatarSummary
import com.vrcx0.android.data.remote.CommandRunner
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
 * Resolves `avtr_...` ids into avatar ("模型") names.
 *
 * Two places hand the UI an avatar id with no name attached:
 *
 *  - the signed-in account's own snapshot (`currentUser`), where
 *    `currentAvatar` is an id and there is no `currentAvatarName` field at all;
 *  - a feed row of type `avatar`, whose `avatarName`/`ownerId` may be an id.
 *
 * `app__avatar_get` answers a flat avatar object including `name`, but it takes
 * **one** id per call, so this class fans out with a small concurrency cap and
 * memoises the results. A friend record's `currentAvatarName` is empty on this
 * server (verified across all 242 records), so it is not a usable source.
 */
class AvatarNameRepository(private val runner: CommandRunner) {

    private val _avatars = MutableStateFlow<Map<String, AvatarSummary>>(emptyMap())

    /** avatarId -> summary. Observe from Compose to re-render on arrival. */
    val avatars: StateFlow<Map<String, AvatarSummary>> = _avatars.asStateFlow()

    private val lock = Mutex()
    private val requested = mutableSetOf<String>()

    /** Concurrency cap: the router is a small box and VRChat rate-limits. */
    private val permits = Semaphore(4)

    fun cachedName(avatarId: String): String? =
        _avatars.value[avatarId]?.name?.takeIf { it.isNotBlank() }

    fun cached(avatarId: String): AvatarSummary? = _avatars.value[avatarId]

    /** Resolves every id not already asked for. Idempotent. */
    suspend fun ensure(avatarIds: Collection<String>) {
        val candidates = avatarIds
            .map { it.trim() }
            .filter { it.startsWith("avtr_") }
            .distinct()

        // `Sequence.filter` cannot host a suspend call (withLock), so the
        // de-duplication happens in one guarded pass over a plain list.
        val missing = lock.withLock { candidates.filter { requested.add(it) } }

        if (missing.isEmpty()) return

        val resolved = coroutineScope {
            missing.map { id ->
                async {
                    permits.withPermit {
                        runCatching {
                            val reply = runner("app__avatar_get", mapOf("avatarId" to id))
                            wireJson.decodeFromJsonElement<AvatarSummary>(reply)
                        }.getOrNull()
                    }
                }
            }.awaitAll()
        }

        val found = resolved.filterNotNull().associateBy { it.id.ifBlank { it.name } }
        if (found.isNotEmpty()) {
            _avatars.update { it + found }
        }
    }

    fun clear() {
        _avatars.value = emptyMap()
        requested.clear()
    }
}
