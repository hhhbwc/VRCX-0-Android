package com.vrcx0.android.ui.screens

import com.vrcx0.android.ui.i18n.tr
import com.vrcx0.android.ui.i18n.trf
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vrcx0.android.data.LocalAppSettings
import com.vrcx0.android.data.SessionServices
import com.vrcx0.android.data.remote.FriendRecord
import com.vrcx0.android.data.remote.WorldSummary
import com.vrcx0.android.data.remote.instanceAccessLabel
import com.vrcx0.android.data.remote.worldIdFromLocation
import com.vrcx0.android.data.repository.FriendsRoster
import com.vrcx0.android.ui.components.AnimatedStatusDot
import com.vrcx0.android.ui.components.RemoteAvatar
import com.vrcx0.android.ui.components.rememberRosterError
import com.vrcx0.android.ui.components.rememberRosterLoading
import com.vrcx0.android.ui.components.rememberRosterState
import com.vrcx0.android.ui.components.rememberWorldNames
import kotlinx.coroutines.launch

/**
 * Friend roster for the signed-in user.
 *
 * Data comes from `app__backend_runtime_combined_snapshot_get` (see
 * [com.vrcx0.android.data.RosterStore]) and is **shared** with the feed, which
 * needs the same map to put a face on a feed row. The UI derives everything
 * else client-side:
 *
 *  - presence buckets (online / active / offline) from each record's `state`;
 *  - same-room groups by the `location` instance id of *online* friends;
 *  - ordering (online -> active -> offline), which the wire does NOT send.
 *
 * Names, not ids: a record describes where someone is as `wrld_...`, so the
 * world name comes from [rememberWorldNames] and the row shows
 * "中文吧 Chinese Bar · 公开" rather than `wrld_057b9b0f-...:ae6c8c9293~region(us)`.
 *
 * Tapping a row opens that person's profile sheet.
 */
