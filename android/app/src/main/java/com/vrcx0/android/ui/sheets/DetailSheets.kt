package com.vrcx0.android.ui.sheets

import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import com.vrcx0.android.data.remote.trustRankLabel
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalClipboardManager
import com.vrcx0.android.ui.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.vrcx0.android.data.SessionServices
import com.vrcx0.android.data.remote.FeedRowOutput
import com.vrcx0.android.data.remote.FriendRecord
import com.vrcx0.android.data.remote.instanceAccessLabel
import com.vrcx0.android.data.remote.worldIdFromLocation
import com.vrcx0.android.ui.screens.WorldTarget
import com.vrcx0.android.data.repository.ImageProxyRepository
import com.vrcx0.android.data.repository.UserCard
import com.vrcx0.android.ui.components.RemoteAvatar
import com.vrcx0.android.ui.components.rememberRosterState
import com.vrcx0.android.ui.components.rememberWorldNames

/**
 * A person's profile, opened by tapping them in the friends list or from a feed
 * entry's "查看资料".
 *
 * [friend] is passed through when the caller already holds the roster record.
 * It is not redundant with the fetched card: the record carries live presence
 * (state/platform/current world) that the profile endpoints do not return,
 * so passing it is what makes "在线 · PC · 在 xxx 世界" possible here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserProfileSheet(
    services: SessionServices?,
    userId: String,
    friend: FriendRecord? = null,
    onDismiss: () -> Unit,
    onOpenWorld: (WorldTarget) -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var card by remember(userId) { mutableStateOf<UserCard?>(null) }
    var error by remember(userId) { mutableStateOf<String?>(null) }
    var loading by remember(userId) { mutableStateOf(true) }

    val notConnectedText = "尚未连接服务器".tr()
    val loadFailedText = "加载资料失败".tr()
    LaunchedEffect(userId, services) {
        val svc = services
        if (svc == null) {
            error = notConnectedText
            loading = false
            return@LaunchedEffect
        }
        loading = true
        runCatching { svc.profiles.load(userId, friend) }
            .onSuccess {
                card = it
                error = null
                // Also resolve the avatar name and world name this card shows.
                if (it.worldId.isNotBlank()) svc.worlds.ensure(listOf(it.worldId))
            }
            .onFailure { error = it.message ?: loadFailedText }
        loading = false
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        when {
            loading && card == null -> Box(
                Modifier.fillMaxWidth().padding(32.dp),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }

            error != null && card == null -> Box(
                Modifier.fillMaxWidth().padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    error ?: "加载失败".tr(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            else -> ProfileBody(services, card, onOpenWorld)
        }
    }
}

@Composable
private fun ProfileBody(
    services: SessionServices?,
    card: UserCard?,
    onOpenWorld: (WorldTarget) -> Unit
) {
    if (card == null) return
    val worldNames = rememberWorldNames(services)
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val booped = remember(card.userId) { mutableStateOf(false) }
    var boopPicker by remember(card.userId) { mutableStateOf(false) }
    var selectedEmoji by remember(card.userId) { mutableStateOf<String?>(null) }
    val requestedInvite = remember(card.userId) { mutableStateOf(false) }
    val invited = remember(card.userId) { mutableStateOf(false) }
    val inviteDisabled = remember(card.userId) { mutableStateOf(false) }

    val worldName = card.worldId.takeIf { it.isNotBlank() }?.let { id -> worldNames[id]?.name }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RemoteAvatar(
                services = services,
                rawUrl = card.iconUrl,
                name = card.displayName,
                size = 64.dp,
                thumbnailSize = ImageProxyRepository.DETAIL_SIZE
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = card.displayName.ifBlank { card.userId },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val presence = presenceLine(card)
                if (presence.isNotBlank()) {
                    Text(
                        presence,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                card.pronouns.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (card.trustTags.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                trustChips(card.trustTags).forEach { label ->
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
        }

        // 戳一戳 -- opens an emoji picker; VRChat's boop accepts an emojiId
        // (built-in set: `default_<name>`) or a plain no-emoji boop. Only for
        // friends: VRChat rejects boops to strangers.
        if (card.isFriend) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { boopPicker = true },
                    enabled = !booped.value,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (booped.value) "已戳一戳".tr() else "戳一戳".tr())
                }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            if (services?.profiles?.requestInvite(card.userId) == true) {
                                requestedInvite.value = true
                            }
                        }
                    },
                    enabled = !requestedInvite.value,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (requestedInvite.value) "已请求邀请".tr() else "请求邀请".tr())
                }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val meNow = services?.snapshot?.me?.value
                            val myWorld = meNow?.presence?.world.orEmpty()
                            val myInstance = meNow?.presence?.instance.orEmpty()
                            if (myWorld.startsWith("wrld_")) {
                                val worldName =
                                    services?.worlds?.summaries?.value?.get(myWorld)?.name.orEmpty()
                                if (services?.profiles?.inviteToInstance(
                                        card.userId, myWorld, myInstance, worldName
                                    ) == true
                                ) {
                                    invited.value = true
                                }
                            } else {
                                inviteDisabled.value = true
                            }
                        }
                    },
                    enabled = !invited.value,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (invited.value) "已邀请".tr() else "邀请加入".tr())
                }
            }
        }

        card.statusDescription.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(14.dp))
            SectionTitle("状态".tr())
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }

        if (worldName != null || card.worldId.isNotBlank()) {
            Spacer(Modifier.height(14.dp))
            SectionTitle("所在世界".tr())
            Text(
                text = worldName ?: "另一个世界".tr(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = card.worldId.isNotBlank()) {
                        onOpenWorld(WorldTarget(card.worldId, card.location))
                    }
                    .padding(vertical = 2.dp)
            )
        }

        card.bio.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(14.dp))
            SectionTitle("简介".tr())
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(14.dp))
        HorizontalDivider()
        Spacer(Modifier.height(10.dp))

        InfoRow("平台".tr(), platformLabel(card.platform))
        if (card.languages.isNotEmpty()) InfoRow("使用的语言".tr(), card.languages.joinToString(", "))
        card.allowAvatarCopying?.let {
            InfoRow("允许克隆模型".tr(), if (it) "是".tr() else "否".tr())
        }
        card.representedGroup?.takeIf { it.isNotBlank() }?.let {
            InfoRow("展示的群组".tr(), if (it.startsWith("grp_")) "群组实例".tr() else it)
        }
        card.dateJoined?.takeIf { it.isNotBlank() }?.let { InfoRow("加入时间".tr(), it.take(10)) }
        card.lastLogin?.takeIf { it.isNotBlank() }?.let { InfoRow("最近登录".tr(), shortTime(it)) }
        card.lastActivity?.takeIf { it.isNotBlank() }?.let { InfoRow("最近活动".tr(), shortTime(it)) }
        card.currentAvatarName.takeIf { it.isNotBlank() }?.let { InfoRow("当前模型".tr(), it) }
        InfoRow("关系".tr(), if (card.isFriend) "好友".tr() else "非好友".tr())

        // Identity block: the id and the public profile link, both one-tap
        // copyable. The link is just the profile page URL -- derived, not
        // fetched, so it costs nothing and always works.
        CopyRow("用户 ID".tr(), card.userId, clipboard)
        if (card.userId.startsWith("usr_")) {
            CopyRow("VRChat 链接".tr(), "https://vrchat.com/home/user/${card.userId}", clipboard)
        }

        if (inviteDisabled.value) {
            Spacer(Modifier.height(8.dp))
            Text(
                "你需要在一个房间里才能邀请别人".tr(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (boopPicker) {
            AlertDialog(
                onDismissRequest = { boopPicker = false },
                title = { Text("戳一戳".tr()) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "选一个表情（不选就是普通戳一戳）".tr(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        com.vrcx0.android.ui.components.BoopEmojiGrid(
                            selectedId = selectedEmoji,
                            onSelect = { selectedEmoji = it.id }
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            boopPicker = false
                            scope.launch {
                                if (services?.profiles?.sendBoop(card.userId, selectedEmoji.orEmpty()) == true) {
                                    booped.value = true
                                }
                            }
                        }
                    ) { Text("发送".tr()) }
                },
                dismissButton = {
                    TextButton(onClick = { boopPicker = false }) { Text("取消".tr()) }
                }
            )
        }

        if (card.badges.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            SectionTitle("徽章 · ${card.badges.size}")
            Spacer(Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                card.badges.take(4).forEach { badge ->
                    // Display-only: a badge has no detail to open, so this must
                    // NOT be an AssistChip -- an interactive chip with an empty
                    // onClick reads as a dead button.
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            badge.badgeName.ifBlank { "徽章".tr() },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- feed detail

/**
 * The "second level" for a feed entry.
 *
 * The list only has room for one line of body text, so a GPS change ("moved to
 * <world>") cannot also show which instance, whether it is a group instance,
 * or what it moved *from*. That detail belongs here rather than being crammed
 * into the row -- and it is the reason the row is clickable at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedDetailSheet(
    services: SessionServices?,
    row: FeedRowOutput,
    onOpenUser: ((userId: String) -> Unit)?,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val roster = rememberRosterState(services)
    val worldNames = rememberWorldNames(services)
    val worldId = worldIdFromLocation(row.location.orEmpty())
    val groupId = row.groupName.orEmpty()

    LaunchedEffect(worldId, groupId) {
        if (worldId.isNotBlank()) services?.worlds?.ensure(listOf(worldId))
    }

    // The row's own `worldName` wins over the lookup: it is free, always
    // present on a location row, and correct even when the summary call has not
    // landed yet. Only a row without one (or a `previousLocation`, which never
    // has one) needs the id resolved.
    val worldName = row.worldName?.takeIf { it.isNotBlank() } ?: worldNames[worldId]?.name
    // The feed row itself carries no avatar URL, so the face comes from the
    // roster the rest of the app has already loaded -- a free lookup rather
    // than another request.
    val authorIcon = roster?.friends?.get(row.userId.orEmpty())?.iconUrl

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RemoteAvatar(
                    services = services,
                    rawUrl = authorIcon,
                    name = row.displayName,
                    size = 48.dp,
                    thumbnailSize = ImageProxyRepository.DETAIL_SIZE
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = row.displayName ?: row.userId ?: "某人".tr(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        feedTypeLabel(row.type),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(10.dp))

            when (row.type?.lowercase()) {
                "location", "gps" -> {
                    if (worldId.isNotBlank()) {
                        InfoRow("世界".tr(), worldName ?: "另一个世界".tr())
                        InfoRow("实例类型".tr(), instanceAccessLabel(row.location.orEmpty()))
                    }
                    groupId.takeIf { it.isNotBlank() }?.let { InfoRow("群组".tr(), groupDisplayName(it)) }
                    row.previousLocation?.takeIf { it.isNotBlank() }?.let { prev ->
                        // Built from named parts only. The raw string is
                        // `wrld_...:19925~hidden(usr_...~)~region(jp)`, and
                        // printing it verbatim -- which is what this did -- put
                        // a world id and a stranger's user id on screen.
                        val prevId = worldIdFromLocation(prev)
                        val prevName = when {
                            prevId.isBlank() -> null
                            // Same world, other instance: reuse the name, no lookup.
                            prevId == worldId -> worldName
                            else -> worldNames[prevId]?.name
                        }
                        val parts = listOfNotNull(
                            prevName ?: describeNonWorldLocation(prev, hasWorldId = prevId.isNotBlank()),
                            instanceAccessLabel(prev).takeIf { it.isNotBlank() }
                        )
                        if (parts.isNotEmpty()) InfoRow("来自".tr(), parts.joinToString(" · "))
                    }
                }

                else -> Unit
            }

            row.status?.takeIf { it.isNotBlank() }?.let { InfoRow("状态".tr(), statusLabel(it)) }
            row.statusDescription?.takeIf { it.isNotBlank() }?.let { InfoRow("状态文字".tr(), it) }
            row.previousStatus?.takeIf { it.isNotBlank() }?.let { InfoRow("原状态".tr(), statusLabel(it)) }
            row.bio?.takeIf { it.isNotBlank() }?.let { InfoRow("简介".tr(), it) }
            row.previousBio?.takeIf { it.isNotBlank() }?.let { InfoRow("原简介".tr(), it) }
            row.avatarName?.takeIf { it.isNotBlank() }?.let { InfoRow("模型".tr(), it) }

            row.createdAt?.takeIf { it.isNotBlank() }?.let { InfoRow("时间".tr(), shortTime(it)) }

            val userId = row.userId.orEmpty()
            if (userId.isNotBlank() && onOpenUser != null) {
                Spacer(Modifier.height(18.dp))
                AssistChip(
                    onClick = { onOpenUser(userId) },
                    label = { Text("查看用户资料".tr()) }
                )
            }
        }
    }
}

// ---------------------------------------------------------------- shared bits

/**
 * A value that copies itself on tap.
 *
 * Ids and links are useless retyped by hand from a phone; the row is still
 * rendered as plain text so it can be long-pressed by the system selection
 * too. A tap confirms with the label swap rather than a toast -- one fewer
 * dependency, and the feedback is where the finger already is.
 */
