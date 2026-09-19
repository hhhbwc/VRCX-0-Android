package com.vrcx0.android.data.repository

import com.vrcx0.android.data.remote.AvatarSummary
import com.vrcx0.android.data.remote.FavoriteGroup
import com.vrcx0.android.data.remote.FavoritesSnapshot
import com.vrcx0.android.data.remote.FriendRecord
import com.vrcx0.android.data.remote.GroupSummary
import com.vrcx0.android.data.remote.RemoteFavorite
import com.vrcx0.android.data.remote.WorldSummary
import com.vrcx0.android.data.remote.worldIdFromLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the three new tabs' logic against captured wire shapes.
 *
 * The fixtures below mirror what a live server returned for this account
 * (183 favourites across worlds/avatars/friends, 42 groups, friends spread
 * across instances). The point of testing the *pure* builders is that the
 * grouping rules are where silent bugs live -- a favourite silently vanishing
 * from the grid, or two friends in different instances of the same world being
 * wrongly merged -- and none of those throw an exception.
 */
class HomeAndFavoritesTest {

    // ------------------------------------------------------------ favourites

    @Test
    fun avatar_groups_are_recovered_from_remote_favorites() {
        // `favoriteAvatarIds` carries no group; only `remoteFavoritesById`
        // relates an avatar to its `$groupKey`. This pins that the builder
        // follows the right path.
        val snapshot = FavoritesSnapshot(
            favoriteAvatarIds = listOf("avtr_1", "avtr_2"),
            remoteFavoritesById = mapOf(
                "fvrt_1" to RemoteFavorite(
                    id = "fvrt_1",
                    favoriteId = "avtr_1",
                    type = "avatar",
                    groupKey = "avatar:avatars2"
                ),
                "fvrt_2" to RemoteFavorite(
                    id = "fvrt_2",
                    favoriteId = "avtr_2",
                    type = "avatar",
                    groupKey = "avatar:avatars2"
                )
            ),
            favoriteAvatarGroups = listOf(
                FavoriteGroup(
                    key = "avatar:avatars2",
                    displayName = "avatars2",
                    name = "avatars2",
                    type = "avatar",
                    count = 2,
                    capacity = 50
                )
            )
        )

        val groups = buildFavoriteGroups(
            snapshot,
            FavoriteKind.AVATAR,
            avatars = mapOf(
                "avtr_1" to AvatarSummary(id = "avtr_1", name = "模型A"),
                "avtr_2" to AvatarSummary(id = "avtr_2", name = "模型B")
            )
        )

        assertEquals(1, groups.size)
        // Stock VRChat group names (`avatars2`) read as implementation labels,
        // so the builder rewrites them into plain Chinese labels.
        assertEquals("模型组 2", groups[0].title)
        assertEquals(2, groups[0].items.size)
        assertEquals("模型A", groups[0].items[0].displayTitle)
    }

    @Test
    fun a_custom_group_name_is_shown_as_is() {
        // A group the account named itself must not be rewritten.
        val snapshot = FavoritesSnapshot(
            favoriteWorldIds = listOf("wrld_1"),
            groupedFavoriteWorldIdsByGroupKey = mapOf("world:我的菜" to listOf("wrld_1")),
            favoriteWorldGroups = listOf(
                FavoriteGroup(
                    key = "world:我的菜",
                    displayName = "我的菜",
                    name = "我的菜",
                    type = "world",
                    count = 1,
                    capacity = 100
                )
            ),
            remoteFavoritesById = mapOf(
                "fvrt_1" to RemoteFavorite(
                    id = "fvrt_1",
                    favoriteId = "wrld_1",
                    type = "world",
                    groupKey = "world:我的菜"
                )
            )
        )

        val groups = buildFavoriteGroups(snapshot, FavoriteKind.WORLD)
        assertEquals("我的菜", groups.single().title)
    }

