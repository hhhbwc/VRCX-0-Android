package com.vrcx0.android.ui.screens

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The force-directed relaxation is what turned the mutual graph from a ring of
 * overlapping labels into clusters, so its two load-bearing properties are
 * pinned here: connected nodes end up closer than unconnected ones, and two
 * disconnected groups drift apart instead of settling on top of each other.
 */
class ForceLayoutTest {

    @Test
    fun connected_nodes_end_up_closer_than_unconnected_ones() {
        // a-b-c chain, plus an unrelated island d.
        val ids = listOf("a", "b", "c", "d")
        val initial = mapOf(
            "a" to Offset(100f, 100f),
            "b" to Offset(900f, 100f),
            "c" to Offset(500f, 900f),
            "d" to Offset(500f, 500f)
        )
        val out = forceRelax(
            ids = ids,
            initial = initial,
            edges = listOf("a" to "b", "b" to "c")
        )

        fun dist(p1: Offset, p2: Offset): Float {
            val dx = p1.x - p2.x
            val dy = p1.y - p2.y
            return sqrt(dx * dx + dy * dy)
        }

        val ab = dist(out.getValue("a"), out.getValue("b"))
        val bc = dist(out.getValue("b"), out.getValue("c"))
        val ad = dist(out.getValue("a"), out.getValue("d"))

        // Chain neighbours are pulled toward the ideal edge length; the island
        // is pushed away from the connected cluster.
        assertTrue("ab=$ab should be well under ad=$ad", ab < ad)
        assertTrue("bc=$bc should be well under ad=$ad", bc < ad)
    }

    @Test
    fun two_disconnected_groups_separate() {
        // Group 1 fully connected, group 2 fully connected, no cross edges.
        val ids = (1..8).map { "g1-$it" } + (1..8).map { "g2-$it" }
        val initial = ids.associateWith {
            Offset((it.hashCode() % 400 + 300).toFloat(), (it.hashCode() % 300 + 350).toFloat())
        }
        val edges = buildList {
            for (i in 1..8) for (j in i + 1..8) {
                add("g1-$i" to "g1-$j")
                add("g2-$i" to "g2-$j")
            }
        }
        val out = forceRelax(ids, initial, edges, iterations = 200)

        fun centroid(prefix: String): Offset {
            val group = out.filterKeys { it.startsWith(prefix) }.values
            val x = group.sumOf { it.x.toDouble() } / group.size
            val y = group.sumOf { it.y.toDouble() } / group.size
            return Offset(x.toFloat(), y.toFloat())
        }

        val dx = abs(centroid("g1-").x - centroid("g2-").x)
        val dy = abs(centroid("g1-").y - centroid("g2-").y)
        // Separation on at least one axis; the exact vector depends on the
        // deterministic hash nudge, so assert on the distance instead.
        val separation = sqrt(dx * dx + dy * dy)
        assertTrue("groups should separate, got $separation", separation > 60f)
    }

    @Test
    fun the_layout_is_deterministic() {
        val ids = listOf("a", "b", "c")
        val initial = mapOf(
            "a" to Offset(100f, 100f),
            "b" to Offset(800f, 200f),
            "c" to Offset(500f, 800f)
        )
        val edges = listOf("a" to "b", "b" to "c")
        val one = forceRelax(ids, initial, edges)
        val two = forceRelax(ids, initial, edges)
        // Determinism is what keeps the graph from re-shuffling on recompute.
        assertTrue(one == two)
    }
}
