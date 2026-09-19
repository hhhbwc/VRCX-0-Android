package com.vrcx0.android.data.repository

import com.vrcx0.android.data.remote.FeedRowOutput
import com.vrcx0.android.data.remote.FriendLogRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure aggregation in StatsRepository. The numbers are simple counts
 * over already-loaded rows, but "which type string maps to which bucket" is
 * exactly where a silent undercount lives.
 */
class StatsRepositoryTest {

    @Test
    fun feed_stats_count_each_type() {
        val rows = listOf(
            FeedRowOutput(type = "online"),
            FeedRowOutput(type = "online"),
            FeedRowOutput(type = "offline"),
            FeedRowOutput(type = "GPS"),
            FeedRowOutput(type = "status"),
            FeedRowOutput(type = "avatar"),
            FeedRowOutput(type = "bio")
        )
        val stats = aggregateFeed(rows)
        assertEquals(7, stats.total)
        assertEquals(2, stats.online)
        assertEquals(1, stats.offline)
        assertEquals(1, stats.location)
        assertEquals(1, stats.status)
        assertEquals(1, stats.avatar)
        assertEquals(1, stats.bio)
    }

    @Test
    fun friend_log_stats_count_kinds() {
        val rows = listOf(
            FriendLogRow(type = "Friend"),
            FriendLogRow(type = "Friend"),
            FriendLogRow(type = "Unfriend"),
            FriendLogRow(type = "DisplayName"),
            FriendLogRow(type = "TrustLevel")
        )
        val stats = aggregateFriendLog(rows)
        assertEquals(2, stats.added)
        assertEquals(1, stats.removed)
        assertEquals(1, stats.renamed)
        assertEquals(1, stats.trustChanged)
    }

    @Test
    fun browse_stats_only_count_worlds() {
        val rows = listOf(
            BrowseHistoryItem(entityKind = "world", title = "A", viewCount = 3),
            BrowseHistoryItem(entityKind = "world", title = "B", viewCount = 1),
            BrowseHistoryItem(entityKind = "user", title = "X", viewCount = 9)
        )
        val stats = aggregateBrowse(rows)
        assertEquals(2, stats.distinctWorlds)
        assertEquals(4L, stats.totalViews)
        assertEquals("A", stats.topWorld)
    }

    @Test
    fun empty_rows_yield_empty_stats() {
        assertTrue(aggregateFeed(emptyList()).isEmpty)
        assertTrue(aggregateFriendLog(emptyList()).isEmpty)
        assertTrue(aggregateBrowse(emptyList()).isEmpty)
    }
}
