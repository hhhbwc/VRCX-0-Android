package com.vrcx0.android.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vrcx0.android.data.SessionServices
import com.vrcx0.android.data.remote.AvatarSummary
import com.vrcx0.android.data.remote.CombinedCurrentUser
import com.vrcx0.android.data.remote.FavoritesSnapshot
import com.vrcx0.android.data.remote.GroupSummary
import com.vrcx0.android.data.remote.WorldSummary
import com.vrcx0.android.data.repository.FriendsRoster
import com.vrcx0.android.data.repository.PersonSummary
import kotlinx.coroutines.flow.StateFlow

/**
 * Reads the shared session caches from Compose.
 *
 * Each has to cope with `services == null` (the composition can exist while no
 * tenant is attached). The flow is `remember`ed against the services instance
 * so a recomposition does not hand Compose a new flow identity and re-subscribe
 * on every frame -- which is the classic way a shared flow turns into a
 * per-frame allocation.
 */

@Composable
fun rememberRosterState(services: SessionServices?): FriendsRoster? {
    val flow = services?.let { remember(it) { it.snapshot.roster } }
    return flow?.collectAsStateWithLifecycle()?.value
}

@Composable
fun rememberMe(services: SessionServices?): CombinedCurrentUser? {
    val flow = services?.let { remember(it) { it.snapshot.me } }
    return flow?.collectAsStateWithLifecycle()?.value
}

@Composable
fun rememberWorldNames(services: SessionServices?): Map<String, WorldSummary> {
    val flow = services?.let { remember(it) { it.worlds.summaries } }
    return flow?.collectAsStateWithLifecycle()?.value.orEmpty()
}

/**
 * Names **and avatars** for people the graph only knows as ids.
 *
 * Replaced a names-only lookup: every row in the mutual-graph list now shows a
 * face beside the name, and both come from one `app__vrchat_user_get` reply.
 */
@Composable
fun rememberMutualPeople(services: SessionServices?): Map<String, PersonSummary> {
    val flow = services?.let { remember(it) { it.mutualPeople.people } }
    return flow?.collectAsStateWithLifecycle()?.value.orEmpty()
}

@Composable
fun rememberAvatarNames(services: SessionServices?): Map<String, AvatarSummary> {
    val flow = services?.let { remember(it) { it.avatars.avatars } }
    return flow?.collectAsStateWithLifecycle()?.value.orEmpty()
}

@Composable
fun rememberFavorites(services: SessionServices?): FavoritesSnapshot? {
    val flow = services?.let { remember(it) { it.snapshot.favorites } }
    return flow?.collectAsStateWithLifecycle()?.value
}

@Composable
fun rememberMyGroupIds(services: SessionServices?): List<String> {
    val flow = services?.let { remember(it) { it.snapshot.myGroupIds } }
    return flow?.collectAsStateWithLifecycle()?.value.orEmpty()
}

@Composable
fun rememberGroups(services: SessionServices?): Map<String, GroupSummary> {
    val flow = services?.let { remember(it) { it.groups.groups } }
    return flow?.collectAsStateWithLifecycle()?.value.orEmpty()
}

/** `true` while the roster is being (re)loaded. */
@Composable
fun rememberRosterLoading(services: SessionServices?): Boolean {
    val flow: StateFlow<Boolean>? = services?.let { remember(it) { it.snapshot.loading } }
    return flow?.collectAsStateWithLifecycle()?.value ?: false
}

@Composable
fun rememberRosterError(services: SessionServices?): String? {
    val flow = services?.let { remember(it) { it.snapshot.error } }
    return flow?.collectAsStateWithLifecycle()?.value
}
