package com.vrcx0.android.ui.screens

import com.vrcx0.android.data.repository.SuggestedRoom
import com.vrcx0.android.data.repository.buildSuggestedRooms
import com.vrcx0.android.data.repository.BrowseHistoryItem
import com.vrcx0.android.data.repository.BrowseHistoryRepository
import com.vrcx0.android.ui.i18n.tr
import com.vrcx0.android.ui.i18n.trf
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vrcx0.android.data.SessionServices
import com.vrcx0.android.data.LocalAppSettings
import com.vrcx0.android.data.repository.MyGroup
import com.vrcx0.android.data.repository.WorldGathering
import com.vrcx0.android.data.repository.buildMyGroups
import kotlinx.coroutines.launch
import com.vrcx0.android.data.repository.buildWorldGatherings
import com.vrcx0.android.ui.components.RemoteAvatar
import com.vrcx0.android.ui.components.RemoteImage
import com.vrcx0.android.ui.components.rememberGroups
import com.vrcx0.android.ui.components.rememberMyGroupIds
import com.vrcx0.android.ui.components.rememberRosterState
import com.vrcx0.android.ui.components.rememberWorldNames

/**
 * Tab 3 -- home / overview.
 *
 * Three stacked sections, all drawn from state the rest of the app has already
 * fetched (the shared combined snapshot and its name caches), so this screen
 * does no network work of its own:
 *
 *  1. **好友聚集地** -- online friends grouped by the instance they are in,
 *     busiest first. The one piece of genuinely new information in the app, and
 *     it is computed client-side: the server has no "who is here now" command.
 *  2. **我的群组** -- the groups the account belongs to (ids from the user
 *     snapshot, names hydrated via `app__vrchat_group_get`).
 *  3. **最近访问世界** -- not yet wired; the server's `app__vrchat_auth_visits_get`
 *     answers a bare count rather than a list on this deployment, so there is
 *     nothing to render. The section is left out rather than showing a fake
 *     list.
 */