    @Test
    fun a_favourite_without_a_resolved_name_shows_a_label_not_an_id() {
        val snapshot = FavoritesSnapshot(
            favoriteWorldIds = listOf("wrld_1"),
            groupedFavoriteWorldIdsByGroupKey = mapOf("world:worlds1" to listOf("wrld_1")),
            favoriteWorldGroups = listOf(
                FavoriteGroup(key = "world:worlds1", displayName = "worlds1", type = "world")
            )
        )

        // No world name resolved yet -- the item must fall back to "世界",
        // never to `wrld_1`.
        val groups = buildFavoriteGroups(snapshot, FavoriteKind.WORLD, worldNames = emptyMap())
        assertEquals("世界", groups[0].items[0].displayTitle)
    }

    @Test
    fun an_orphan_favourite_is_kept_under_ungrouped() {
        // The id set is the source of truth: a favourite the grouping map does
        // not mention must not disappear.
        val snapshot = FavoritesSnapshot(
            favoriteFriendIds = listOf("usr_1"),
            groupedFavoriteFriendIdsByGroupKey = emptyMap(),
            favoriteFriendGroups = emptyList()
        )

        val groups = buildFavoriteGroups(snapshot, FavoriteKind.FRIEND, friends = emptyMap())
        assertEquals(1, groups.size)
        assertEquals("未分组", groups[0].title)
        assertEquals(1, groups[0].items.size)
    }

    // ------------------------------------------------------------ world gatherings

    @Test
    fun online_friends_are_grouped_by_instance_not_world() {
        // Two friends in *different instances* of the same world are not in the
        // same place. The location string distinguishes them, so the builder
        // must key on the full location, not the world id.
        val friends = listOf(
            FriendRecord(id = "u1", displayName = "A", location = "wrld_x:11111~region(jp)"),
            FriendRecord(id = "u2", displayName = "B", location = "wrld_x:22222~region(jp)"),
            FriendRecord(id = "u3", displayName = "C", location = "offline"),
            FriendRecord(id = "u4", displayName = "D", location = "private")
        )

        val gatherings = buildWorldGatherings(friends, emptyMap())
        // offline and private are states, not places -- so two gatherings only.
        assertEquals(2, gatherings.size)
        // Each gathering holds exactly one friend, because the instances differ.
        assertTrue(gatherings.all { it.count == 1 })
    }

    @Test
    fun gatherings_are_sorted_busiest_first() {
        val friends = listOf(
            FriendRecord(id = "a", displayName = "A", location = "wrld_busy:1"),
            FriendRecord(id = "b", displayName = "B", location = "wrld_busy:1"),
            FriendRecord(id = "c", displayName = "C", location = "wrld_busy:1"),
            FriendRecord(id = "d", displayName = "D", location = "wrld_quiet:1")
        )

        val gatherings = buildWorldGatherings(friends, emptyMap())
        assertEquals(2, gatherings.size)
        assertEquals(3, gatherings[0].count)
        assertEquals(1, gatherings[1].count)
    }

    @Test
    fun a_gathering_with_no_name_shows_a_label() {
        val gatherings = buildWorldGatherings(
            listOf(FriendRecord(id = "a", displayName = "A", location = "wrld_x:1")),
            emptyMap()
        )
        assertEquals("世界", gatherings[0].displayName)
    }

    // ------------------------------------------------------------ my groups

    @Test
    fun an_unhydrated_group_never_claims_zero_members() {
        val groups = buildMyGroups(
            listOf("grp_1"),
            emptyMap() // name lookup still in flight
        )
        assertEquals(1, groups.size)
        // "0 成员" would be a lie -- the count is simply not known yet.
        assertEquals("加载中…", groups[0].memberCaption)
        assertEquals("群组", groups[0].displayName)
    }

    @Test
    fun a_hydrated_group_shows_counts() {
        val groups = buildMyGroups(
            listOf("grp_1"),
            mapOf(
                "grp_1" to GroupSummary(
                    id = "grp_1",
                    name = "中文吧",
                    memberCount = 100,
                    onlineMemberCount = 12
                )
            )
        )
        assertEquals("中文吧", groups[0].displayName)
        assertEquals("100 成员 · 12 在线", groups[0].memberCaption)
    }
}
