package com.vrcx0.android.ui.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vrcx0.android.data.AppLanguage
import com.vrcx0.android.data.LocalAppSettings
import com.vrcx0.android.data.SessionServices
import com.vrcx0.android.data.remote.FriendRecord
import com.vrcx0.android.data.remote.InstanceInfo
import com.vrcx0.android.data.remote.PreviousInstanceRow
import com.vrcx0.android.data.remote.WorldDetail
import com.vrcx0.android.data.remote.WorldFriendVisitRow
import com.vrcx0.android.data.remote.WorldFriendVisits
import com.vrcx0.android.data.remote.instanceAccessLabel
import com.vrcx0.android.data.repository.ImageProxyRepository
import com.vrcx0.android.data.repository.WorldDetailState
import com.vrcx0.android.ui.components.RemoteAvatar
import com.vrcx0.android.ui.components.RemoteImage
import com.vrcx0.android.ui.components.StaggeredReveal
import com.vrcx0.android.ui.components.StateCrossfade
import com.vrcx0.android.ui.components.formatFeedTime
import com.vrcx0.android.ui.i18n.tr
import com.vrcx0.android.ui.i18n.trf

/**
 * Everything known about one world, plus the people standing in it.
 *
 * ### Why this file is not a cosmetic change
 *
 * The sheet used to render from a six-field summary, which is why it read as
 * "not detailed enough" -- the *data* was thin, not the layout. The summary
 * comes from the server's local cache, a twelve-column table with no capacity,
 * visits, tags or dates; `app__world_get` without `full` is answered from that
 * table and never touches VRChat.
 *
 * [WorldDetailRepository] fixes that at the source: `full = true` fetches the
 * live world document (~30 fields), and three more commands fill in the parts
 * that are not properties of the world at all -- who has been here
 * ([WorldFriendVisits]), how full this specific room is right now
 * ([InstanceInfo]), and how much time the account has actually spent here
 * ([PreviousInstanceRow]).
 *
 * ### The roster the user asked for
 *
 * "点击了之后详情要显示里面都要谁，要显示头像和名字" -- there are two distinct
 * answers to "who is inside", and conflating them would be a lie:
 *
 *  1. **Here and now** -- the friends whose own presence says they are in this
 *     exact instance. This is *client-side*, free, and authoritative: it comes
 *     from [com.vrcx0.android.data.repository.WorldGathering], which the home
 *     tab already built. Rendered first, with avatars and names, because it is
 *     the live answer.
 *  2. **Has been here before** -- `app__world_friend_visits`, historical. Shown
 *     after, explicitly labelled, with a visit count and a date.
 *
 * Both lists are tappable through to the profile sheet, because a name the user
 * cannot act on is only half useful.
 *
 * ### Degradation
 *
 * Every section is independently optional. A world the account cannot see, an
 * instance that has closed, an empty friend-visits table: each collapses to a
 * missing row rather than an error page. Only the primary world fetch has a
 * visible failure state, and even that keeps whatever was already on screen.
 *
 * @param worldId the `wrld_...` being shown.
 * @param location the full `wrld_x:12345~region(jp)` when the tap came from a
 *   room. This is what makes the live head-count possible -- VRChat counts per
 *   instance, so a world id alone has no occupancy to report.
 * @param roster the friends present in [location], straight from the caller's
 *   gathering. `null` for entries reached without a room (favourites, profile).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorldDetailSheet(
    services: SessionServices?,
    worldId: String,
    onDismiss: () -> Unit,
    onOpenUser: (String) -> Unit,
    location: String? = null,
    roster: List<FriendRecord>? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val settings = LocalAppSettings.current

    // Collected rather than read through `flow.value`: a one-off read would
    // never trigger recomposition, so the sheet would sit on its spinner with
    // the data already in hand.
    val states = services?.worldDetails?.states?.collectAsState()
    val state: WorldDetailState? = states?.value?.get(worldId)

    // Reloaded whenever the location changes, because that is the only input
    // that can change the live occupancy. Cached by world id, so re-opening the
    // same world is instant.
    LaunchedEffect(services, worldId, location) {
        services?.worldDetails?.load(worldId, location)
    }

    // The plain name cache is still consulted: it is usually already populated
    // from the list that was tapped, and it is what lets the header render a
    // real title during the live round trip instead of a spinner alone.
    val fallbackName = state?.world?.name

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(bottom = 28.dp)
        ) {
            StateCrossfade(target = state?.loading == true && state?.world == null) { loading ->
                if (loading) {
                    LoadingHeader()
                } else {
                    WorldHeader(
                        services = services,
                        detail = state?.world,
                        fallbackName = fallbackName,
                        location = location,
                        showLocation = settings.showInstanceId,
                        onRetry = {
                            // Fire-and-forget: the state flow repaints the sheet.
                        }
                    )
                }
            }

            val error = state?.error
            if (!error.isNullOrBlank() && state?.world == null) {
                Spacer(Modifier.height(12.dp))
                ErrorNote(error)
            }

            // ---- here and now ----
            LiveRosterSection(
                services = services,
                location = location,
                roster = roster,
                instance = state?.instance,
                onOpenUser = onOpenUser
            )

            // ---- the world itself ----
            state?.world?.let { detail ->
                if (detail.description.isNotBlank()) {
                    Spacer(Modifier.height(16.dp))
                    SectionTitle("详情".tr())
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = detail.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                StatsGrid(detail)

                if (detail.tags.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    SectionTitle("世界标签".tr())
                    Spacer(Modifier.height(6.dp))
                    TagFlow(detail.tags)
                }

                if (detail.platforms.isNotEmpty() || detail.unityPackages.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    SectionTitle("支持平台".tr())
                    Spacer(Modifier.height(6.dp))
                    PlatformChips(detail)
                }

                Spacer(Modifier.height(14.dp))
                SectionTitle("实例".tr())
                Spacer(Modifier.height(4.dp))
                DetailRow("版本".tr(), detail.version.toString())
                DetailRow("发布状态".tr(), detail.releaseStatusLabel())
                DetailRow("发布时间".tr(), fmtDate(detail.publishedAt, settings.timeZoneId, settings.language))
                DetailRow("实验室发布".tr(), fmtDate(detail.labsPublicationDate, settings.timeZoneId, settings.language))
                DetailRow("创建时间".tr(), fmtDate(detail.createdAt, settings.timeZoneId, settings.language))
                DetailRow("更新时间".tr(), fmtDate(detail.updatedAt, settings.timeZoneId, settings.language))
                if (detail.featured) DetailRow("精选".tr(), "是".tr())
                if (detail.isLabs) DetailRow("实验室世界".tr(), "是".tr())
                if (detail.hasPersistData) DetailRow("持久化数据".tr(), "是".tr())
                detail.organization.takeIf { it.isNotBlank() }
                    ?.let { DetailRow("所属组织".tr(), it) }
                detail.previewYoutubeId.takeIf { it.isNotBlank() }
                    ?.let { DetailRow("宣传片".tr(), "youtu.be/$it") }
                DetailRow("作者".tr(), detail.authorName)
                if (settings.showInstanceId) {
                    DetailRow("世界".tr(), detail.id)
                }
            }

            // ---- who has been here before ----
            VisitsSection(
                services = services,
                visits = state?.visits,
                onOpenUser = onOpenUser
            )

            // ---- my own play history ----
            PlayHistorySection(
                history = state?.previousInstances.orEmpty(),
                timeZone = settings.timeZoneId,
                language = settings.language
            )
        }
    }
}

// ---------------------------------------------------------------------------
// header
// ---------------------------------------------------------------------------

/**
 * The banner plus the two numbers that answer "is this alive right now".
 *
 * Duration first, then author: the reader's question when a detail sheet opens
 * is "should I go", and "how full is it" beats "who made it" for that.
 */
