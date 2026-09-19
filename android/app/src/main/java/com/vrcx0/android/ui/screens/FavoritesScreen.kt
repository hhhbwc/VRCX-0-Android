package com.vrcx0.android.ui.screens

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import com.vrcx0.android.ui.i18n.tr
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vrcx0.android.data.SessionServices
import com.vrcx0.android.data.repository.FavoriteKind
import com.vrcx0.android.data.repository.buildFavoriteGroups
import com.vrcx0.android.ui.components.RemoteImage
import com.vrcx0.android.ui.components.StateCrossfade
import com.vrcx0.android.ui.components.rememberAvatarNames
import com.vrcx0.android.ui.components.rememberFavorites
import com.vrcx0.android.ui.components.rememberRosterState
import com.vrcx0.android.ui.components.rememberWorldNames

/**
 * Tab 4 -- favourites.
 *
 * Three kinds (world / avatar / friend), each a segmented chip row and a grid.
 * The favourites themselves come from the shared combined snapshot's
 * `favoritesBaseline` -- **not** `app__favorite_list`, which reads the server's
 * local database and answers `[]` here even though the account has 183.
 *
 * A favourite arrives as only an id; its name and thumbnail are resolved by the
 * already-shared world/avatar/roster caches. So this screen draws entirely from
 * state that the feed and friends tabs have already populated, and does no
 * network work of its own.
 */
@Composable
fun FavoritesScreen(
    services: SessionServices?,
    userId: String,
    onOpenWorld: (WorldTarget) -> Unit,
    onOpenUser: (String) -> Unit
) {
    var kind by rememberSaveable { mutableStateOf(FavoriteKind.WORLD) }
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }

    val snapshot = rememberFavorites(services)
    val worldNames = rememberWorldNames(services)
    val avatars = rememberAvatarNames(services)
    val roster = rememberRosterState(services)

    // Every visit to this tab retries the names that are still missing. The
    // first attempt happens in SessionServices.load, but a cold server cache
    // can answer that one with `{}`; retrying on entry is what fills the grid
    // without a restart.
    LaunchedEffect(services) {
        services?.ensureFavoritesResolved()
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Only the three VRChat kinds are segmented here; the two local
            // kinds have no data on this server and would just render empty.
            listOf(FavoriteKind.WORLD, FavoriteKind.AVATAR, FavoriteKind.FRIEND).forEach { k ->
                FilterChip(
                    selected = kind == k,
                    onClick = { kind = k },
                    label = { Text(k.label) }
                )
            }
        }

        // Crossfaded on the snapshot's presence only, not on the snapshot
        // itself: the object is replaced on every refresh, and keying on it
        // would replay the fade each time names resolve. A `null -> loaded`
        // swap is the one transition here that the user genuinely waits
        // through, and it is also the only one where seeing a fade instead of
        // a jump tells them the app is doing something.
        //
        // `AnimatedContent` hands the unwrapped snapshot to the content
        // lambda, so the non-null branch no longer needs a smart cast.
        StateCrossfade(target = snapshot) { loaded ->
        when {
            loaded == null -> LoadingPlaceholder()

            else -> {
                val groups = remember(loaded, kind, worldNames, avatars, roster?.friends.orEmpty()) {
                    buildFavoriteGroups(
                        loaded,
                        kind,
                        worldNames,
                        avatars,
                        roster?.friends.orEmpty()
                    )
                }
                val allItems = groups.flatMap { it.items }

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
                    if (allItems.isEmpty()) {
                        EmptyPlaceholder(
                            title = "还没有${kind.label}收藏",
                            subtitle = "在 VRChat 里收藏的${kind.label}会显示在这里"
                        )
                    } else {
                        FavoriteGrid(
                            groups = groups,
                            kind = kind,
                            services = services,
                            onOpenWorld = onOpenWorld,
                            onOpenUser = onOpenUser
                        )
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun FavoriteGrid(
    groups: List<com.vrcx0.android.data.repository.FavoriteGroupView>,
    kind: FavoriteKind,
    services: SessionServices?,
    onOpenWorld: (WorldTarget) -> Unit,
    onOpenUser: (String) -> Unit
) {
    // Collapsed state per group, keyed by the group's own key. Keyed by kind
    // implicitly: each segment builds its own grid, so switching between 世界 /
    // 模型 / 好友 starts fresh, while tab-switching away and back keeps the
    // user's collapse choices (rememberSaveable).
    var collapsed by rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 12.dp,
            vertical = 8.dp
        ),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        groups.forEach { group ->
            val isCollapsed = group.key in collapsed
            item(key = "g:${group.key}", span = { GridItemSpan(2) }) {
                GroupHeader(
                    group = group,
                    isCollapsed = isCollapsed,
                    onClick = {
                        collapsed = if (isCollapsed) {
                            collapsed - group.key
                        } else {
                            collapsed + group.key
                        }
                    }
                )
            }
            if (!isCollapsed) {
                items(group.items, key = { "i:${it.entityId}" }) { item ->
                    FavoriteCell(
                        item = item,
                        services = services,
                        onClick = {
                            when (kind) {
                                FavoriteKind.WORLD -> onOpenWorld(WorldTarget(item.entityId))
                                FavoriteKind.FRIEND, FavoriteKind.LOCAL_FRIEND ->
                                    onOpenUser(item.entityId)

                                else -> Unit // avatar tap: no sheet yet
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(
    group: com.vrcx0.android.data.repository.FavoriteGroupView,
    isCollapsed: Boolean,
    onClick: () -> Unit
) {
    // A filled block rather than bare text: it separates the heading from the
    // grid content by *surface*, not just weight, which is what the user asked
    // for after bold-on-plain-background still read as a row label.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = group.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = group.occupancy,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f)
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = if (isCollapsed) "▸" else "▾",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

@Composable
private fun FavoriteCell(
    item: com.vrcx0.android.data.repository.FavoriteItem,
    services: SessionServices?,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(Modifier.padding(8.dp)) {
            RemoteImage(
                services = services,
                rawUrl = item.imageUrl,
                label = item.displayTitle,
                size = 84.dp,
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 8.dp
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = item.displayTitle,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (item.subtitle.isNotBlank()) {
                Text(
                    text = item.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun LoadingPlaceholder() {
    // Skeleton grid: grey rounded blocks stand in for the favourite cells so a
    // cold start reads as "loading" rather than a blank screen that might be
    // mistaken for "no favourites".
    Column(Modifier.fillMaxSize()) {
        repeat(3) { _ ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                repeat(2) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(140.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyPlaceholder(title: String, subtitle: String) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
