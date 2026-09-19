package com.vrcx0.android.data

import com.vrcx0.android.data.remote.CommandRunner
import com.vrcx0.android.data.remote.CombinedCurrentUser
import com.vrcx0.android.data.remote.CombinedSnapshotOutput
import com.vrcx0.android.data.remote.FavoritesSnapshot
import com.vrcx0.android.data.remote.FriendRecord
import com.vrcx0.android.data.remote.worldIdFromLocation
import com.vrcx0.android.data.remote.wireJson
import com.vrcx0.android.data.repository.AvatarNameRepository
import com.vrcx0.android.data.repository.ConfigRepository
import com.vrcx0.android.data.repository.FriendsRoster
import com.vrcx0.android.data.repository.GroupRepository
import com.vrcx0.android.data.repository.ImageProxyRepository
import com.vrcx0.android.data.repository.MutualGraphCache
import com.vrcx0.android.data.repository.MutualGraphRepository
import com.vrcx0.android.data.repository.MutualPeopleRepository
import com.vrcx0.android.data.repository.ProfileRepository
import com.vrcx0.android.data.repository.WorldDetailRepository
import com.vrcx0.android.data.repository.WorldNameRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Session-scoped services, shared by every screen.
 *
 * Two reasons this is one object rather than something each screen builds:
 *
 *  - **A cache is only useful if it is shared.** The friend roster, the world
 *    names behind it and the image bytes are all expensive, and the feed needs
 *    the same roster (to put a face on an event) that the friends tab does. Two
 *    independent copies would double the traffic and desynchronise.
 *  - **The image cache has to outlive a screen.** Avatars come from a ~1.2 s
 *    proxy round trip and are written to disk; rebuilding that per screen would
 *    throw away the only thing making it affordable.
 *
 * Built once per tenant attachment and torn down on sign-out ([clear]).
 */
