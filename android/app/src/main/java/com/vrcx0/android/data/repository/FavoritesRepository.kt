package com.vrcx0.android.data.repository

import com.vrcx0.android.data.remote.AvatarSummary
import com.vrcx0.android.data.remote.FavoriteGroup
import com.vrcx0.android.data.remote.FavoritesSnapshot
import com.vrcx0.android.data.remote.FriendRecord
import com.vrcx0.android.data.remote.WorldSummary

/** The three kinds VRChat models, plus the two VRCX keeps locally. */
enum class FavoriteKind(val label: String) {
    WORLD("世界"),
    AVATAR("模型"),
    FRIEND("好友"),
    LOCAL_AVATAR("本地模型"),
    LOCAL_FRIEND("本地好友")
}

/**
 * One favourite, with its name already resolved.
 *
 * [entityId] is the `wrld_`/`avtr_`/`usr_` id -- **not** the `fvrt_` favourite
 * id. The two are easy to confuse on the wire; only this one can be looked up,
 * which is why it is the one carried here.
 */
data class FavoriteItem(
    val entityId: String,
    val kind: FavoriteKind,
    val groupKey: String,
    /** Resolved name, or `null` while the lookup is still in flight. */
    val title: String?,
    val subtitle: String = "",
    /** Raw VRChat image URL; must go through the proxy to load. */
    val imageUrl: String = ""
) {
    /** Never show a raw id: fall back to a neutral label, not `wrld_...`. */
    val displayTitle: String
        get() = title?.takeIf { it.isNotBlank() } ?: when (kind) {
            FavoriteKind.WORLD -> "世界"
            FavoriteKind.AVATAR, FavoriteKind.LOCAL_AVATAR -> "模型"
            FavoriteKind.FRIEND, FavoriteKind.LOCAL_FRIEND -> "好友"
        }
}

/** A favourite group and everything filed under it. */
data class FavoriteGroupView(
    val key: String,
    val title: String,
    val count: Long,
    val capacity: Long,
    val items: List<FavoriteItem>
) {
    /** "80 / 100" when the plan declares a capacity, otherwise just the count. */
    val occupancy: String
        get() = if (capacity > 0) "$count / $capacity" else count.toString()
}

/**
 * Assembles one kind's favourites, grouped.
 *
 * Pure on purpose: every input is a plain map, so the whole thing is
 * unit-testable without a server, a coroutine or a Compose runtime. That
 * matters here because the grouping rules are genuinely fiddly -- see below.
 *
 * ## Where the grouping comes from, per kind
 *
 *  - **Worlds** and **friends**: the server ships
 *    `groupedFavoriteWorldIdsByGroupKey` / `groupedFavoriteFriendIdsByGroupKey`
 *    directly, so those are used as-is.
 *  - **Avatars**: there is no such map. The group is recovered from
 *    `remoteFavoritesById[*].$groupKey`, which means the avatar list has to be
 *    folded back out of the favourite entries rather than read from
 *    `favoriteAvatarIds` (that list carries no group information at all).
 *
 * Anything with an id that is in no group is collected under a trailing
 * "未分组" so a favourite can never silently disappear from the screen.
 */
fun buildFavoriteGroups(
    snapshot: FavoritesSnapshot,
    kind: FavoriteKind,
    worldNames: Map<String, WorldSummary> = emptyMap(),
    avatars: Map<String, AvatarSummary> = emptyMap(),
    friends: Map<String, FriendRecord> = emptyMap()
): List<FavoriteGroupView> {
    val (ids, grouped, declaredGroups) = when (kind) {
        FavoriteKind.WORLD ->
            Triple(
                snapshot.favoriteWorldIds,
                snapshot.groupedFavoriteWorldIdsByGroupKey,
                snapshot.favoriteWorldGroups
            )

        FavoriteKind.FRIEND ->
            Triple(
                snapshot.favoriteFriendIds,
                snapshot.groupedFavoriteFriendIdsByGroupKey,
                snapshot.favoriteFriendGroups
            )

        FavoriteKind.LOCAL_AVATAR ->
            Triple(
                snapshot.localAvatarFavoritesList,
                snapshot.localAvatarFavorites,
                snapshot.localAvatarFavoriteGroups.map { localGroup(it, "avatar") }
            )

        FavoriteKind.LOCAL_FRIEND ->
            Triple(
                snapshot.localFriendFavoritesList,
                snapshot.localFriendFavorites,
                snapshot.localFriendFavoriteGroups.map { localGroup(it, "friend") }
            )

        FavoriteKind.AVATAR ->
            Triple(
                snapshot.favoriteAvatarIds,
                avatarGroupsFromFavorites(snapshot),
                snapshot.favoriteAvatarGroups
            )
    }

    val groupsByKey = declaredGroups.associateBy { it.key }

    // Preserve the server's own ordering of groups where it gave one, then
    // append any group it did not mention (avatars recovered from favourites
    // land here).
    val orderedKeys = buildList {
        declaredGroups.forEach { if (it.key.isNotBlank() && it.key !in this) add(it.key) }
        grouped.keys.forEach { if (it !in this) add(it) }
    }

    val claimed = mutableSetOf<String>()
    val views = orderedKeys.mapNotNull { key ->
        val memberIds = grouped[key].orEmpty().filter { it in ids || kind == FavoriteKind.AVATAR }
        if (memberIds.isEmpty()) return@mapNotNull null
        claimed += memberIds

        val declared = groupsByKey[key]
        FavoriteGroupView(
            key = key,
            title = declared?.let { groupTitle(it) } ?: key.substringAfter(':'),
            count = declared?.count?.takeIf { it > 0 } ?: memberIds.size.toLong(),
            capacity = declared?.capacity ?: 0,
            items = memberIds.map { item(it, kind, key, worldNames, avatars, friends) }
        )
    }

    // A favourite the server filed under a group key it never declared would
    // otherwise vanish; the id set is the source of truth, not the grouping.
    val leftovers = ids.filter { it !in claimed }
    val withLeftovers = if (leftovers.isEmpty()) {
        views
    } else {
        views + FavoriteGroupView(
            key = "ungrouped",
            title = "未分组",
            count = leftovers.size.toLong(),
            capacity = 0,
            items = leftovers.map { item(it, kind, "ungrouped", worldNames, avatars, friends) }
        )
    }

    return withLeftovers
}

