package com.vrcx0.android.ui.screens

import com.vrcx0.android.ui.i18n.tr
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.vrcx0.android.SessionUiState
import com.vrcx0.android.data.AppSettings
import com.vrcx0.android.data.SessionServices
import com.vrcx0.android.data.remote.CommandRunner
import com.vrcx0.android.data.remote.FeedRowOutput
import com.vrcx0.android.data.remote.FriendRecord
import com.vrcx0.android.data.remote.StreamFrame
import com.vrcx0.android.ui.components.VrcxTopBar
import com.vrcx0.android.ui.components.rememberMe
import com.vrcx0.android.ui.components.rememberRosterState
import com.vrcx0.android.ui.sheets.FeedDetailSheet
import com.vrcx0.android.ui.sheets.GroupDetailSheet
import com.vrcx0.android.ui.sheets.NotificationsSheet
import com.vrcx0.android.ui.sheets.UserProfileSheet
import com.vrcx0.android.ui.sheets.WorldDetailSheet
import com.vrcx0.android.data.repository.NotificationRepository
import com.vrcx0.android.data.repository.BrowseHistoryRepository
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/** Bottom tabs, in the order the UI spec fixes them. */
enum class Tab(
    val label: String,
    val icon: ImageVector
) {
    FEED("动态", Icons.AutoMirrored.Outlined.ListAlt),
    FRIENDS("好友", Icons.Outlined.Groups),
    HOME("主页", Icons.Outlined.Home),
    FAVORITES("收藏", Icons.Outlined.Collections),
    PROFILE("个人", Icons.Outlined.Person)
}

/**
 * A person a sheet should open on.
 *
 * [friend] is carried when the tap came from the friends list, because the
 * roster record is the only source of live presence; a tap from a feed row has
 * no record, so the sheet falls back to fetching the user.
 */
private data class ProfileTarget(val userId: String, val friend: FriendRecord?)

/**
 * A world a sheet should open on, and the room inside it if there is one.
 *
 * [location] is the full `wrld_x:12345~region(jp)` and is optional: the
 * favourites grid knows a world id and nothing else, while a gathering or a
 * feed row knows the exact instance. The distinction is what makes a live
 * head-count possible at all -- VRChat counts per instance.
 *
 * [roster] overrides the snapshot-derived live list when the caller already
 * built it (`WorldGathering.friends`); otherwise the sheet filters the shared
 * friend snapshot by [location] itself, which keeps the two call paths giving
 * the same answer.
 */
data class WorldTarget(
    val worldId: String,
    val location: String? = null,
    val roster: List<FriendRecord>? = null
)