class SessionServices(
    val runner: CommandRunner,
    scope: CoroutineScope,
    cacheDir: File,
    /**
     * Private, non-evictable storage (Android `filesDir`).
     *
     * Separate from [cacheDir] because one thing here must **not** be treated
     * as disposable: the mutual-graph snapshot. It is minutes of server
     * traversal that cannot be re-derived on demand, whereas everything in
     * `cacheDir` is re-fetchable and may be cleared by the OS at any time. See
     * [MutualGraphCache].
     */
    filesDir: File
) {
    val images = ImageProxyRepository(runner, scope, cacheDir)
    val worlds = WorldNameRepository(runner)

    /**
     * The deep world lookup behind the world sheet.
     *
     * Separate from [worlds] on purpose: that one is a cheap six-field cache
     * answering "what is this `wrld_...` called" for hundreds of ids while lists
     * scroll, this one does a handful of live round trips for one world and is
     * only touched when a sheet opens. See [WorldDetailRepository].
     */
    val worldDetails = WorldDetailRepository(runner)
    val avatars = AvatarNameRepository(runner)
    val groups = GroupRepository(runner)
    val profiles = ProfileRepository(runner)

    /**
     * Names and avatars for people the graph only knows as ids.
     *
     * Replaced a names-only lookup: the graph list puts a face beside every
     * name, and `app__vrchat_user_get` returns both in one reply. See
     * [MutualPeopleRepository].
     */
    val mutualPeople = MutualPeopleRepository(runner)

    /**
     * Drives the mutual-graph fetch and persists its result.
     *
     * The screen builds its own instance today (it needs no session state to do
     * so), but the cache directory belongs here -- it is account storage, not
     * screen state.
     */
    val mutualGraph = MutualGraphRepository(runner, MutualGraphCache(filesDir))

    val config = ConfigRepository(runner)
    val snapshot = SnapshotStore(runner)

    /**
     * Loads the combined snapshot and then, without blocking the lists, the
     * names every screen needs to render what is inside it.
     *
     * Ordering matters: the snapshot must land first so the UI has rows to
     * show; the name lookups are background polish on top of an
     * already-visible list.
     *
     * The three name lookups are fired together rather than in sequence. They
     * are independent and each is network-bound, so serialising them would only
     * add their latencies up.
     */
    suspend fun load(userId: String, force: Boolean = false) {
        val roster = snapshot.refresh(userId, force) ?: return
        val favorites = snapshot.favorites.value
        val myGroupIds = snapshot.myGroupIds.value

        val friendWorldIds = roster.friends.values
            .map { it.worldId.ifBlank { worldIdFromLocation(it.location) } }
            .filter { it.startsWith("wrld_") }

        // Favourites carry world ids of their own, and those are exactly the
        // ones the user is most likely to look at -- so they are resolved up
        // front instead of waiting for the grid to scroll to them.
        val favoriteWorldIds = favorites?.favoriteWorldIds.orEmpty()
            .filter { it.startsWith("wrld_") }

        worlds.ensure(friendWorldIds + favoriteWorldIds)

        // The account's own avatar is an id in the snapshot with no name beside
        // it, so it has to be resolved separately. Note a *friend's* avatar
        // cannot be: VRChat's friend payload carries no avatar id at all
        // (`currentAvatarName` is empty on every record), only an image URL.
        val myAvatarId = snapshot.me.value?.currentAvatar.orEmpty()
        avatars.ensure(
            favorites?.favoriteAvatarIds.orEmpty()
                .filter { it.startsWith("avtr_") } +
                listOf(myAvatarId).filter { it.isNotBlank() }
        )

        groups.ensure(myGroupIds)
    }

    /** Re-reads only the account config store, for the settings screen. */
    suspend fun loadConfig() {
        config.refresh()
    }

    /**
     * (Re-)resolves the names behind the favourites grid.
     *
     * Called when the favourites tab becomes visible. Idempotent by design:
     * ids that resolved already are skipped, and ones that came back empty on a
     * cold start (the world resolver releases them for retry) get another
     * chance. This is what turns a rate-limited first load into a filled grid
     * the moment the user looks at the tab, instead of needing a restart.
     */
    suspend fun ensureFavoritesResolved() {
        val favorites = snapshot.favorites.value ?: return
        worlds.ensure(favorites.favoriteWorldIds.filter { it.startsWith("wrld_") })
        avatars.ensure(favorites.favoriteAvatarIds.filter { it.startsWith("avtr_") })
    }

    /**
     * (Re-)resolves the group list behind the home tab, same retry semantics
     * as [ensureFavoritesResolved].
     */
    suspend fun ensureGroupsResolved() {
        groups.ensure(snapshot.myGroupIds.value)
    }

    /**
     * Whether a presence edit can be shown locally.
     *
     * The card reads `snapshot.me`, so an edit has nowhere to land until the
     * first snapshot has arrived. Callers check this to decide between an
     * immediate local update and falling back to a refresh -- see
     * [applyPresenceEdit].
     */
    val hasSelfSnapshot: Boolean get() = snapshot.me.value != null

    /**
     * Reflects a presence edit in the UI without depending on a snapshot
     * refresh.
     *
     * See [SnapshotStore.applyLocalPresence] for why the refresh cannot be
     * trusted here. `blank` is meaningful (clearing the status text), so a
     * supplied-but-empty string still overwrites.
     *
     * [selfUserId] is optional and only used to drop this account's now-stale
     * entry from [ProfileRepository]'s TTL cache; when omitted the overlay
     * still applies, and the next profile load may briefly serve cached
     * presence.
     */
    fun applyPresenceEdit(
        status: String? = null,
        statusDescription: String? = null,
        pronouns: String? = null,
        selfUserId: String? = null
    ) {
        snapshot.applyLocalPresence(status, statusDescription, pronouns)
        // My own card is cached with a 5-minute TTL and predates this write, so
        // it still holds the previous `status`/`statusDescription`. Drop it or
        // the profile sheet re-renders the stale pair -- the same symptom this
        // method fixes, reached through the cache instead of the refresh patch.
        selfUserId?.let(profiles::invalidate)
    }

    /**
     * Drops account-scoped data but keeps the image files.
     *
     * Used when signing out: another account may sign in on the same server,
     * and while the roster, world names and profiles belong to the account, the
     * cached image bytes do not -- they are keyed by VRChat file id and stay
     * valid. Deleting them would force a fresh ~1.2 s proxy fetch per avatar on
     * the next sign-in for no benefit.
     *
     * The mutual-graph files are **not** dropped here: they are keyed by
     * `ownerUserId` on disk, so a different account signing in cannot read them
     * and a re-signed-in account gets its graph back for free. See
     * [MutualGraphCache].
     */
    fun clearAccountScoped() {
        worlds.clear()
        worldDetails.clear()
        avatars.clear()
        groups.clear()
        profiles.clear()
        mutualPeople.clear()
        config.clear()
        snapshot.clear()
    }

    /** Drops every cache. Called when the tenant/account changes. */
    fun clear() {
        clearAccountScoped()
        images.clear()
    }
}

/**
 * The signed-in account's whole combined snapshot, loaded from one command.
 *
 * `app__backend_runtime_combined_snapshot_get` is ~466 KB and carries **all
 * three** baselines -- the friend roster, the account's own record, and the
 * favourites baseline. Every screen that needs any of them reads this one
 * [StateFlow], which means:
 *
 *  - the 466 KB is fetched once, not once per feature;
 *  - a screen that mounts later sees data that is already there.
 *
 * Renamed from `RosterStore` when favourites moved in, because the name had
 * stopped describing what it holds.
 */
class SnapshotStore(private val runner: CommandRunner) {

    private val _roster = MutableStateFlow<FriendsRoster?>(null)