@Composable
private fun CopyRow(label: String, value: String, clipboard: ClipboardManager) {
    var copied by remember(value) { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable {
                clipboard.setText(AnnotatedString(value))
                copied = true
            }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = if (copied) "已复制".tr() else "复制".tr(),
            style = MaterialTheme.typography.labelSmall,
            color = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 2.dp)
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    if (value.isBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(76.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun presenceLine(card: UserCard): String {
    val parts = buildList {
        when (card.state.lowercase()) {
            "online" -> add("在线".tr())
            "active" -> add("活跃".tr())
            "offline" -> add("离线".tr())
        }
        platformLabel(card.platform).takeIf { it.isNotBlank() && it != "—" }?.let(::add)
        card.status.takeIf { it.isNotBlank() }?.let { add(statusLabel(it)) }
    }
    return parts.joinToString(" · ")
}

@Composable
private fun platformLabel(p: String): String = when (p.lowercase()) {
    "standalonewindows" -> "PC"
    "android" -> "Android"
    "ios" -> "iOS"
    "web" -> "Web"
    "" -> "—"
    else -> p
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

/** Case-insensitive: the server sends Rust enum variants (`"GPS"`, `"Online"`). */
@Composable
private fun feedTypeLabel(type: String?): String = when (type?.lowercase()) {
    "location", "gps" -> "位置变化".tr()
    "status" -> "状态变化".tr()
    "bio" -> "简介变化".tr()
    "avatar" -> "模型变化".tr()
    "online" -> "上线".tr()
    "offline" -> "下线".tr()
    else -> type ?: "动态".tr()
}

/**
 * A phrase for a location that is not a world.
 *
 * The last resort is a generic phrase rather than the input: `previousLocation`
 * can be `wrld_<uuid>:19925~hidden(usr_<uuid>~)~region(jp)`, and a fallback that
 * returns its argument puts both a world id and an unrelated user id on screen.
 * Saying less is strictly better than saying that.
 */
@Composable
private fun describeNonWorldLocation(location: String, hasWorldId: Boolean): String {
    if (hasWorldId) return "另一个世界".tr()
    return when (location.lowercase()) {
        "private" -> "私密房间".tr()
        "offline" -> "离线".tr()
        "traveling" -> "传送中".tr()
        else -> "未知位置".tr()
    }
}

/**
 * A group id is not a name.
 *
 * Without a resolved name the row is better off saying nothing than printing
 * `grp_69408060-1cb5-44ec-854d-351b311ece11`.
 */
@Composable
private fun groupDisplayName(groupId: String): String =
    if (groupId.startsWith("grp_")) "群组实例".tr() else groupId

/** Trust tags are `system_trust_*`; only the meaningful half is worth a chip. */
@Composable
private fun trustChips(tags: List<String>): List<String> = buildList {
    val order = listOf(
        "system_trust_legendary",
        "system_trust_veteran",
        "system_trust_trusted",
        "system_trust_known",
        "system_trust_basic",
        "system_trust_user",
        "system_trust_visitor",
        "system_trust_nuisance"
    )
    val joined = tags.joinToString(" ").lowercase()
    order.firstOrNull { joined.contains(it.removePrefix("system_")) }?.let {
        add(trustRankLabel(it).tr())
    }
}

private fun shortTime(raw: String): String =
    raw.replace('T', ' ').removeSuffix("Z").take(16)
