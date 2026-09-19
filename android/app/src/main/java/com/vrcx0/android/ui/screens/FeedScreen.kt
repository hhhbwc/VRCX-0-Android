package com.vrcx0.android.ui.screens

import androidx.compose.ui.text.font.FontWeight
import com.vrcx0.android.data.remote.trustRankLabel
import androidx.compose.material3.CardDefaults
import kotlinx.serialization.json.decodeFromJsonElement
import com.vrcx0.android.data.remote.wireJson
import kotlinx.serialization.json.put
import com.vrcx0.android.data.remote.FriendLogRow
import com.vrcx0.android.data.remote.FeedSection
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import com.vrcx0.android.ui.i18n.tr
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vrcx0.android.data.LocalAppSettings
import com.vrcx0.android.data.SessionServices
import com.vrcx0.android.data.remote.CommandRunner
import com.vrcx0.android.data.remote.FeedCursorInput
import com.vrcx0.android.data.remote.FeedFilter
import com.vrcx0.android.data.remote.FeedRowOutput
import com.vrcx0.android.data.remote.instanceAccessLabel
import com.vrcx0.android.data.remote.worldIdFromLocation
import com.vrcx0.android.data.repository.FeedRepository
import com.vrcx0.android.ui.components.RemoteAvatar
import com.vrcx0.android.ui.components.StateCrossfade
import com.vrcx0.android.ui.components.formatFeedTime
import com.vrcx0.android.ui.components.rememberAvatarNames
import com.vrcx0.android.ui.components.rememberRosterState
import com.vrcx0.android.ui.components.rememberWorldNames
import kotlinx.coroutines.launch

/**
 * Activity feed for the signed-in user.
 *
 * - Filter chips scope the query; they map to the server's `FeedFilter` enum
 *   (GPS serialises to the literal "GPS"). Note the server treats an **empty**
 *   `filters` list as "match nothing" and an **omitted** one as "everything",
 *   so nothing selected must send `null` (see [FeedRepository]).
 * - A search box runs `app__feed_search_query` (TEXT search, no cursor paging).
 * - Pull-to-refresh reloads the newest page; "load older" pages via
 *   `app__feed_rows_query` with the cursor from the previous page.
 *
 * Avatars are not on the row: a feed row carries only a `userId`. So the face
 * shown beside an event comes from the roster the app has already loaded, which
 * is why this screen reads [rememberRosterState] instead of fetching anything.
 */