@Composable
fun HomeScreen(
    services: SessionServices?,
    userId: String,
    onOpenWorld: (WorldTarget) -> Unit,
    onOpenFeed: (com.vrcx0.android.data.remote.FeedRowOutput) -> Unit
) {
    val roster = rememberRosterState(services)
    val worldNames = rememberWorldNames(services)
    val myGroupIds = rememberMyGroupIds(services)
    val groups = rememberGroups(services)
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }

    // Same retry-on-entry rule as the favourites tab: a group or world that
    // failed to resolve on the cold start gets another chance here.
    LaunchedEffect(services) {
        services?.ensureGroupsResolved()
    }

    // Latest activity, previewed on the overview. Three rows is enough to show
    // "something happened" without duplicating the whole feed tab.
    var latestFeed by remember { mutableStateOf<List<com.vrcx0.android.data.remote.FeedRowOutput>>(emptyList()) }
    LaunchedEffect(services, userId) {
        val runner = services?.runner ?: return@LaunchedEffect
        runCatching {
            com.vrcx0.android.data.repository.FeedRepository(runner).latest(userId, maxRows = 3)
        }.onSuccess { out ->
            latestFeed = out.rows
            // Resolve the worlds the preview lines mention, or they read
            // "在 未知世界" until the feed tab happens to load them. A feed
            // location carries an instance suffix (`wrld_x:123~region(eu)`),
            // so it must be normalised before it can key the name cache.
            services?.worlds?.ensure(
                out.rows.map { com.vrcx0.android.data.remote.worldIdFromLocation(it.location.orEmpty()) }
                    .filter { it.startsWith("wrld_") }
            )
        }
    }

    // Recently-browsed worlds (the desktop app records a row each time a world
    // detail opens; this reads them back for a "jump back in" strip). Rows
    // recorded before the title fix carry a blank title and an entity id --
    // displaying a raw wrld_ id is forbidden, so the world-name cache resolves
    // them (and triggers the fetch for the ones it does not have yet).
    var recentWorlds by remember { mutableStateOf<List<BrowseHistoryItem>>(emptyList()) }
    LaunchedEffect(services, userId) {
        val runner = services?.runner ?: return@LaunchedEffect
        runCatching {
            BrowseHistoryRepository(runner).query(userId, kind = "world", limit = 20)
        }.onSuccess { rows ->
            recentWorlds = rows
            services?.worlds?.ensure(rows.map { it.entityId }.filter { it.startsWith("wrld_") })
        }
    }

    // "Your VRChat in numbers": aggregated from the data the server already
    // records on its own (feed + friend log + browse history). No game client
    // needed -- unlike game-log / instance-activity, which stay empty without
    // one. Loaded lazily so the overview above stays snappy.
    var feedStats by remember { mutableStateOf<com.vrcx0.android.data.repository.FeedStats?>(null) }
    var friendLogStats by remember { mutableStateOf<com.vrcx0.android.data.repository.FriendLogStats?>(null) }
    var browseStats by remember { mutableStateOf<com.vrcx0.android.data.repository.BrowseStats?>(null) }
    LaunchedEffect(services, userId) {
        val runner = services?.runner ?: return@LaunchedEffect
        val stats = com.vrcx0.android.data.repository.StatsRepository(runner)
        runCatching { stats.feedStats(userId) }.onSuccess { feedStats = it }
        runCatching { stats.friendLogStats(userId) }.onSuccess { friendLogStats = it }
        runCatching { stats.browseStats(userId) }.onSuccess { browseStats = it }
    }

    // A gathering of one is just a friend who is online -- it says nothing
    // the friends tab does not, so single-occupant rooms are not cards.
    val gatherings = remember(roster, worldNames) {
        buildWorldGatherings(roster?.friends?.values.orEmpty(), worldNames)
            .filter { it.count >= 2 }
    }
    // "Guess you want to go": rooms of the friends the user knows best.
    val favoriteIds = remember(services) {
        services?.snapshot?.favorites?.value?.favoriteFriendIds?.toSet().orEmpty()
    }
    val rosterOrder = remember(roster) {
        roster?.friends?.keys?.withIndex()?.associate { (i, id) -> id to i }.orEmpty()
    }
    val suggested = remember(roster, worldNames, favoriteIds) {
        buildSuggestedRooms(
            roster?.friends?.values.orEmpty(),
            favoriteIds,
            rosterOrder,
            worldNames
        )
    }

    // The overview used to stack every card vertically, which pushed the
    // groups section off-screen behind 20+ gathering rows. Now: a horizontal
    // strip showing the busiest few, with "show all" expanding in place --
    // partial by default, complete on demand.
    var showAllGatherings by remember { mutableStateOf(false) }
    val visibleGatherings =
        if (showAllGatherings) gatherings else gatherings.take(GATHERING_PREVIEW)

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            scope.launch {
                refreshing = true
                services?.load(userId, force = true)
                refreshing = false
            }
        },
        modifier = Modifier.fillMaxSize()
    ) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ---- latest activity (the "alive" strip) ----
        if (latestFeed.isNotEmpty()) {
            item(key = "feed-header") { SectionHeader("最新动态".tr(), "") }
            items(latestFeed, key = { "feed:${it.rowId ?: it.createdAt}:${it.userId}" }) { row ->
                FeedPreviewCard(row, worldNames, onOpenFeed)
            }
        }

        item(key = "gather-header") {
            SectionHeader(
                "好友聚集地".tr(),
                if (gatherings.isEmpty()) "" else "${gatherings.size} 个房间".trf(gatherings.size)
            )
        }

        if (gatherings.isEmpty()) {
            item(key = "gather-empty") {
                EmptyCard("当前没有好友在线".tr(), "好友上线后会按所在世界聚到这里".tr())
            }
        } else if (showAllGatherings) {
            items(visibleGatherings, key = { it.location }) { gathering ->
                GatheringCard(gathering, services, onOpenWorld)
            }
            item(key = "gather-toggle") {
                ShowAllToggle(expanded = true) { showAllGatherings = false }
            }
        } else {
            item(key = "gather-strip") {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp)
                ) {
                    items(visibleGatherings, key = { it.location }) { gathering ->
                        GatheringCard(
                            gathering,
                            services,
                            onOpenWorld,
                            modifier = Modifier.width(260.dp)
                        )
                    }
                }
            }
            if (gatherings.size > GATHERING_PREVIEW) {
                item(key = "gather-toggle") {
                    ShowAllToggle(expanded = false) { showAllGatherings = true }
                }
            }
        }

        // The groups list is gone from the overview: 42 rows pushed
        // everything else below the fold, and none of it is time-sensitive.

        // ---- suggested rooms (familiar friends, online now) ----
        item(key = "suggest-header") { SectionHeader("猜你想去".tr(), "") }
        if (suggested.isEmpty()) {
            item(key = "suggest-empty") {
                EmptyCard("还没有可推荐的世界".tr(), "熟悉的好友在线时会显示在这里".tr())
            }
        } else {
            item(key = "suggest-strip") {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp)
                ) {
                    items(suggested, key = { it.location }) { room ->
                        SuggestedRoomCard(room, services, onOpenWorld, Modifier.width(240.dp))
                    }
                }
            }
        }

        // ---- recently browsed (jump back in) ----
        if (recentWorlds.isNotEmpty()) {
            item(key = "recent-header") { SectionHeader("最近浏览".tr(), "") }
            item(key = "recent-strip") {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp)
                ) {
                    items(recentWorlds, key = { it.entityId }) { item ->
                        RecentWorldCard(item, worldNames, onOpenWorld)
                    }
                }
            }
        }

        // ---- your numbers (aggregated from server-recorded data) ----
        val fs = feedStats; val fls = friendLogStats; val bs = browseStats
        if (fs != null || fls != null || bs != null) {
            item(key = "stats-header") { SectionHeader("数据统计".tr(), "") }
            item(key = "stats-grid") {
                StatsGrid(fs, fls, bs)
            }
        }
    }
    }
}

