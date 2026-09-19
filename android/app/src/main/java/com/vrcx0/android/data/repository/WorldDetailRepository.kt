package com.vrcx0.android.data.repository

import android.util.Log
import com.vrcx0.android.data.remote.CommandRunner
import com.vrcx0.android.data.remote.InstanceInfo
import com.vrcx0.android.data.remote.PreviousInstanceRow
import com.vrcx0.android.data.remote.WorldDetail
import com.vrcx0.android.data.remote.WorldFriendVisits
import com.vrcx0.android.data.remote.instanceIdFromLocation
import com.vrcx0.android.data.remote.parseInstanceInfo
import com.vrcx0.android.data.remote.parsePreviousInstances
import com.vrcx0.android.data.remote.parseWorldFriendVisits
import com.vrcx0.android.data.remote.unwrapDataEnvelope
import com.vrcx0.android.data.remote.wireJson
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Everything the world sheet can show, gathered in one place.
 *
 * The sheet used to render from [WorldNameRepository] alone, which only ever
 * held a six-field summary -- so "not detailed enough" was a property of the
 * data, not of the layout. Three separate commands fill it out:
 *
 *  1. `app__world_get` with `full = true` -> the whole world document. Without
 *     `full` the server answers from its local cache, whose table has twelve
 *     columns and no capacity, visits, tags or dates. This one call is what
 *     turns a name-and-thumbnail card into a real profile.
 *  2. `app__world_friend_visits` -> which friends have been here, how often,
 *     and when last. Historical, and labelled as such in the UI.
 *  3. `app__vrchat_instance_get` -> the live head-count for one instance, but
 *     only when a specific `location` was passed in. A world id alone cannot
 *     answer it: VRChat counts per instance.
 *
 * All three are optional. A world the account cannot see, an instance that has
 * since closed, or a friend-visits table that is still empty must each degrade
 * to a missing section rather than an error screen -- which is why every field
 * here is independently nullable.
 */
data class WorldDetailState(
    val world: WorldDetail? = null,
    val visits: WorldFriendVisits? = null,
    val instance: InstanceInfo? = null,
    val previousInstances: List<PreviousInstanceRow> = emptyList(),
    val loading: Boolean = true,
    /** Set only when the primary `world_get` failed; the others fail quietly. */
    val error: String? = null
)

/**
 * One command runner for world data.
 *
 * Deliberately not merged into [WorldNameRepository]: that one is a cache whose
 * whole job is answering "what is this `wrld_...` called" for hundreds of ids at
 * once, and it is called from list rendering. This one does a handful of
 * sequential live round trips for a single world and is only ever called once a
 * sheet opens. Sharing a cache would mean a rich record evicting the cheap ones
 * the lists depend on.
 */
class WorldDetailRepository(private val runner: CommandRunner) {

    private val _state = MutableStateFlow<Map<String, WorldDetailState>>(emptyMap())

    /** worldId -> state. Observe from Compose so a slow fetch fills in live. */
    val states: StateFlow<Map<String, WorldDetailState>> = _state.asStateFlow()

    fun stateFor(worldId: String): WorldDetailState? = _state.value[worldId]