@Composable
fun FeedScreen(
    runner: CommandRunner?,
    services: SessionServices?,
    userId: String,
    onOpenDetail: (FeedRowOutput) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val repo = remember(runner) { runner?.let { FeedRepository(it) } }

    var rows by remember { mutableStateOf<List<FeedRowOutput>>(emptyList()) }
    var cursor by remember { mutableStateOf<FeedCursorInput?>(null) }
    var hasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var filters by remember { mutableStateOf<Set<FeedFilter>>(emptySet()) }
    var search by remember { mutableStateOf("") }
    // "动态" reads the live feed; "日志" reads the friend log the server
    // accumulates (adds, removals -- including being removed -- renames and
    // trust changes). Same screen because both answer "what changed".
    var feedSection by remember { mutableStateOf(FeedSection.FEED) }
    var logRows by remember { mutableStateOf<List<FriendLogRow>>(emptyList()) }
    var logLoading by remember { mutableStateOf(false) }
    var logError by remember { mutableStateOf<String?>(null) }

    val logLoadFailed = "加载失败".tr()
    LaunchedEffect(feedSection, runner, userId) {
        if (feedSection != FeedSection.LOG || runner == null || logRows.isNotEmpty()) {
            return@LaunchedEffect
        }
        logLoading = true
        logError = null
        val reply = runCatching {
            runner(
                "app__friend_log_history_query",
                mapOf(
                    "query" to kotlinx.serialization.json.buildJsonObject {
                        put("userId", kotlinx.serialization.json.JsonPrimitive(userId))
                        put("maxEntries", kotlinx.serialization.json.JsonPrimitive(100))
                    }
                )
            )
        }.getOrNull()
        if (reply == null) {
            logError = logLoadFailed
        } else {
            runCatching {
                wireJson.decodeFromJsonElement<List<FriendLogRow>>(reply)
            }.onSuccess { logRows = it }
                .onFailure { logError = it.message ?: logLoadFailed }
        }
        logLoading = false
    }

    val roster = rememberRosterState(services)
    val worldNames = rememberWorldNames(services)
    val avatarNames = rememberAvatarNames(services)

    /** Newest page: `latest` or `search` depending on the query box. */
    suspend fun loadInitial() {
        val r = repo ?: return
        loading = true
        error = null
        runCatching {
            if (search.isNotBlank()) {
                r.search(userId, search, filters = filters.toList())
            } else {
                r.latest(userId, filters = filters.toList())
            }
        }.onSuccess { out ->
            rows = out.rows
            cursor = out.persistedCursor
            hasMore = out.persistedHasMore
            loading = false
            // Resolve the world names these rows display. The rows already
            // carry a `worldName` for the current location, but not for
            // `previousLocation`, so a "moved from" line still needs a lookup.
            services?.worlds?.ensure(
                out.rows.flatMap { row ->
                    listOf(
                        worldIdFromLocation(row.location.orEmpty()),
                        worldIdFromLocation(row.previousLocation.orEmpty())
                    )
                }.filter { it.isNotBlank() }
            )
        }.onFailure { e ->
            error = e.message
            loading = false
        }
    }

    /** Older page: only meaningful for the non-search ("lookup") mode. */
    suspend fun loadOlder() {
        val r = repo ?: return
        if (search.isNotBlank() || cursor == null || loading) return
        loading = true
        runCatching { r.rows(userId, cursor = cursor, filters = filters.toList()) }
            .onSuccess { out ->
                rows = rows + out.rows
                cursor = out.persistedCursor
                hasMore = out.persistedHasMore
                loading = false
            }
            .onFailure { e ->
                error = e.message
                loading = false
            }
    }

    LaunchedEffect(Unit) { loadInitial() }

    Column(modifier.fillMaxSize()) {
        FeedControls(
            search = search,
            onSearchChange = { search = it },
            onSearchSubmit = { scope.launch { loadInitial() } },
            filters = filters,
            section = feedSection,
            onSectionChange = { feedSection = it },
            onToggleFilter = { f ->
                filters = if (f in filters) filters - f else filters + f
                scope.launch { loadInitial() }
            }
        )

        if (feedSection == FeedSection.LOG) {
            FriendLogList(
                rows = logRows,
                loading = logLoading,
                error = logError
            )
            return@Column
        }

        // The three states here (failed / nothing / content) used to cut
        // straight from one to the next. Fading between them matters most on
        // the empty -> content edge, which is what a cold start looks like.
        //
        // Keyed on a three-value enum rather than the row list: rows change on
        // every page load, and keying on them would replay the fade mid-scroll.
        val feedState = when {
            error != null -> FeedViewState.ERROR
            rows.isEmpty() && !loading -> FeedViewState.EMPTY
            else -> FeedViewState.CONTENT
        }
        StateCrossfade(target = feedState) { viewState ->
        when (viewState) {
            FeedViewState.ERROR -> FeedStatusMessage(
                message = error ?: "加载失败".tr(),
                onRetry = { scope.launch { loadInitial() } }
            )

            FeedViewState.EMPTY -> FeedStatusMessage(
                message = if (search.isNotBlank()) "没有匹配的动态".tr() else "还没有动态".tr(),
                onRetry = null
            )

            FeedViewState.CONTENT -> PullToRefreshBox(
                isRefreshing = loading,
                onRefresh = { scope.launch { loadInitial() } },
                modifier = Modifier.fillMaxSize()
            ) {
                // Filtering happens at render time, not load time, so the
                // setting takes effect on the already-loaded page the moment
                // the toggle flips -- no refetch needed.
                val appSettings = LocalAppSettings.current
                val visibleRows = if (appSettings.hidePrivateFromFeed) {
                    rows.filter { it.location?.lowercase() != "private" }
                } else {
                    rows
                }
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = visibleRows,
                        key = { it.rowId ?: it.createdAt ?: it.hashCode() },
                        // Lets LazyColumn reuse whole subtrees instead of
                        // rebuilding each card's slot table while scrolling.
                        contentType = { "feed-row" }
                    ) { row ->
                        FeedCard(
                            row = row,
                            services = services,
                            avatarUrl = roster?.friends?.get(row.userId.orEmpty())?.iconUrl,
                            worldName = resolveWorldName(row, worldNames),
                            avatarName = resolveAvatarName(row, avatarNames),
                            onClick = { onOpenDetail(row) }
                        )
                    }
                    if (hasMore && search.isBlank()) {
                        item(contentType = "load-more") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { scope.launch { loadOlder() } }
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                if (loading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Text(
                                        "加载更早的动态".tr(),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
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
}

/**
 * Which of the three mutually exclusive things the feed body can be showing.
 *
 * Exists purely to give the crossfade a stable key: the underlying row list is
 * replaced on every load, so it cannot be the key without replaying the fade
 * mid-scroll.
 */
private enum class FeedViewState { ERROR, EMPTY, CONTENT }

@Composable
private fun FeedControls(
    search: String,
    onSearchChange: (String) -> Unit,
    onSearchSubmit: () -> Unit,
    filters: Set<FeedFilter>,
    onToggleFilter: (FeedFilter) -> Unit,
    section: FeedSection,
    onSectionChange: (FeedSection) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        OutlinedTextField(
            value = search,
            onValueChange = onSearchChange,
            singleLine = true,
            placeholder = { Text("搜索动态（好友名 / 世界名）".tr()) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearchSubmit() }),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        // Section switch: 动态 vs 日志.
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            FilterChip(
                selected = section == FeedSection.FEED,
                onClick = { onSectionChange(FeedSection.FEED) },
                label = { Text("动态".tr()) }
            )
            FilterChip(
                selected = section == FeedSection.LOG,
                onClick = { onSectionChange(FeedSection.LOG) },
                label = { Text("日志".tr()) }
            )
        }
        // Horizontally scrollable: six chips overflow a phone width, and the
        // clipped half-chip on the right read as a rendering bug.
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            FeedFilter.entries.forEach { f ->
                FilterChip(
                    selected = f in filters,
                    onClick = { onToggleFilter(f) },
                    label = { Text(feedFilterLabel(f)) }
                )
            }
        }
    }
}

@Composable
private fun FeedCard(
    row: FeedRowOutput,
    services: SessionServices?,
    avatarUrl: String?,
    worldName: String?,
    avatarName: String?,
    onClick: () -> Unit
) {
    // `Card(onClick=...)` rather than `Card(Modifier.clickable())`: the clickable
    // overload folds the indication and the pointer handling into the surface,
    // instead of adding two more nodes per row to walk and draw.
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            RemoteAvatar(
                services = services,
                rawUrl = avatarUrl ?: row.currentAvatarThumbnailImageUrl ?: row.currentAvatarImageUrl,
                name = row.displayName,
                size = 40.dp
            )
            Spacer(Modifier.width(10.dp))
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
                        text = feedTypeLabel(row.type),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                val body = feedBodyText(row, worldName, avatarName)
                if (body.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                row.createdAt?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = formatFeedTime(it, LocalAppSettings.current.relativeFeedTime),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun FeedStatusMessage(message: String, onRetry: (() -> Unit)?) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (onRetry != null) {
                Spacer(Modifier.height(8.dp))
                IconButton(onClick = onRetry) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "重试".tr())
                }
            }
        }
    }
}