/**
 * "Your VRChat in numbers": a grid of small stat cells, each a count aggregated
 * from data the server already records (no game client required).
 */
@Composable
private fun StatsGrid(
    feed: com.vrcx0.android.data.repository.FeedStats?,
    log: com.vrcx0.android.data.repository.FriendLogStats?,
    browse: com.vrcx0.android.data.repository.BrowseStats?
) {
    // Two columns of stat cells; each cell is a big number + a small label.
    // `tr(lang)` (non-composable) is used because `buildList` is not a
    // composable lambda.
    val lang = LocalAppSettings.current.language
    val cells = buildList {
        feed?.takeIf { !it.isEmpty }?.let { f ->
            add("动态总数".tr(lang) to f.total.toString())
            add("好友上线".tr(lang) to f.online.toString())
            add("好友下线".tr(lang) to f.offline.toString())
            add("位置变化".tr(lang) to f.location.toString())
            add("换了模型".tr(lang) to f.avatar.toString())
            add("状态变化".tr(lang) to f.status.toString())
        }
        log?.takeIf { !it.isEmpty }?.let { l ->
            add("添加好友".tr(lang) to l.added.toString())
            add("删除好友".tr(lang) to l.removed.toString())
            add("好友改名".tr(lang) to l.renamed.toString())
            add("信任变化".tr(lang) to l.trustChanged.toString())
        }
        browse?.takeIf { !it.isEmpty }?.let { b ->
            add("浏览过的世界".tr(lang) to b.distinctWorlds.toString())
            add("累计浏览次数".tr(lang) to b.totalViews.toString())
        }
    }
    if (cells.isEmpty()) return

    val chunks = cells.chunked(2)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        chunks.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, value) ->
                    StatCell(label, value, Modifier.weight(1f))
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * A one-line preview of a feed row: who did what, when. Tap opens the feed
 * detail sheet (via [onOpenFeed], which also switches to the feed tab).
 */
