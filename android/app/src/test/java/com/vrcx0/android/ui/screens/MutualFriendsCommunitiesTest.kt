package com.vrcx0.android.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Louvain port to the behaviours that matter on the graph screen:
 * real clusters get separated, singletons stay isolated, and the result is
 * deterministic (the graph must not rearrange between recompositions).
 */
class MutualFriendsCommunitiesTest {

    @Test
    fun two_connected_cliques_are_split_into_two_communities() {
        // clique A: a1-a2-a3; clique B: b1-b2-b3; one bridge a3-b1.
        val nodes = listOf("a1", "a2", "a3", "b1", "b2", "b3")
        val edges = listOf(
            "a1" to "a2", "a2" to "a3", "a1" to "a3",
            "b1" to "b2", "b2" to "b3", "b1" to "b3",
            "a3" to "b1"
        )
        val degree = mutableMapOf<String, Int>()
        for ((a, b) in edges) { degree[a] = (degree[a] ?: 0) + 1; degree[b] = (degree[b] ?: 0) + 1 }

        val assignment = MutualFriendsCommunities.assign(nodes, degree, edges)

        assertEquals(2, assignment.communities.size)
        val byId = assignment.communityIndexById
        // The two cliques must not be lumped together.
        assertTrue(byId.getValue("a1") == byId.getValue("a3"))
        assertTrue(byId.getValue("b1") == byId.getValue("b3"))
        assertTrue(byId.getValue("a1") != byId.getValue("b1"))
    }

    @Test
    fun isolated_nodes_do_not_merge_into_any_community() {
        val nodes = listOf("a", "b", "c", "solo")
        val edges = listOf("a" to "b", "b" to "c")
        val degree = mapOf("a" to 1, "b" to 2, "c" to 1, "solo" to 0)

        val assignment = MutualFriendsCommunities.assign(nodes, degree, edges)
        val byId = assignment.communityIndexById

        val connected = byId.getValue("a")
        assertEquals(connected, byId.getValue("b"))
        assertEquals(connected, byId.getValue("c"))
        // The degree-0 node must sit in its own community.
        assertTrue(byId.getValue("solo") != connected)
    }

    @Test
    fun assignment_is_deterministic_across_runs() {
        val nodes = (1..40).map { "n$it" }
        val edges = mutableListOf<Pair<String, String>>()
        for (i in 1..20) for (j in 21..40) {
            if ((i + j) % 3 == 0) edges.add("n$i" to "n$j")
        }
        val degree = mutableMapOf<String, Int>()
        for ((a, b) in edges) { degree[a] = (degree[a] ?: 0) + 1; degree[b] = (degree[b] ?: 0) + 1 }

        val first = MutualFriendsCommunities.assign(nodes, degree, edges)
        val second = MutualFriendsCommunities.assign(nodes, degree, edges)

        assertEquals(first.communityIndexById, second.communityIndexById)
        assertEquals(first.communities.map { it.anchorId }, second.communities.map { it.anchorId })
    }

    @Test
    fun anchor_is_the_highest_degree_member() {
        val nodes = listOf("hub", "leaf1", "leaf2")
        val edges = listOf("hub" to "leaf1", "hub" to "leaf2")
        val degree = mapOf("hub" to 2, "leaf1" to 1, "leaf2" to 1)

        val assignment = MutualFriendsCommunities.assign(nodes, degree, edges)

        assertEquals(1, assignment.communities.size)
        assertEquals("hub", assignment.communities.single().anchorId)
    }
}