// ---------------------------------------------------------------- presentation

@Composable
private fun feedFilterLabel(f: FeedFilter): String = when (f) {
    FeedFilter.Gps -> "位置".tr()
    FeedFilter.Status -> "状态".tr()
    FeedFilter.Bio -> "简介".tr()
    FeedFilter.Avatar -> "模型".tr()
    FeedFilter.Online -> "上线".tr()
    FeedFilter.Offline -> "下线".tr()
}

/**
 * A readable label for a row's `type`.
 *
 * Compared case-insensitively on purpose: the server sends PascalCase
 * (`"GPS"`, `"Online"`, `"Offline"`) because the values are Rust enum variants,
 * so a `when (type)` against lowercase literals matched almost nothing and the
 * card printed the raw variant name instead of a label.
 */
@Composable
private fun feedTypeLabel(type: String?): String = when (type?.lowercase()) {
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
 * The world name to show for a row, preferring the one the row already carries.
 *
 * The feed embeds `worldName` for the *current* location, which is free and
 * always right. `previousLocation` has no name beside it, so that one comes
 * from the lookup by id.
 */
@Composable
private fun resolveWorldName(
    row: FeedRowOutput,
    worldNames: Map<String, com.vrcx0.android.data.remote.WorldSummary>
): String? {
    row.worldName?.takeIf { it.isNotBlank() }?.let { return it }
    val id = worldIdFromLocation(row.location.orEmpty())
    if (id.isBlank()) return null
    return worldNames[id]?.name
}

/**
 * An avatar name, resolving an id if that is what the row carried.
 *
 * A feed row of type `avatar` may hold either a name ("模型") or an id
 * (`avtr_...`) depending on which side of VRChat wrote it; only the id form
 * needs the lookup, and printing an id to a reader is worse than printing
 * nothing.
 */
@Composable
private fun resolveAvatarName(
    row: FeedRowOutput,
    avatarNames: Map<String, com.vrcx0.android.data.remote.AvatarSummary>
): String? {
    val raw = row.avatarName?.takeIf { it.isNotBlank() } ?: return null
    if (!raw.startsWith("avtr_")) return raw
    return avatarNames[raw]?.name?.takeIf { it.isNotBlank() }
}

/** Locations that are not worlds. */
private val NON_WORLD_LOCATIONS = setOf("private", "offline", "traveling", "")

/**
 * The one line of body text a card has room for.
 *
 * The rule that matters: a raw `wrld_...` id never reaches the screen. Before
 * this, a GPS row read `在 wrld_61e374f5-a05f-44a9-80ff-6b845923dcd3:83734~group(...)~region(jp)`,
 * which is both unreadable and long enough to wrap over several lines.
 */
@Composable
private fun feedBodyText(
    row: FeedRowOutput,
    worldName: String?,
    avatarName: String?
): String {
    row.statusDescription?.takeIf { it.isNotBlank() }?.let { return it }
    row.status?.takeIf { it.isNotBlank() }?.let { return statusLabel(it) }
    row.bio?.takeIf { it.isNotBlank() }?.let { return it }

    val location = row.location.orEmpty()
    if (location.isNotBlank()) {
        val lower = location.lowercase()
        if (lower in NON_WORLD_LOCATIONS) {
            return when (lower) {
                "private" -> "在私密房间".tr()
                "offline" -> "离线".tr()
                "traveling" -> "传送中".tr()
                else -> ""
            }
        }
        if (location.startsWith("wrld_")) {
            val access = instanceAccessLabel(location)
            val where = worldName ?: "未知世界".tr()
            return if (access.isBlank()) "在 $where" else "在 $where · $access"
        }
    }

    row.previousStatus?.takeIf { it.isNotBlank() }?.let { return "原状态：${statusLabel(it)}" }
    row.previousBio?.takeIf { it.isNotBlank() }?.let { return it }
    avatarName?.takeIf { it.isNotBlank() }?.let { return "换了模型：$it" }
    row.previousAvatarName?.takeIf { it.isNotBlank() }?.let { return "原模型：$it" }
    return ""
}

/**
 * VRChat social status -> the official client's Chinese label
 * (`dialog.user.status.*`; the mapping is pinned by `StatusLabelTest`).
 */
@Composable
private fun statusLabel(status: String): String = when (status.lowercase()) {
    "join me" -> "欢迎加入".tr()
    "active" -> "在线".tr()
    "ask me" -> "忙碌".tr()
    "busy" -> "请勿打扰".tr()
    "offline" -> "离线".tr()
    else -> status
}


/**
 * The friend-log list: what the server noticed about the user's friends --
 * additions, removals (including being removed), renames and trust changes.
 *
 * The store grows as the server runs; an empty answer on a fresh deployment is
 * "nothing recorded yet", not a failure, and it reads that way.
 */
@Composable
private fun FriendLogList(
    rows: List<FriendLogRow>,
    loading: Boolean,
    error: String?
) {
    // Same three-state machine as the feed body above, and the same reasoning
    // for the key: `rows` is replaced wholesale on each load.
    val logState = when {
        error != null -> FeedViewState.ERROR
        rows.isEmpty() -> FeedViewState.EMPTY
        else -> FeedViewState.CONTENT
    }
    StateCrossfade(target = logState) { viewState ->
    when (viewState) {
        FeedViewState.ERROR -> FeedStatusMessage(message = error ?: "加载失败".tr(), onRetry = null)

        FeedViewState.EMPTY -> FeedStatusMessage(
            message = if (loading) "加载中…".tr() else "暂无日志，服务器运行后会自动记录好友变化".tr(),
            onRetry = null
        )

        FeedViewState.CONTENT -> LazyColumn(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(rows, key = { it.rowId.hashCode().toString() + it.createdAt }) { row ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = friendLogTypeLabel(row.type),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = row.createdAt.take(16).replace('T', ' '),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = when (row.type) {
                                "DisplayName" -> row.previousDisplayName + " → " + row.displayName
                                "TrustLevel" -> trustRankLabel(row.previousTrustLevel).tr() + " → " + trustRankLabel(row.trustLevel).tr()
                                else -> row.displayName
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
    }
}

/** The friend-log event kinds, as short labels. */
private fun friendLogTypeLabel(type: String): String = when (type) {
    "Friend" -> "添加好友"
    "Unfriend" -> "删除好友"
    "FriendRequest" -> "收到好友请求"
    "CancelFriendRequest" -> "取消好友请求"
    "DisplayName" -> "改名"
    "TrustLevel" -> "信任变化"
    else -> type
}