/**
 * Recovers avatar -> group from the favourite entries.
 *
 * `remoteFavoritesById` values carry `$groupKey` (`"avatar:avatars2"`) and
 * `favoriteId` (the `avtr_` id), which is the only place the two are related.
 */
private fun avatarGroupsFromFavorites(snapshot: FavoritesSnapshot): Map<String, List<String>> =
    snapshot.remoteFavoritesById.values
        .filter { it.type.equals("avatar", ignoreCase = true) }
        .filter { it.favoriteId.isNotBlank() && it.groupKey.isNotBlank() }
        .groupBy({ it.groupKey }, { it.favoriteId })

/** VRCX's local groups are plain name strings, with no key or capacity. */
private fun localGroup(name: String, type: String): FavoriteGroup =
    FavoriteGroup(key = "$type:local:$name", displayName = name, name = name, type = type)

private fun groupTitle(group: FavoriteGroup): String =
    friendlyGroupName(group.type, group.displayName.ifBlank { group.name }.ifBlank { group.key.substringAfter(':') })

/**
 * VRChat's own default group names are implementation labels, not something a
 * reader wants to see (`group_0`, `worlds1`, `vrcPlusWorlds1`). The well-known
 * ones get a plain Chinese label; anything else -- a group the account named
 * itself -- is shown as-is.
 */
private fun friendlyGroupName(type: String, raw: String): String = when {
    type == "friend" && GROUP_PAT.matches(raw) -> "好友"
    type == "world" && GROUP_PAT.matches(raw) -> raw.replaceFirst("worlds", "世界组 ")
    type == "vrcPlusWorld" && GROUP_PAT.matches(raw) -> raw.replaceFirst("vrcPlusWorlds", "VRC+ 世界组 ")
    type == "avatar" && GROUP_PAT.matches(raw) -> raw.replaceFirst("avatars", "模型组 ")
    else -> raw
}

/** `group_0`, `worlds1`, `avatars2`, `vrcPlusWorlds1` -- the stock names. */
private val GROUP_PAT = Regex("^(group|worlds|avatars|vrcPlusWorlds)_?\\d+$")

/**
 * Resolves one favourite's display fields.
 *
 * Every resolver here can miss -- the name lookup may still be running, or the
 * entity may no longer be visible to the account -- and in that case the item
 * keeps a neutral title and an empty image rather than showing an id.
 */
private fun item(
    entityId: String,
    kind: FavoriteKind,
    groupKey: String,
    worldNames: Map<String, WorldSummary>,
    avatars: Map<String, AvatarSummary>,
    friends: Map<String, FriendRecord>
): FavoriteItem {
    val world = worldNames[entityId]
    val avatar = avatars[entityId]
    val friend = friends[entityId]

    return when (kind) {
        FavoriteKind.WORLD -> FavoriteItem(
            entityId = entityId,
            kind = kind,
            groupKey = groupKey,
            title = world?.name,
            subtitle = world?.authorName.orEmpty(),
            imageUrl = world?.thumbnailImageUrl.orEmpty()
        )

        FavoriteKind.AVATAR, FavoriteKind.LOCAL_AVATAR -> FavoriteItem(
            entityId = entityId,
            kind = kind,
            groupKey = groupKey,
            title = avatar?.name,
            subtitle = avatar?.authorName.orEmpty(),
            imageUrl = (avatar?.thumbnailImageUrl ?: avatar?.imageUrl).orEmpty()
        )

        FavoriteKind.FRIEND, FavoriteKind.LOCAL_FRIEND -> FavoriteItem(
            entityId = entityId,
            kind = kind,
            groupKey = groupKey,
            title = friend?.displayName,
            subtitle = friend?.statusDescription.orEmpty(),
            imageUrl = friend?.iconUrl.orEmpty()
        )
    }
}