@Composable
fun FriendsScreen(
    services: SessionServices?,
    userId: String,
    onOpenGraph: () -> Unit,
    onOpenProfile: (FriendRecord) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()

    val roster = rememberRosterState(services)
    val loading = rememberRosterLoading(services)
    val error = rememberRosterError(services)
    val worldNames = rememberWorldNames(services)

    var query by remember { mutableStateOf("") }
    var grouping by remember { mutableStateOf(FriendGrouping.ALL) }

    LaunchedEffect(services, userId) { services?.load(userId) }

    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("搜索好友".tr()) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = onOpenGraph) { Text("关系图".tr()) }
        }

        // Horizontally scrollable -- this row used to wrap and read like a
        // vertical list, which is what "离线选项是竖着的" was.
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 2.dp)
        ) {
            FriendGrouping.entries.forEach { g ->
                FilterChip(
                    selected = g == grouping,
                    onClick = { grouping = g },
                    label = { Text(friendGroupingLabel(g)) }
                )
            }
        }

        when {
            error != null && roster == null ->
                FriendsStatusMessage(error ?: "加载失败".tr()) { scope.launch { services?.load(userId, force = true) } }

            roster == null && !loading ->
                FriendsStatusMessage("还没有好友".tr()) { scope.launch { services?.load(userId, force = true) } }

            else -> {
                val settings = LocalAppSettings.current
                val items = remember(roster, query, grouping, worldNames, settings.language, settings.showInstanceId) {
                    buildFriendList(
                        roster,
                        query,
                        grouping,
                        worldNames,
                        settings.language,
                        settings.showInstanceId
                    )
                }
                // Without this, a loaded-but-empty roster renders a blank screen.
                if (items.isEmpty() && !loading) {
                    FriendsStatusMessage(
                        message = if (query.isNotBlank()) "没有匹配的好友".tr() else "还没有好友".tr(),
                        onRetry = { scope.launch { services?.load(userId, force = true) } }
                    )
                } else {
                    PullToRefreshBox(
                        isRefreshing = loading,
                        onRefresh = { scope.launch { services?.load(userId, force = true) } },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        LazyColumn(
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(
                                items = items,
                                key = {
                                    when (it) {
                                        is FriendListItem.Header -> "h:${it.label}"
                                        is FriendListItem.Friend -> "f:${it.record.id}"
                                    }
                                },
                                // Headers and rows are different shapes; telling
                                // LazyColumn so lets it keep separate pools.
                                contentType = {
                                    when (it) {
                                        is FriendListItem.Header -> "header"
                                        is FriendListItem.Friend -> "friend"
                                    }
                                }
                            ) { item ->
                                when (item) {
                                    is FriendListItem.Header -> FriendRoomHeader(item.label, item.count)
                                    is FriendListItem.Friend -> FriendRow(
                                        record = item.record,
                                        services = services,
                                        worldName = worldNameFor(item.record, worldNames),
                                        onClick = { onOpenProfile(item.record) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FriendRow(
    record: FriendRecord,
    services: SessionServices?,
    worldName: String?,
    onClick: () -> Unit
) {
    // The clickable `Card` overload rather than `Modifier.clickable`: it folds
    // the indication and pointer handling into the surface instead of adding
    // extra nodes for every row of a 242-row list to walk and draw.
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(contentAlignment = Alignment.BottomEnd) {
                RemoteAvatar(
                    services = services,
                    rawUrl = record.iconUrl
                        .ifBlank { record.currentAvatarThumbnailImageUrl }
                        .ifBlank { record.currentAvatarImageUrl },
                    name = friendName(record),
                    size = 40.dp
                )
                // Presence ring: a thin dot in the corner reads more clearly
                // than tinting the whole avatar, and survives both themes.
                //
                // Animated, because a friend going green is the single most
                // useful thing this list can tell you -- without the crossfade
                // you only notice the change if you happened to be looking at
                // that exact row when the stream frame landed.
                AnimatedStatusDot(
                    color = presenceColor(record.state),
                    pulsing = friendBucket(record.state) == "online",
                    size = 12.dp
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = friendName(record),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val lang = LocalAppSettings.current.language
                val showInstanceId = LocalAppSettings.current.showInstanceId
                val loc = friendLocationText(record, worldName, lang, showInstanceId)
                if (loc.isNotBlank()) {
                    Text(
                        text = loc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            if (!LocalAppSettings.current.hideDevicesFromFeed) {
                Text(
                    text = platformLabel(record.platform),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun FriendRoomHeader(label: String, count: Int) {
    Text(
        text = "$label · $count 人",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    )
}

@Composable
private fun FriendsStatusMessage(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            IconButton(onClick = onRetry) {
                Icon(Icons.Outlined.Refresh, contentDescription = "重试".tr())
            }
        }
    }
}

// ---------------------------------------------------------------- model

private enum class FriendGrouping { ALL, SAME_ROOM, ONLINE, ACTIVE, OFFLINE }

private sealed interface FriendListItem {
    data class Header(val label: String, val count: Int) : FriendListItem
    data class Friend(val record: FriendRecord) : FriendListItem
}

@Composable
private fun friendGroupingLabel(g: FriendGrouping): String = when (g) {
    FriendGrouping.ALL -> "全部".tr()
    FriendGrouping.SAME_ROOM -> "同房间".tr()
    FriendGrouping.ONLINE -> "在线".tr()
    FriendGrouping.ACTIVE -> "活跃".tr()
    FriendGrouping.OFFLINE -> "离线".tr()
}

private val OFFLINE_LOCATIONS = setOf("private", "offline", "traveling", "")

private fun presenceColor(state: String): Color = when (friendBucket(state)) {
    "online" -> Color(0xFF1D9E75)
    "active" -> Color(0xFFBA7517)
    else -> Color(0xFF888780)
}

private fun friendBucket(state: String): String {
    val s = state.lowercase().trim()
    return if (s in setOf("online", "active", "offline")) s else "offline"
}

private fun friendName(r: FriendRecord): String =
    r.displayName.ifBlank { r.username.ifBlank { r.id } }

@Composable
private fun platformLabel(p: String): String = when (p.lowercase()) {
    "standalonewindows" -> "PC"
    "android" -> "Android"
    "ios" -> "iOS"
    "web" -> "Web"
    else -> p.ifBlank { "—" }
}

/** The world a friend is in, looked up by id, falling back to nothing. */
private fun worldNameFor(r: FriendRecord, worldNames: Map<String, WorldSummary>): String? {
    val id = r.worldId.ifBlank { worldIdFromLocation(r.location) }
    if (id.isBlank()) return null
    return worldNames[id]?.name
}

/**
 * Where a friend is, in words.
 *
 * The raw location is `wrld_<uuid>:<instance>~region(xx)`, which is what this
 * used to print. Now the id is dressed as a world name plus the access mode,
 * and a location that is not a world ("private"/"traveling") gets its own
 * phrasing instead of being shown verbatim.
 *
 * When the name has not resolved yet the access mode alone is shown rather than
 * a placeholder like "未知世界": "隐身" is true and readable, whereas "未知世界"
 * claims something the app cannot know and hides the part it does.
 */
private fun friendLocationText(
    r: FriendRecord,
    worldName: String?,
    lang: com.vrcx0.android.data.AppLanguage,
    showInstanceId: Boolean
): String {
    val bucket = friendBucket(r.state)
    if (bucket == "offline") return "离线".tr(lang)

    val location = r.location
    val lower = location.lowercase()
    if (location.isNotBlank() && lower !in OFFLINE_LOCATIONS) {
        val access = instanceAccessLabel(location)
        // The instance number ("wrld_x:#83734~...") is meaningless to most
        // people but is how VRChat players actually share "which instance are
        // you in" -- so it is behind a setting, off by default.
        val base = when {
            worldName != null && access.isNotBlank() -> "$worldName · $access"
            worldName != null -> worldName
            access.isNotBlank() -> access
            else -> "另一个世界".tr(lang)
        }
        if (showInstanceId) {
            val number = location.substringAfter(':').substringBefore('~')
            if (number.isNotBlank()) return "$base ·#$number"
        }
        return base
    }

    return when {
        lower == "traveling" && r.travelingToLocation.isNotBlank() ->
            "前往 %s".trf(lang, r.travelingToLocation)

        lower == "private" -> "私密房间".tr(lang)

        else -> r.statusDescription.ifBlank { r.status.ifBlank { "在线".tr(lang) } }
    }
}

private fun buildFriendList(
    roster: FriendsRoster?,
    query: String,
    grouping: FriendGrouping,
    worldNames: Map<String, WorldSummary>,
    lang: com.vrcx0.android.data.AppLanguage,
    showInstanceId: Boolean
): List<FriendListItem> {
    val all = roster?.friends?.values?.toList().orEmpty()
    val q = query.trim().lowercase()
    val filtered = if (q.isBlank()) all else all.filter { f ->
        friendName(f).lowercase().contains(q) ||
            f.username.lowercase().contains(q) ||
            f.id.lowercase().contains(q)
    }

    val bucketOrder = mapOf("online" to 0, "active" to 1, "offline" to 2)
    val byBucketThenName: Comparator<FriendRecord> = compareBy(
        { bucketOrder[friendBucket(it.state)] ?: 3 },
        { friendName(it).lowercase() }
    )

    return when (grouping) {
        FriendGrouping.ALL -> filtered.sortedWith(byBucketThenName).map { FriendListItem.Friend(it) }

        FriendGrouping.ONLINE ->
            filtered.filter { friendBucket(it.state) == "online" }
                .sortedBy { friendName(it).lowercase() }.map { FriendListItem.Friend(it) }

        FriendGrouping.ACTIVE ->
            filtered.filter { friendBucket(it.state) == "active" }
                .sortedBy { friendName(it).lowercase() }.map { FriendListItem.Friend(it) }

        FriendGrouping.OFFLINE ->
            filtered.filter { friendBucket(it.state) == "offline" }
                .sortedBy { friendName(it).lowercase() }.map { FriendListItem.Friend(it) }

        FriendGrouping.SAME_ROOM -> {
            // A "same room" group of one is not a room -- a single friend
            // already shows as Online, and a group header for themselves is
            // noise (the user said exactly this).
            val inRoom = filtered.filter {
                friendBucket(it.state) == "online" &&
                    it.location.isNotBlank() && it.location.lowercase() !in OFFLINE_LOCATIONS
            }
                .groupBy { it.location }
                .filter { it.value.size >= 2 }
                .flatMap { it.value }
            val rooms = inRoom.groupBy { it.location }.toList()
                .sortedByDescending { it.second.size }
            val roomItems = rooms.flatMap { (loc, members) ->
                val sorted = members.sortedBy { friendName(it).lowercase() }
                // The header is a room, so it is labelled with the world name
                // -- the id it used to print meant nothing to a reader.
                listOf(
                    FriendListItem.Header(
                        label = worldNameFor(sorted.first(), worldNames)
                            ?: "另一个世界".tr(lang),
                        count = sorted.size
                    )
                ) + sorted.map { FriendListItem.Friend(it) }
            }
            val others = filtered.filter { it !in inRoom }.sortedWith(byBucketThenName)
            roomItems + if (others.isNotEmpty()) {
                listOf(FriendListItem.Header("其他".tr(lang), others.size)) +
                    others.map { FriendListItem.Friend(it) }
            } else emptyList()
        }
    }
}