    /** The friend roster, or `null` before the first successful load. */
    val roster: StateFlow<FriendsRoster?> = _roster.asStateFlow()

    private val _me = MutableStateFlow<CombinedCurrentUser?>(null)

    /** This account's own snapshot: avatar, status, trust tags, groups. */
    val me: StateFlow<CombinedCurrentUser?> = _me.asStateFlow()

    private val _favorites = MutableStateFlow<FavoritesSnapshot?>(null)

    /**
     * The favourites baseline.
     *
     * Read from the snapshot rather than `app__favorite_list`, which queries
     * the server's local database: that has never been populated on this
     * deployment and answers `[]` for all three kinds even though the account
     * has 183 favourites.
     */
    val favorites: StateFlow<FavoritesSnapshot?> = _favorites.asStateFlow()

    private val _myGroupIds = MutableStateFlow<List<String>>(emptyList())

    /**
     * The groups this account belongs to.
     *
     * The only group source on the wire: `app__vrchat_groups_get` is not
     * implemented by this server, so the ids come out of the user snapshot's
     * `presence.groups`.
     */
    val myGroupIds: StateFlow<List<String>> = _myGroupIds.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val mutex = Mutex()

    /**
     * Refreshes the snapshot.
     *
     * Serialised by a mutex rather than left to the caller: several screens ask
     * for this on mount, and firing concurrent 466 KB requests at the router is
     * worse than waiting for one.
     *
     * `force` only skips the "already loaded" shortcut -- it never serves stale
     * data as fresh.
     */
    suspend fun refresh(userId: String, force: Boolean = false): FriendsRoster? {
        if (!force && _roster.value != null && _error.value == null) return _roster.value

        return mutex.withLock {
            if (!force && _roster.value != null && _error.value == null) return@withLock _roster.value

            _loading.value = true
            _error.value = null
            return@withLock runCatching {
                val element = runner("app__backend_runtime_combined_snapshot_get", emptyMap())
                val combined = wireJson.decodeFromString<CombinedSnapshotOutput>(element.toString())

                val phase = combined.authenticatedRuntimePhase

                combined.authenticatedSession?.session?.currentUserSnapshot?.let { me ->
                    _me.value = me
                    _myGroupIds.value = me.presence?.groups.orEmpty()
                }

                phase?.favoritesBaseline?.snapshot?.let { _favorites.value = it }

                val baseline = phase?.friendBaseline
                buildRoster(userId, baseline?.snapshot?.friendsById.orEmpty(), baseline?.count)
            }.onSuccess {
                _roster.value = it
                _loading.value = false
            }.onFailure {
                _error.value = it.message ?: "加载失败"
                _loading.value = false
            }.getOrNull()
        }
    }

    /**
     * Applies a presence edit locally, without a round trip.
     *
     * The combined snapshot is **not** a reliable way to read back a write we
     * just made. On the server, an unauthenticated-`status` pair is declared
     * "local authority" (`CURRENT_USER_REFRESH_LOCAL_AUTHORITY_FIELDS` in
     * `application-realtime`), so the REST refresh path strips `status` and
     * `statusDescription` out of the incoming patch and keeps the previous
     * values. A save therefore succeeds while a follow-up `refresh` keeps
     * returning the old text -- which is exactly the "I saved it and nothing
     * changed" report this method exists to fix.
     *
     * The desktop client sidesteps the same trap by treating the *mutation
     * response body* as the new state (`userProfileRepository.ts`,
     * `updateCurrentUser`). This does the same thing for the one screen that
     * needs it: `app__vrchat_current_user_update` answers with the updated user
     * record, so the caller has authoritative values in hand and does not have
     * to ask again.
     *
     * `blank` is meaningful, not "unset": clearing the status text is a normal
     * edit (VRChat stores it as an empty string), so an explicit blank must
     * overwrite the old value rather than being skipped like a null.
     */
    fun applyLocalPresence(
        status: String? = null,
        statusDescription: String? = null,
        pronouns: String? = null
    ) {
        val current = _me.value ?: return
        _me.value = current.copy(
            status = status ?: current.status,
            statusDescription = statusDescription ?: current.statusDescription,
            pronouns = pronouns ?: current.pronouns
        )
    }

    private fun buildRoster(
        userId: String,
        friends: Map<String, FriendRecord>,
        declaredCount: Long?
    ): FriendsRoster = FriendsRoster(
        userId = userId,
        // No explicit freshness flag on this path, so "nothing decoded" is the
        // only available signal that the roster is not ready.
        stale = friends.isEmpty(),
        count = declaredCount ?: friends.size.toLong(),
        detail = "",
        friends = friends,
        friendLogChanged = false
    )

    fun clear() {
        _roster.value = null
        _me.value = null
        _favorites.value = null
        _myGroupIds.value = emptyList()
        _error.value = null
    }
}