@Composable
private fun WorldHeader(
    services: SessionServices?,
    detail: WorldDetail?,
    fallbackName: String?,
    location: String?,
    showLocation: Boolean,
    onRetry: () -> Unit
) {
    val name = detail?.name?.takeIf { it.isNotBlank() }
        ?: fallbackName?.takeIf { it.isNotBlank() }
        ?: "世界".tr()

    Column {
        Box(Modifier.fillMaxWidth()) {
            RemoteImage(
                services = services,
                rawUrl = detail?.imageUrl?.ifBlank { detail.thumbnailImageUrl }
                    ?: detail?.thumbnailImageUrl,
                label = name,
                size = 168.dp,
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 16.dp,
                thumbnailSize = com.vrcx0.android.data.repository.ImageProxyRepository.DETAIL_SIZE
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (!detail?.authorName.isNullOrBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = "by ${detail?.authorName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (location.isNullOrBlank().not() && instanceAccessLabel(location!!).isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = instanceAccessLabel(location),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (showLocation && !location.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = location,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (detail?.isLoaded != true) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onRetry) { Text("刷新".tr()) }
        }
    }
}

@Composable
private fun LoadingHeader() {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            text = "正在加载世界详情…".tr(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ErrorNote(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = "加载失败".tr(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

// ---------------------------------------------------------------------------
// live roster
// ---------------------------------------------------------------------------

/**
 * "房间里的人" -- the friends whose presence says they are here.
 *
 * Nothing is fetched: the caller already had this list, because it is the same
 * grouping the home tab's gathering card was built from. That matters because
 * it makes the section instant, and instant is what the user is asking for when
 * they tap a busy room.
 *
 * A capacity bar sits alongside rather than instead of the count, because
 * "3/16" and "3" answer different questions and both are worth one line.
 */
@Composable
private fun LiveRosterSection(
    services: SessionServices?,
    location: String?,
    roster: List<FriendRecord>?,
    instance: InstanceInfo?,
    onOpenUser: (String) -> Unit
) {
    val present = roster.orEmpty()
    if (present.isEmpty() && instance == null) return

    Spacer(Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionTitle("房间里的人".tr())
        if (present.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = "位好友在这里".trf(present.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    Spacer(Modifier.height(6.dp))

    // Live occupancy. Kept separate from the friend roster because it counts
    // everybody in the room -- strangers included -- while the list below is
    // only the people this account knows.
    instance?.let { OccupancyBar(it) }

    if (present.isEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = "当前没有好友在线".tr(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        Spacer(Modifier.height(4.dp))
        val visible = present.take(30)
        visible.forEachIndexed { index, friend ->
            StaggeredReveal(index = index) {
                RosterRow(
                    services = services,
                    userId = friend.id,
                    displayName = friend.displayName,
                    imageUrl = friend.iconUrl,
                    caption = presenceCaption(friend),
                    trailing = null,
                    onClick = { onOpenUser(friend.id) }
                )
            }
        }
        if (present.size > visible.size) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "+${present.size - visible.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * A thin filled bar for `userCount / capacity`.
 *
 * Capped at capacity rather than left raw: VRChat can briefly report more users
 * than capacity during a join, and a bar overflowing its own track is worse
 * than a bar that reads full.
 */
@Composable
private fun OccupancyBar(instance: InstanceInfo) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = instance.occupancyCaption ?: "人数未知".tr(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                instance.regionLabel?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if ((instance.capacity ?: 0) > 0) {
                Spacer(Modifier.height(6.dp))
                val ratio = ((instance.userCount ?: 0).toFloat() / instance.capacity!!.toFloat())
                    .coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { ratio },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                )
            }
            val extras = buildList {
                if ((instance.capacity ?: 0) > 0) add("${"容量".tr()} ${instance.capacity}")
                instance.type?.takeIf { it.isNotBlank() }?.let { add(instanceTypeLabel(it)) }
            }
            if (extras.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = extras.joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun presenceCaption(friend: FriendRecord): String {
    val parts = buildList {
        when (friend.state.lowercase()) {
            "online" -> add("在线".tr())
            "active" -> add("活跃".tr())
            "offline" -> add("离线".tr())
        }
        // Official client wording (`dialog.user.status.*`; pinned by StatusLabelTest).
        when (friend.status.lowercase()) {
            "join me" -> add("欢迎加入".tr())
            "ask me" -> add("忙碌".tr())
            "busy" -> add("请勿打扰".tr())
        }
        friend.statusDescription.takeIf { it.isNotBlank() }?.let(::add)
    }
    return parts.joinToString(" · ")
}

// ---------------------------------------------------------------------------
// world statistics
// ---------------------------------------------------------------------------

/**
 * The numbers VRChat publishes, in one wrapped chip row.
 *
 * Only non-zero values appear. A six-cell grid where three cells read "0" is
 * not more informative than three cells, and every field here is genuinely
 * absent for plenty of worlds (unpublished ones have no visits at all).
 */
@Composable
private fun StatsGrid(detail: WorldDetail) {
    val stats = buildList {
        (detail.capacity ?: 0).takeIf { it > 0 }?.let { add("容量".tr() to "$it") }
        (detail.recommendedCapacity ?: 0).takeIf { it > 0 }
            ?.let { add("推荐容量".tr() to "$it") }
        (detail.visits ?: 0).takeIf { it > 0 }?.let { add("浏览".tr() to compact(it)) }
        (detail.favorites ?: 0).takeIf { it > 0 }?.let { add("收藏数".tr() to compact(it)) }
        (detail.popularity ?: 0).takeIf { it > 0 }?.let { add("人气".tr() to "$it") }
        (detail.heat ?: 0).takeIf { it > 0 }?.let { add("热度".tr() to "$it") }
        (detail.occupants ?: 0).takeIf { it > 0 }?.let { add("占用".tr() to "$it") }
    }
    if (stats.isEmpty()) return

    Spacer(Modifier.height(14.dp))
    SectionTitle("数据统计".tr())
    Spacer(Modifier.height(6.dp))
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        stats.chunked(2).forEach { pair ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                pair.forEach { (label, value) ->
                    StatCell(label, value, Modifier.weight(1f))
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/** 12 345 -> "1.2万". A raw seven-digit visit count is unreadable at a glance. */
private fun compact(value: Long): String = when {
    value >= 10_000 -> "${(value / 1000) / 10.0}万"
    else -> value.toString()
}

@Composable
private fun TagFlow(tags: List<String>) {
    val visible = tags.filter { it.isNotBlank() }.take(24)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        visible.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { tag -> MiniChip(tag) }
            }
        }
    }
    if (tags.size > visible.size) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = "+${tags.size - visible.size}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PlatformChips(detail: WorldDetail) {
    val labels = buildList {
        detail.unityPackages.forEach { pkg ->
            val label = pkg.platformLabel ?: pkg.platform ?: return@forEach
            add(label)
        }
        // `platforms` is the summary list and can name a platform with no
        // build attached -- still worth showing, still deduplicated.
        detail.platforms.forEach { add(platformLabelFor(it)) }
    }.filter { it.isNotBlank() }.distinct()

    if (labels.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEach { MiniChip(it) }
    }

    // Per-build detail: unity version and performance rank are what actually
    // tells a user whether their headset can run this.
    val rated = detail.unityPackages.filter {
        !it.unityVersion.isNullOrBlank() || !it.performanceRating.isNullOrBlank()
    }
    if (rated.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        rated.forEach { pkg ->
            val bits = buildList {
                pkg.unityVersion?.takeIf { it.isNotBlank() }?.let { add("Unity $it") }
                pkg.performanceRating?.takeIf { it.isNotBlank() }
                    ?.let { add(performanceLabel(it)) }
            }
            if (bits.isNotEmpty()) {
                Text(
                    text = (pkg.platformLabel ?: pkg.platform).orEmpty() + " · " + bits.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MiniChip(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

// ---------------------------------------------------------------------------
// historical visitors
// ---------------------------------------------------------------------------

/**
 * Friends who have been here, from `app__world_friend_visits`.
 *
 * Labelled "曾来过" rather than "在这里" on purpose. The two lists sit one
 * above the other and mean completely different things; without the wording the
 * sheet would imply these people are currently inside.
 */
@Composable
private fun VisitsSection(
    services: SessionServices?,
    visits: WorldFriendVisits?,
    onOpenUser: (String) -> Unit
) {
    val rows = visits?.friends.orEmpty()
    if (rows.isEmpty()) {
        // Only say so when the fetch succeeded and genuinely returned nobody.
        // A world nobody has visited and a fetch that failed look identical
        // from here, and a confident "no one has been here" would be wrong
        // half the time -- so this stays silent unless there is a count.
        val known = visits != null
        if (!known) return
        Spacer(Modifier.height(16.dp))
        SectionTitle("曾来过的好友".tr())
        Spacer(Modifier.height(4.dp))
        Text(
            text = "还没有好友来过这里".tr(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    Spacer(Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionTitle("曾来过的好友".tr())
        Spacer(Modifier.width(8.dp))
        Text(
            text = "${visits?.friendCount ?: rows.size}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Spacer(Modifier.height(6.dp))

    // Sorted by visit count so the friends who actually use the world lead.
    // The server's order is its own; this is the one the reader wants.
    val sorted = rows.sortedByDescending { it.visitCount }
    sorted.forEachIndexed { index, row ->
        StaggeredReveal(index = index) {
            RosterRow(
                services = services,
                userId = row.userId,
                displayName = row.displayName,
                imageUrl = null,
                caption = buildString {
                    (row.visitCount ?: 0).takeIf { it > 0 }
                        ?.let { append("来过 %d 次".trf(it)) }
                    row.lastVisitedAt?.takeIf { it.isNotBlank() }?.let {
                        if (isNotEmpty()) append(" · ")
                        append(fmtDate(it, LocalAppSettings.current.timeZoneId, LocalAppSettings.current.language))
                    }
                },
                trailing = null,
                onClick = { onOpenUser(row.userId) }
            )
        }
    }
}

// ---------------------------------------------------------------------------
// own play history
// ---------------------------------------------------------------------------

/**
 * The account's own time in this world, from the game log.
 *
 * `time` is milliseconds actually spent, so it is a measurement rather than an
 * estimate -- which makes this the most trustworthy line on the sheet, and
 * worth showing even when it is short.
 */
@Composable
private fun PlayHistorySection(
    history: List<PreviousInstanceRow>,
    timeZone: String,
    language: com.vrcx0.android.data.AppLanguage
) {
    if (history.isEmpty()) return

    val totalMs = history.sumOf { it.time ?: 0L }
    Spacer(Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionTitle("我的游玩记录".tr())
        Spacer(Modifier.width(8.dp))
        Text(
            text = "游玩".tr() + " " + formatDuration(totalMs),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
    Spacer(Modifier.height(6.dp))

    history.take(8).forEach { row ->
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = row.worldName?.takeIf { it.isNotBlank() } ?: "另一个世界".tr(),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                val when_ = fmtDate(row.createdAt, timeZone, language)
                val where = buildString {
                    row.groupName?.takeIf { it.isNotBlank() }?.let { append(it) }
                    if (isNotEmpty() && when_.isNotBlank()) append(" · ")
                    append(when_)
                }
                if (where.isNotBlank()) {
                    Text(
                        text = where,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = formatDuration(row.time ?: 0L),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** ms -> "2 小时 14 分" / "48 分" / "不到 1 分". */
private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "—"
    val minutes = ms / 60_000
    if (minutes < 1) return "不到 1 分"
    val hours = minutes / 60
    val rest = minutes % 60
    return if (hours > 0) "$hours 小时 $rest 分" else "$rest 分"
}

// ---------------------------------------------------------------------------
// shared row
// ---------------------------------------------------------------------------

/**
 * One person: avatar, name, optional caption, tappable through to the profile.
 *
 * 48dp of vertical space rather than 40: the touch target rule is 44x44 and a
 * row that a thumb keeps missing while scrolling is worse than one that shows
 * two fewer names per screen.
 */
@Composable
private fun RosterRow(
    services: SessionServices?,
    userId: String,
    displayName: String?,
    imageUrl: String?,
    caption: String?,
    trailing: String?,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RemoteAvatar(
            services = services,
            rawUrl = imageUrl,
            name = displayName,
            size = 38.dp
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = displayName?.takeIf { it.isNotBlank() } ?: "某人".tr(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!caption.isNullOrBlank()) {
                Text(
                    text = caption,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (!trailing.isNullOrBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // Deliberately no trailing chevron: every row in this sheet is
        // tappable, so an affordance on each one is noise rather than signal.
    }
}

// ---------------------------------------------------------------------------
// small pieces
// ---------------------------------------------------------------------------

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun DetailRow(label: String, value: String?) {
    if (value.isNullOrBlank() || value == "—") return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(84.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * A timestamp for the sheet, which wants a date and not a relative phrase.
 *
 * Reaches into the feed formatter because it already solved the parsing: the
 * server emits an explicit `+00:00` offset, which `Instant.parse` rejects and
 * `OffsetDateTime.parse` accepts. Re-implementing that here is how the two
 * would drift.
 */
@Composable
private fun fmtDate(
    raw: String?,
    timeZone: String,
    language: com.vrcx0.android.data.AppLanguage
): String {
    if (raw.isNullOrBlank()) return ""
    return formatFeedTime(raw, relative = false, zoneId = timeZone, language = language)
}

/** `standalonewindows` -> "PC". Kept here for the summary platform list. */
private fun platformLabelFor(raw: String): String = when (raw.lowercase()) {
    "standalonewindows" -> "PC"
    "android" -> "Android"
    "ios" -> "iOS"
    "" -> ""
    else -> raw
}

/** `releaseStatus` is a Rust enum variant: `"public"`, `"private"`, `"hidden"`. */
@Composable
private fun WorldDetail.releaseStatusLabel(): String = when (releaseStatus?.lowercase()) {
    "public" -> "已发布".tr()
    "private" -> "未发布".tr()
    "hidden" -> "已隐藏".tr()
    null, "" -> "—"
    else -> releaseStatus.orEmpty()
}

/** Instance type from the location suffix: `public`, `friends+`, `group`... */
@Composable
private fun instanceTypeLabel(raw: String): String = when (raw.lowercase()) {
    "public" -> "公开".tr()
    "hidden" -> "仅好友".tr()
    "friends" -> "仅好友".tr()
    "friends+" -> "仅好友+".tr()
    "private" -> "仅邀请".tr()
    "invite" -> "仅邀请".tr()
    "invite+" -> "仅邀请+".tr()
    "group" -> "群组公开".tr()
    "group+" -> "群组+".tr()
    else -> raw
}

/**
 * VRChat's performance ranks, which are the shape of a triangle on the world
 * page and a word in the API.
 */
@Composable
private fun performanceLabel(raw: String): String = when (raw.lowercase()) {
    "excellent" -> "极佳"
    "good" -> "良好"
    "medium" -> "中等"
    "poor" -> "较差"
    "verypoor", "very poor" -> "很差"
    else -> raw
}
