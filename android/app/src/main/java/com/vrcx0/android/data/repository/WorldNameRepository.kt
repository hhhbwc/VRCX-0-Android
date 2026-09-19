package com.vrcx0.android.data.repository

import android.util.Log
import com.vrcx0.android.data.remote.CommandRunner
import com.vrcx0.android.data.remote.WorldSummary
import com.vrcx0.android.data.remote.parseWorldSummaries
import com.vrcx0.android.data.remote.unwrapDataEnvelope
import com.vrcx0.android.data.remote.wireJson
import com.vrcx0.android.data.remote.worldIdFromLocation
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
 * Resolves `wrld_...` ids into world names.
 *
 * A feed row and a friend record both describe where someone is as an id
 * (`wrld_61e374f5-a05f-44a9-80ff-6b845923dcd3:83734~group(...)`), which is
 * unreadable. Two sources are available and they are not the same:
 *
 *  - the **feed row** already carries a `worldName` -- no lookup needed;
 *  - a **friend record** carries only `worldId`/`location`, so the name has to
 *    be fetched.
 *
 * ## Two commands, and why both are needed
 *
 * `app__world_summaries_get` is the batch call, but it reads the server's
 * **local world cache** -- the worlds seen in game logs and the feed -- and
 * simply omits anything else. Verified on this deployment: 100 requested ids,
 * 10 answers, all of them places friends had actually visited. The 89 favourite
 * worlds the account has never entered are invisible to it.
 *
 * `app__world_get` is a **live** VRChat fetch for one id (~0.37 s cold, ~3 ms
 * once the server has cached it), so it covers exactly the gap the batch call
 * leaves. Hence the two-phase [ensure]: batch first, then the misses one by
 * one, capped concurrency, results merged into the same [StateFlow].
 */
class WorldNameRepository(private val runner: CommandRunner) {

    private val _summaries = MutableStateFlow<Map<String, WorldSummary>>(emptyMap())

    /** worldId -> summary. Observe this from Compose to re-render on arrival. */
    val summaries: StateFlow<Map<String, WorldSummary>> = _summaries.asStateFlow()

    private val lock = Mutex()
    private val requested = mutableSetOf<String>()

    /** Four live fetches at a time; the router sits between here and VRChat. */
    private val permits = Semaphore(4)

    /** Looks up an already-resolved name without suspending. */
    fun cachedName(worldId: String): String? =
        _summaries.value[worldId]?.name?.takeIf { it.isNotBlank() }

    fun cached(worldId: String): WorldSummary? = _summaries.value[worldId]

    /**
     * Resolves every id in [worldIds] that is not already known.
     *
     * Safe to call repeatedly: ids that resolved are remembered, and ones that
     * did not are released for a later retry -- a timeout, a rate limit or a
     * cold cache is not a stable answer, and a permanently blank grid is the
     * visible symptom of treating it as one.
     */
    suspend fun ensure(worldIds: Collection<String>) {
        val candidates = worldIds
            .map { it.trim() }
            .filter { it.startsWith("wrld_") }
            .distinct()

        // `Sequence.filter` cannot host a suspend call (withLock), so the
        // de-duplication happens in one guarded pass over a plain list.
        val missing = lock.withLock { candidates.filter { requested.add(it) } }
        if (missing.isEmpty()) return

        val resolved = mutableMapOf<String, WorldSummary>()

        // Phase 1: the cheap batch, served from the server's local cache.
        missing.chunked(CHUNK).forEach { batch ->
            val reply = runCatching {
                runner("app__world_summaries_get", mapOf("worldIds" to batch))
            }.onFailure {
                Log.w(TAG, "world names failed for ${batch.size} ids: ${it.message}")
            }.getOrNull()

            val parsed = reply?.let {
                runCatching { parseWorldSummaries(it) }
                    .onFailure { Log.w(TAG, "world reply did not parse: ${it.message}") }
                    .getOrDefault(emptyMap())
            }.orEmpty()

            if (parsed.isNotEmpty()) {
                resolved += parsed
            } else {
                Log.w(TAG, "world cache answered 0 of ${batch.size} (${reply.toString().take(120)})")
            }
        }

        // Phase 2: whatever the cache has never seen, fetched live.
        //
        // Done in waves with a [StateFlow] update after each: 90 favourite
        // worlds take ~8 s at four concurrent fetches, and updating per wave
        // lets the grid fill progressively instead of all at once at the end.
        val cacheMisses = missing.filter { it !in resolved }
        if (cacheMisses.isNotEmpty()) {
            if (cacheMisses.size > LIVE_FALLBACK_LOG_THRESHOLD) {
                Log.i(TAG, "cache had ${resolved.size}/${missing.size}; fetching ${cacheMisses.size} live")
            }
            cacheMisses.chunked(LIVE_WAVE).forEach { wave ->
                val live = coroutineScope {
                    wave.map { id -> async { permits.withPermit { fetchLive(id) } } }.awaitAll()
                }
                val found = live.filterNotNull()
                    .filter { it.id.isNotBlank() }
                    .associateBy { it.id }
                if (found.isNotEmpty()) {
                    resolved += found
                    _summaries.update { it + found }
                }
            }
        }

        if (resolved.isNotEmpty()) {
            _summaries.update { it + resolved }
        }

        // Only the still-unresolved ids go back on the retry pile. They are
        // re-fetched the next time a screen calls ensure(), not in a loop here.
        val unresolved = missing.filter { it !in resolved }
        if (unresolved.isNotEmpty()) {
            lock.withLock { unresolved.forEach { requested.remove(it) } }
        }
    }

    /**
     * One live fetch through `app__world_get`.
     *
     * The command wraps its answer in the stringified-document envelope, and a
     * world the account cannot see comes back as an error rather than an empty
     * object -- both are handled by returning `null` and letting the caller
     * decide whether to retry.
     */
    private suspend fun fetchLive(worldId: String): WorldSummary? = runCatching {
        val reply = runner("app__world_get", mapOf("worldId" to worldId))
        wireJson.decodeFromJsonElement<WorldSummary>(unwrapDataEnvelope(reply))
    }.onFailure {
        Log.w(TAG, "live fetch failed for $worldId: ${it.message}")
    }.getOrNull()

    /** Convenience for a single location string; returns the id's name if known. */
    suspend fun nameForLocation(location: String): String? {
        val id = worldIdFromLocation(location)
        if (id.isBlank()) return null
        ensure(listOf(id))
        return cachedName(id)
    }

    /** Drops everything, e.g. when switching accounts. */
    fun clear() {
        _summaries.value = emptyMap()
        requested.clear()
    }

    private companion object {

        const val TAG = "VrcxWorlds"

        /**
         * Ids per request. The command takes a list, but a very long one would
         * become a long URL-ish body and a slow VRChat round trip; 40 keeps the
         * latency per call predictable.
         */
        const val CHUNK = 40

        /** Only log the live-fallback summary when it is doing real work. */
        const val LIVE_FALLBACK_LOG_THRESHOLD = 3

        /** Live fetches per wave; each wave ends with a [StateFlow] update. */
        const val LIVE_WAVE = 8
    }
}
