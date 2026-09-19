package com.vrcx0.android.ui.screens

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The node radius is measured in screen pixels while the label beside it is
 * measured in sp, and getting that relationship wrong is exactly what the user
 * reported: *"一个光点都比得上一个人名大了，看着特别特别拥挤"*.
 *
 * The old model lived in world units and was multiplied by the zoom when drawn,
 * so at the default zoom of 1 a friend was drawn 6-14 px across next to an
 * ~29 px label -- and a *mutual* node got a flat 4f, indistinguishable from a
 * barely-connected friend. The graph was a field of specks that all looked
 * alike, which reads as dense no matter how far apart the nodes actually are.
 *
 * These tests pin the two properties that made it readable:
 *   1. a node is never drawn larger than the text naming it;
 *   2. friends and mutuals are visually distinguishable without a label.
 */
class MutualGraphRadiusTest {

    /** 11 sp at the device's scaled density (420/160), the panel in use. */
    private val textPx = 11f * (420f / 160f)

    @Test
    fun a_node_is_never_wider_than_half_its_label_height() {
        // Sweep the whole range of connectivity and zoom the canvas allows:
        // nothing may break the ceiling, at any combination of inputs.
        for (scale in listOf(0.5f, 1f, 2.4f, 6f, 12f)) {
            for (count in listOf(0, 1, 7, 40, 242)) {
                for (isFriend in listOf(true, false)) {
                    val r = nodeScreenRadius(
                        isFriend = isFriend,
                        mutualCount = count,
                        maxMutualCount = 242,
                        scale = scale,
                        textPx = textPx
                    )
                    assertTrue(
                        "radius $r exceeded the text ceiling at scale=$scale count=$count",
                        r <= textPx * 0.5f + 0.001f
                    )
                }
            }
        }
    }

    @Test
    fun a_node_stays_visible_at_every_zoom() {
        // The other failure mode: optimising the crowding away by making nodes
        // so small they cannot be seen or tapped.
        for (scale in listOf(0.5f, 1f, 2.4f, 12f)) {
            val r = nodeScreenRadius(
                isFriend = false,
                mutualCount = 0,
                maxMutualCount = 1,
                scale = scale,
                textPx = textPx
            )
            assertTrue("mutual node vanished at scale=$scale (r=$r)", r >= 1f)
        }
    }

    @Test
    fun friends_are_drawn_larger_than_mutuals_at_the_same_zoom() {
        // Without this the two kinds of node are indistinguishable, which was
        // half of why the old graph read as noise.
        val scale = 2.4f
        val friend = nodeScreenRadius(
            isFriend = true,
            mutualCount = 1,
            maxMutualCount = 242,
            scale = scale,
            textPx = textPx
        )
        val mutual = nodeScreenRadius(
            isFriend = false,
            mutualCount = 0,
            maxMutualCount = 242,
            scale = scale,
            textPx = textPx
        )
        assertTrue("friend=$friend should exceed mutual=$mutual", friend > mutual)
    }

    @Test
    fun a_more_connected_friend_is_not_drawn_smaller() {
        val scale = 2.4f
        fun radiusFor(count: Int) = nodeScreenRadius(
            isFriend = true,
            mutualCount = count,
            maxMutualCount = 100,
            scale = scale,
            textPx = textPx
        )
        // Monotonic: the ceiling clamps the top end, which is intended, but it
        // must never invert the ordering.
        assertTrue(radiusFor(100) >= radiusFor(50))
        assertTrue(radiusFor(50) >= radiusFor(1))
    }

    @Test
    fun the_selected_node_may_exceed_the_ceiling_and_stays_tappable() {
        // The selection is the answer to the user's tap, so it is deliberately
        // exempt: it needs to read as the focal point at any zoom.
        val r = nodeScreenRadius(
            isFriend = false,
            mutualCount = 0,
            maxMutualCount = 1,
            scale = 0.5f,
            textPx = textPx,
            selected = true
        )
        assertTrue("selected node collapsed to $r", r >= 5f)
    }

    @Test
    fun faded_nodes_shrink_but_never_disappear() {
        val faded = nodeScreenRadius(
            isFriend = true,
            mutualCount = 40,
            maxMutualCount = 242,
            scale = 2.4f,
            textPx = textPx,
            faded = true
        )
        val normal = nodeScreenRadius(
            isFriend = true,
            mutualCount = 40,
            maxMutualCount = 242,
            scale = 2.4f,
            textPx = textPx
        )
        assertTrue("faded=$faded should recede from $normal", faded < normal)
        assertTrue("faded node must still be visible, got $faded", faded >= 1.5f)
    }
}