@Composable
fun MainScreen(
    state: SessionUiState,
    commandRunner: CommandRunner?,
    services: SessionServices?,
    settings: AppSettings?,
    streamFrames: SharedFlow<StreamFrame>,
    // Drops the VRChat session, keeping this device's tenant. Distinct from
    // onForgetServer on purpose: signing out of an account is not the same as
    // giving up the slot, and the two live in different places on the profile
    // page.
    onSignOut: () -> Unit,
    // Gives up the tenant credential and the address too.
    onForgetServer: () -> Unit
) {
    // Home is the default landing tab: it is the overview the user asked for,
    // and rememberSaveable keeps whatever tab they last visited after that.
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    // Mutual-graph is a sub-destination of the Friends tab; it takes over the
    // content area (own header + back) and hides the generic top bar.
    var showGraph by rememberSaveable { mutableStateOf(false) }

    // Sheets live here rather than inside each screen: a sheet opened from the
    // feed would otherwise survive a tab switch and sit on top of the friends
    // list, because tab content is torn down and a dialog window is not.
    var feedDetail by remember { mutableStateOf<FeedRowOutput?>(null) }
    var profileTarget by remember { mutableStateOf<ProfileTarget?>(null) }
    // A world sheet carries the *room* as well as the world, because "who is in
    // there right now" is a per-instance question -- two friends in different
    // instances of the same world are in different places. The location is what
    // lets the sheet answer it.
    var worldDetail by remember { mutableStateOf<WorldTarget?>(null) }
    var groupDetail by remember { mutableStateOf<String?>(null) }
    var showNotifications by remember { mutableStateOf(false) }
    var unreadCount by remember { mutableStateOf(0) }

    val me = rememberMe(services)
    val roster = rememberRosterState(services)

    // Unread badge behind the bell. Loaded once services are up and re-read
    // whenever the notifications sheet closes (the sheet acknowledges rows and
    // calls [onNotificationsChanged] for the live update while it is open).
    suspend fun reloadUnread() {
        val runner = services?.runner ?: return
        runCatching {
            NotificationRepository(runner).list(state.userId).count { !it.seen }
        }.onSuccess { unreadCount = it }
    }
    LaunchedEffect(services, state.userId) { reloadUnread() }

    // Browse history: opening a world or user detail records one row, matching
    // the desktop app. Fire-and-forget -- a failed write never blocks the sheet
    // the user actually asked for.
    val scope = rememberCoroutineScope()
    fun recordBrowse(kind: String, entityId: String, title: String = "") {
        val runner = services?.runner ?: return
        scope.launch {
            BrowseHistoryRepository(runner).record(
                userId = state.userId,
                kind = kind,
                entityId = entityId,
                title = title
            )
        }
    }
    LaunchedEffect(worldDetail) {
        val id = worldDetail?.worldId ?: return@LaunchedEffect
        recordBrowse("world", id)
    }
    LaunchedEffect(profileTarget) {
        val t = profileTarget ?: return@LaunchedEffect
        recordBrowse("user", t.userId, t.friend?.displayName.orEmpty())
    }

    // Loaded here rather than in FriendsScreen, which is where it used to live.
    //
    // The roster is not friends-only data: the feed needs it to put a face on a
    // row (a feed row carries no avatar URL), the top bar needs it for the
    // counts and for this account's own avatar, and the profile sheet needs it
    // for live presence. Tying the one load to the friends tab meant opening the
    // app on the feed produced initial-letter circles everywhere and a top bar
    // reading "— / —" until the tab was visited by hand.
    LaunchedEffect(services, state.userId) {
        if (services != null && state.userId.isNotBlank()) services.load(state.userId)
    }

    NavigationSuiteScaffold(
        layoutType = NavigationSuiteType.NavigationBar,
        navigationSuiteItems = {
            Tab.entries.forEach { entry ->
                item(
                    selected = tab == entry,
                    onClick = {
                        tab = entry
                        showGraph = false
                    },
                    icon = { Icon(entry.icon, contentDescription = entry.label) },
                    label = { Text(entry.label.tr()) }
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                if (!showGraph) {
                    VrcxTopBar(
                        serverAddress = state.baseUrl
                            .removePrefix("http://")
                            .removePrefix("https://"),
                        streamState = state.streamState,
                        // Read through the observed state, not `flow.value`: the
                        // latter is a one-off read that never triggers a
                        // recomposition, so the counts would stay "—" forever.
                        onlineCount = roster?.friends?.values
                            ?.count { it.state.equals("online", true) },
                        totalCount = roster?.count?.toInt(),
                        // Prefer the live snapshot: `state.displayName` is only
                        // refreshed on sign-in, so a name change would not show.
                        avatarUrl = me?.currentAvatarThumbnailImageUrl
                            ?.ifBlank { me?.currentAvatarImageUrl },
                        displayName = me?.displayName
                            ?.takeIf { it.isNotBlank() }
                            ?: state.displayName.ifBlank { "我".tr() },
                        unreadCount = unreadCount,
                        services = services,
                        onAvatarClick = { tab = Tab.PROFILE },
                        onNotificationsClick = { showNotifications = true }
                    )
                }
            }
        ) { padding ->
            if (showGraph) {
                MutualGraphScreen(
                    services = services,
                    streamFrames = streamFrames,
                    userId = state.userId,
                    friendIds = services?.snapshot?.roster?.value?.friends?.keys?.toList().orEmpty(),
                    onBack = { showGraph = false },
                    onOpenProfile = { target ->
                        profileTarget = ProfileTarget(
                            target,
                            services?.snapshot?.roster?.value?.friends?.get(target)
                        )
                    },
                    modifier = Modifier.fillMaxSize().padding(padding)
                )
                return@Scaffold
            }

            Column(Modifier.fillMaxSize().padding(padding)) {
                // Rendered directly, with no AnimatedContent wrapper.
                //
                // The previous version transitioned tabs through
                // `AnimatedContent` + `SizeTransform(clip = false)` + a scale.
                // Every one of those is expensive in exactly the place a
                // list-heavy screen can least afford it: the transition puts a
                // `graphicsLayer` on the whole subtree, which renders the
                // `LazyColumn` into an offscreen buffer that must be
                // recomposited every frame, and `SizeTransform` re-measures
                // every child on every frame of the change. A tab switch is
                // instantaneous now -- cheaper, and less jarring than scaling a
                // 242-row list.
                when (tab) {
                    Tab.FEED -> FeedScreen(
                        runner = commandRunner,
                        services = services,
                        userId = state.userId,
                        onOpenDetail = { feedDetail = it }
                    )

                    Tab.FRIENDS -> FriendsScreen(
                        services = services,
                        userId = state.userId,
                        onOpenGraph = { showGraph = true },
                        onOpenProfile = { profileTarget = ProfileTarget(it.id, it) }
                    )

                    Tab.HOME -> HomeScreen(
                        services = services,
                        userId = state.userId,
                        onOpenWorld = { target -> worldDetail = target },
                        onOpenFeed = { row ->
                            tab = Tab.FEED
                            feedDetail = row
                        }
                    )

                    Tab.FAVORITES -> FavoritesScreen(
                        services = services,
                        userId = state.userId,
                        onOpenWorld = { target ->
                            worldDetail = WorldTarget(target.worldId, target.location, null)
                        },
                        onOpenUser = { profileTarget = ProfileTarget(it, null) }
                    )

                    Tab.PROFILE -> ProfileScreen(
                        state = state,
                        services = services,
                        settings = settings,
                        onSignOut = onSignOut,
                        onForgetServer = onForgetServer
                    )
                }
            }
        }
    }

    feedDetail?.let { row ->
        FeedDetailSheet(
            services = services,
            row = row,
            onOpenUser = { userId ->
                feedDetail = null
                profileTarget = ProfileTarget(
                    userId = userId,
                    friend = services?.snapshot?.roster?.value?.friends?.get(userId)
                )
            },
            onDismiss = { feedDetail = null }
        )
    }

    profileTarget?.let { target ->
        UserProfileSheet(
            services = services,
            userId = target.userId,
            friend = target.friend,
            onDismiss = { profileTarget = null },
            onOpenWorld = { target ->
                worldDetail = WorldTarget(target.worldId, target.location, null)
            }
        )
    }

    worldDetail?.let { target ->
        WorldDetailSheet(
            services = services,
            worldId = target.worldId,
            location = target.location,
            // The live roster: whoever in the friend list says they are in this
            // exact instance. Taken from the shared snapshot rather than passed
            // down from the screen that opened the sheet, so it stays correct
            // when the sheet is reached from the feed or a profile instead of
            // from the gathering card that built it.
            roster = target.roster ?: services?.snapshot?.roster?.value?.friends?.values
                ?.filter { it.location == target.location },
            onDismiss = { worldDetail = null },
            onOpenUser = { userId ->
                worldDetail = null
                profileTarget = ProfileTarget(userId, null)
            }
        )
    }

    groupDetail?.let { groupId ->
        GroupDetailSheet(
            services = services,
            groupId = groupId,
            onDismiss = { groupDetail = null }
        )
    }

    if (showNotifications) {
        val scope = rememberCoroutineScope()
        NotificationsSheet(
            services = services,
            userId = state.userId,
            onChanged = { scope.launch { reloadUnread() } },
            onDismiss = {
                showNotifications = false
                scope.launch { reloadUnread() }
            }
        )
    }
}