@Composable
private fun FeedPreviewCard(
    row: com.vrcx0.android.data.remote.FeedRowOutput,
    worldNames: Map<String, com.vrcx0.android.data.remote.WorldSummary>,
    onOpenFeed: (com.vrcx0.android.data.remote.FeedRowOutput) -> Unit
) {
    Card(
        onClick = { onOpenFeed(row) },
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = row.displayName ?: row.userId ?: "某人".tr(),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = feedPreviewTypeLabel(row.type),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                val body = feedPreviewBody(row, worldNames)
                if (body.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** Short feed-event label, same mapping as FeedScreen.feedTypeLabel. */
@Composable
private fun feedPreviewTypeLabel(type: String?): String = when (type?.lowercase()) {
    "location", "gps" -> "位置".tr()
    "status" -> "状态".tr()
    "bio" -> "简介".tr()
    "avatar" -> "模型".tr()
    "online" -> "上线".tr()
    "offline" -> "下线".tr()
    "friend" -> "好友".tr()
    "friendrequest" -> "好友请求".tr()
    "trust" -> "信任".tr()
    else -> type ?: "动态".tr()
}

/**
 * One-line body: status/location description, resolved through the world-name
 * cache so a raw wrld_ id never reaches the screen.
 */
@Composable
private fun feedPreviewBody(
    row: com.vrcx0.android.data.remote.FeedRowOutput,
    worldNames: Map<String, com.vrcx0.android.data.remote.WorldSummary>
): String {
    row.statusDescription?.takeIf { it.isNotBlank() }?.let { return it }
    row.status?.takeIf { it.isNotBlank() }?.let { return it }
    row.bio?.takeIf { it.isNotBlank() }?.let { return it }
    val location = row.location.orEmpty()
    if (location.startsWith("wrld_")) {
        val worldId = com.vrcx0.android.data.remote.worldIdFromLocation(location)
        val name = worldNames[worldId]?.name ?: "未知世界".tr()
        return "在 $name"
    }
    if (location.isNotBlank()) {
        return when (location.lowercase()) {
            "private" -> "在私密房间".tr()
            "offline" -> "离线".tr()
            "traveling" -> "传送中".tr()
            else -> ""
        }
    }
    return ""
}

/**
 * A "recently browsed" world card: resolved name + view count, tap to reopen.
 */
@Composable
private fun RecentWorldCard(
    item: com.vrcx0.android.data.repository.BrowseHistoryItem,
    worldNames: Map<String, com.vrcx0.android.data.remote.WorldSummary>,
    onOpenWorld: (WorldTarget) -> Unit
) {
    // Name precedence: stored title -> live world-name cache -> (never) the raw
    // id. A wrld_ id must not reach the screen.
    val name = item.title.ifBlank {
        worldNames[item.entityId]?.name.orEmpty()
    }.ifBlank { "未知世界".tr() }
    Card(
        onClick = { onOpenWorld(WorldTarget(item.entityId)) },
        modifier = Modifier.width(200.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (item.viewCount > 0) {
                Text(
                    // `.replace` (TimeFormat.kt's pattern), NOT trf: the trf
                    // path crashed live with IllegalFormatConversionException
                    // (%d vs String) once this card actually rendered.
                    text = "浏览 %d 次".tr().replace("%d", item.viewCount.toString()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * The "guess you want to go" card: whose room it is and how busy it is.
 * Tapping opens the world.
 */
@Composable
private fun SuggestedRoomCard(
    room: com.vrcx0.android.data.repository.SuggestedRoom,
    services: SessionServices?,
    onOpenWorld: (WorldTarget) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = { onOpenWorld(WorldTarget(room.worldId, room.location)) },
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(
            Modifier.padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RemoteImage(
                services = services,
                rawUrl = room.thumbnailUrl,
                label = room.worldName,
                size = 64.dp,
                cornerRadius = 10.dp
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = room.worldName ?: "另一个世界".tr(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "和 " + room.topFriendName + " 一起",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (room.accessLabel.isNotBlank()) {
                    Text(
                        text = room.accessLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** Cards shown in the horizontal strip before "show all" is needed. */
private const val GATHERING_PREVIEW = 6

@Composable
private fun ShowAllToggle(expanded: Boolean, onToggle: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
        Text(if (expanded) "收起".tr() else "查看全部".tr())
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun GatheringCard(
    gathering: WorldGathering,
    services: SessionServices?,
    onOpenWorld: (WorldTarget) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = {
            onOpenWorld(
                WorldTarget(
                    worldId = gathering.worldId,
                    location = gathering.location,
                    roster = gathering.friends
                )
            )
        },
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RemoteImage(
                services = services,
                rawUrl = gathering.thumbnailUrl,
                label = gathering.displayName,
                size = 56.dp,
                cornerRadius = 8.dp
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = gathering.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${gathering.count} 位好友 · ${gathering.accessLabel}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // The avatars of the friends present, overlapping, newest last.
            Row {
                gathering.friends.take(4).reversed().forEachIndexed { index, friend ->
                    Box(Modifier.width(if (index == 0) 0.dp else 6.dp))
                    RemoteAvatar(
                        services = services,
                        rawUrl = friend.iconUrl,
                        name = friend.displayName,
                        size = 28.dp
                    )
                }
            }
        }
    }
}

@Composable
private fun MyGroupRow(group: MyGroup, services: SessionServices?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RemoteImage(
                services = services,
                rawUrl = group.iconUrl,
                label = group.displayName,
                size = 40.dp,
                cornerRadius = 8.dp
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = group.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = group.memberCaption,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (group.isRepresenting) {
                Text(
                    text = "代表中".tr(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun EmptyCard(title: String, subtitle: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