    /**
     * Loads (or reloads) everything for one world.
     *
     * [location] is optional and only affects the live occupant count. Pass the
     * full location when the tap came from a gathering or a feed row, because
     * that is the only case where "how many people are in there right now" has a
     * meaningful answer.
     *
     * Safe to call repeatedly -- opening the same sheet twice reuses the cached
     * answer unless [force] is set, which the refresh button does.
     */
    suspend fun load(
        worldId: String,
        location: String? = null,
        force: Boolean = false
    ) {
        val id = worldId.trim()
        if (!id.startsWith("wrld_")) return

        val existing = _state.value[id]
        if (!force && existing != null && !existing.loading) {
            // Already complete; only fill in the instance if this call knows one
            // and the previous call did not.
            if (location.isNullOrBlank() || existing.instance != null) return
        }

        _state.update { it + (id to (existing ?: WorldDetailState()).copy(loading = true)) }

        // The three lookups are independent, so they overlap. The world fetch is
        // the slow one; the other two answer from local tables in milliseconds.
        coroutineScope {
            val worldJob = async { fetchWorld(id, force) }
            val visitsJob = async { fetchVisits(id) }
            val instanceJob = async { location?.let { fetchInstance(it) } }
            val historyJob = async { fetchPreviousInstances(id) }

            val worldResult = worldJob.await()
            val visits = visitsJob.await()
            val instance = instanceJob.await()
            val history = historyJob.await()

            _state.update { current ->
                val prior = current[id] ?: WorldDetailState()
                current + (id to WorldDetailState(
                    world = worldResult.first ?: prior.world,
                    visits = visits ?: prior.visits,
                    instance = instance ?: prior.instance,
                    previousInstances = if (history.isNotEmpty()) history else prior.previousInstances,
                    loading = false,
                    error = worldResult.second
                ))
            }
        }
    }

    /**
     * The rich world fetch.
     *
     * `full = true` is the whole point of this repository: it bypasses the
     * server's twelve-column cache for the live VRChat document. Returns the
     * error text alongside so the sheet can say what went wrong instead of
     * rendering an empty card.
     */
    private suspend fun fetchWorld(worldId: String, force: Boolean): Pair<WorldDetail?, String?> =
        runCatching {
            val reply = runner(
                "app__world_get",
                mapOf("worldId" to worldId, "force" to force, "full" to true)
            )
            wireJson.decodeFromJsonElement<WorldDetail>(unwrapDataEnvelope(reply))
                .takeIf { it.isLoaded }
        }.onFailure {
            Log.w(TAG, "world detail failed for $worldId: ${it.message}")
        }.fold(
            onSuccess = { it to null },
            onFailure = { null to (it.message ?: "加载失败") }
        )

    /** Which friends have been here before. Empty is a normal answer. */
    private suspend fun fetchVisits(worldId: String): WorldFriendVisits? =
        runCatching {
            parseWorldFriendVisits(runner("app__world_friend_visits", mapOf("worldId" to worldId)))
        }.onFailure {
            Log.w(TAG, "friend visits failed for $worldId: ${it.message}")
        }.getOrNull()

    /**
     * The live occupant count for one instance.
     *
     * The command wants the instance suffix and the world id separately, so a
     * `location` cannot be handed over whole.
     */
    private suspend fun fetchInstance(location: String): InstanceInfo? {
        val worldId = location.substringBefore(':')
        val instanceId = instanceIdFromLocation(location)
        if (worldId.isBlank() || instanceId.isBlank()) return null
        return runCatching {
            parseInstanceInfo(
                runner(
                    "app__vrchat_instance_get",
                    mapOf("worldId" to worldId, "instanceId" to instanceId)
                )
            )
        }.onFailure {
            Log.w(TAG, "instance lookup failed for $location: ${it.message}")
        }.getOrNull()
    }

    /**
     * Past visits to this world, from the game log.
     *
     * This is the play-time ledger the home tab's suggestion ranking wanted and
     * could not find -- here it is per-world, which is exactly what the sheet
     * needs. `time` on each row is milliseconds spent, so it is real data rather
     * than an estimate.
     */
    private suspend fun fetchPreviousInstances(worldId: String): List<PreviousInstanceRow> =
        runCatching {
            parsePreviousInstances(
                runner(
                    "app__game_log_previous_instances_by_world_id",
                    mapOf("worldId" to worldId)
                )
            )
        }.onFailure {
            Log.w(TAG, "previous instances failed for $worldId: ${it.message}")
        }.getOrDefault(emptyList())

    /** Drops everything, e.g. when switching accounts. */
    fun clear() {
        _state.value = emptyMap()
    }

    private companion object {
        const val TAG = "VrcxWorldDetail"
    }
}
