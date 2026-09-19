package com.vrcx0.android.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vrcx0.android.ui.theme.VrcxMotion
import kotlinx.coroutines.delay

/**
 * Shared motion primitives.
 *
 * The app had a well-reasoned `VrcxMotion` token object and almost nothing
 * using it: presence dots, connectivity, empty states and list rows all changed
 * state as hard cuts. This file is the small set of reusable pieces that fixes
 * that in one place, so screens do not each invent their own timing.
 *
 * Two rules hold throughout:
 *
 *  - **Respect the reduced-motion setting.** Every primitive here reads
 *    [LocalReducedMotion] and degrades to an instant change. This is not
 *    optional polish: users who enable it commonly do so for vestibular
 *    reasons, and an 8dp dot that scales is enough to trigger symptoms for
 *    some people.
 *  - **Only animate alpha/scale/colour.** Nothing here animates size or
 *    position in a way that would force a re-measure of a list row.
 */

/**
 * Whether the app should minimise animation.
 *
 * Reads the app's own `reducedMotionAndBlur` preference, which the user can
 * toggle in 个人 → 设置 → 外观. Kept as a `CompositionLocal` so a primitive can
 * read it without threading the setting through every call site.
 */
val LocalReducedMotion = androidx.compose.runtime.staticCompositionLocalOf { false }

/**
 * A presence / status dot that fades and scales between colours instead of
 * snapping.
 *
 * State changes here are exactly the kind of thing that reads as "cheap" when
 * hard-cut: a friend goes offline and the dot jumps from green to grey in one
 * frame. A 180ms colour tween plus a brief scale dip makes the change
 * followable by the eye, which matters when 200 rows are on screen and only
 * one of them changed.
 *
 * @param color the current semantic colour (online / offline / travelling).
 * @param pulsing true while a transition state should breathe (connecting).
 */
@Composable
fun AnimatedStatusDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 12.dp,
    pulsing: Boolean = false
) {
    val reduced = LocalReducedMotion.current

    val animatedColor by animateColorAsState(
        targetValue = color,
        animationSpec = tween(if (reduced) 0 else VrcxMotion.ColorDurationMs),
        label = "statusColor"
    )

    /*
     * Scale dips to 0.7 whenever the colour changes, then springs back. Driven
     * off the colour itself rather than a separate flag so callers only pass
     * the state once and every change gets the same acknowledgement.
     */
    var bounce by remember { mutableStateOf(false) }
    LaunchedEffect(color) {
        if (reduced) return@LaunchedEffect
        bounce = true
        delay(VrcxMotion.StateDurationMs.toLong())
        bounce = false
    }
    val scale by animateFloatAsState(
        targetValue = if (bounce && !pulsing) 0.7f else 1f,
        animationSpec = VrcxMotion.FloatExpressive,
        label = "statusScale"
    )

    Box(
        modifier = modifier
            .size(size)
            .scale(if (reduced) 1f else scale)
            .clip(CircleShape)
            .background(animatedColor)
    )
}

/**
 * Animated unread badge.
 *
 * Appears with a scale-in rather than popping, because the badge is the only
 * signal that something happened while the user was elsewhere -- a hard cut is
 * easy to miss entirely.
 */
@Composable
fun AnimatedCountBadge(
    count: Int,
    content: @Composable (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val reduced = LocalReducedMotion.current

    AnimatedVisibility(
        visible = count > 0,
        enter = if (reduced) {
            fadeIn(animationSpec = tween(0))
        } else {
            scaleIn(
                animationSpec = androidx.compose.animation.core.spring(
                    dampingRatio = 0.7f,
                    stiffness = androidx.compose.animation.core.Spring.StiffnessMedium
                ),
                initialScale = 0.5f
            ) + fadeIn(animationSpec = tween(120))
        },
        exit = if (reduced) {
            fadeOut(animationSpec = tween(0))
        } else {
            scaleOut(targetScale = 0.5f, animationSpec = tween(110)) +
                fadeOut(animationSpec = tween(90))
        },
        modifier = modifier
    ) {
        content(count)
    }
}

/**
 * Wraps a list so its children fade and lift in with a stagger.
 *
 * Only the first few rows are staggered (see [VrcxMotion.staggerDelayMs]) --
 * the rest start together. This is deliberately *not* applied per-row inside a
 * `LazyColumn`, which would re-run the entrance every time a row scrolled back
 * into view. Apply it to the container, once, on first composition.
 *
 * @param enabled pass false when the content is a cache hit being restored, so
 *   a returning user does not sit through an animation they have already seen.
 */
@Composable
fun StaggeredReveal(
    index: Int,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val reduced = LocalReducedMotion.current
    if (reduced || !enabled) {
        content()
        return
    }

    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(VrcxMotion.staggerDelayMs(index).toLong())
        visible = true
    }

    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(VrcxMotion.StateDurationMs),
        label = "revealAlpha"
    )
    val lift by animateFloatAsState(
        targetValue = if (visible) 0f else 1f,
        animationSpec = VrcxMotion.FloatExpressive,
        label = "revealLift"
    )

    Box(
        modifier = Modifier
            .alpha(alpha)
            .scale(1f - lift * 0.02f)
    ) {
        content()
    }
}

/**
 * Cross-fades between two pieces of content (loading → loaded, empty → filled).
 *
 * `AnimatedContent` without a size transform: a `SizeTransform` re-measures its
 * children every frame, which is exactly what must not happen around a
 * `LazyColumn`. See the note in `MainScreen` about tab content for the same
 * reasoning.
 */
@Composable
fun <T> StateCrossfade(
    target: T,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit
) {
    val reduced = LocalReducedMotion.current
    AnimatedContent(
        targetState = target,
        transitionSpec = {
            fadeIn(animationSpec = tween(if (reduced) 0 else 160)) togetherWith
                fadeOut(animationSpec = tween(if (reduced) 0 else 110))
        },
        label = "stateCrossfade",
        modifier = modifier
    ) { state ->
        content(state)
    }
}

/**
 * Vertically expanding section (filter chips row, "show more", inline errors).
 *
 * Expanding height is animated with `expandVertically`, which is a layout
 * animation and does re-measure -- acceptable here because these sections hold
 * a fixed handful of chips rather than a list.
 */
@Composable
fun ExpandableSection(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val reduced = LocalReducedMotion.current
    AnimatedVisibility(
        visible = expanded,
        enter = if (reduced) {
            fadeIn(animationSpec = tween(0))
        } else {
            expandVertically(
                animationSpec = androidx.compose.animation.core.spring(
                    dampingRatio = 0.85f,
                    stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
                )
            ) + fadeIn(animationSpec = tween(140))
        },
        exit = if (reduced) {
            fadeOut(animationSpec = tween(0))
        } else {
            shrinkVertically(
                animationSpec = tween(VrcxMotion.StateDurationMs)
            ) + fadeOut(animationSpec = tween(100))
        },
        modifier = modifier
    ) {
        content()
    }
}
