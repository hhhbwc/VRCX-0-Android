package com.vrcx0.android.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * Motion tokens.
 *
 * Material 3 Expressive leans on springs rather than fixed durations: the
 * movement keeps some momentum and settles instead of stopping dead, which is
 * most of what makes an interface feel alive. Everything here is spring-based
 * so the whole app shares one motion personality.
 *
 * Damping ratio cheat sheet:
 *   < 1.0  overshoots slightly (playful)  -- used for content appearing
 *   = 1.0  no overshoot (crisp)           -- used for navigation and dismissal
 */
object VrcxMotion {

    /** Crisp, no overshoot. Navigation, dismissal, anything reversible. */
    val Spatial: FiniteAnimationSpec<IntOffset> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium
    )

    /** Slight overshoot. Content arriving on screen. */
    val Expressive: FiniteAnimationSpec<IntOffset> = spring(
        dampingRatio = 0.8f,
        stiffness = Spring.StiffnessMedium
    )

    /** Gentle, for large surfaces such as the login -> main transition. */
    val Emphasis: FiniteAnimationSpec<IntOffset> = spring(
        dampingRatio = 0.9f,
        stiffness = 220f
    )

    val FloatSpatial: FiniteAnimationSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium
    )

    val FloatExpressive: FiniteAnimationSpec<Float> = spring(
        dampingRatio = 0.8f,
        stiffness = Spring.StiffnessMedium
    )

    /** Expand/collapse animate size, not offset, so they need their own spec. */
    val SizeExpressive: FiniteAnimationSpec<IntSize> = spring(
        dampingRatio = 0.8f,
        stiffness = Spring.StiffnessMedium
    )

    /**
     * Colour transitions (presence dots, connection state, chip selection).
     *
     * Springs are wrong for colour: they interpolate through the Oklab
     * conversion per frame for no visible benefit, and a colour has no
     * "momentum". A short tween is both cheaper and calmer. This value exists
     * so the dozens of colour changes in the app stop being hard cuts.
     */
    const val ColorDurationMs = 180

    /**
     * Alpha/scale for small state glyphs -- the online/offline dot, the unread
     * badge, the travelling indicator. Fast enough to read as instant, slow
     * enough that the eye can follow which row changed.
     */
    const val StateDurationMs = 140

    /**
     * Stagger between successive items in a list reveal.
     *
     * Applied by index (`index * StaggerMs`) and, critically, **capped** -- see
     * [staggerDelayMs]. Revealing a 200-row list at 30ms apart would animate
     * for six seconds, which is not an entrance, it is a loading screen.
     */
    const val StaggerMs = 30

    /**
     * Delay for the item at [index] in a staggered reveal.
     *
     * Caps the total delay at [maxDelayMs] so that rows past the cap all start
     * together. Only the first handful of rows are ever visible at once, so
     * past that point the stagger is invisible and pure latency.
     */
    fun staggerDelayMs(index: Int, maxDelayMs: Int = 240): Int =
        (index.coerceAtLeast(0) * StaggerMs).coerceAtMost(maxDelayMs)

    /** Keep this one duration-based: it loops forever, so a spring never settles. */
    const val PulseDurationMs = 1400
}

/** Standard screen swap: cross-fade with a small directional slide. */
fun screenEnter(forward: Boolean = true): EnterTransition =
    fadeIn(animationSpec = spring(stiffness = Spring.StiffnessMedium)) +
        slideInHorizontally(
            animationSpec = VrcxMotion.Expressive,
            initialOffsetX = { if (forward) it / 4 else -it / 4 }
        )

fun screenExit(forward: Boolean = true): ExitTransition =
    fadeOut(animationSpec = spring(stiffness = Spring.StiffnessMedium)) +
        slideOutHorizontally(
            animationSpec = VrcxMotion.Spatial,
            targetOffsetX = { if (forward) -it / 4 else it / 4 }
        )

/**
 * Tab content: a fade, and deliberately nothing else.
 *
 * This used to be a fade **plus a scale** (`scaleIn`/`scaleOut`) wrapped in an
 * `AnimatedContent` with `SizeTransform`. That combination is the wrong tool for
 * a tab whose content is a `LazyColumn`:
 *
 *  - a scale is applied through `graphicsLayer`, so the whole list is rendered
 *    into a separate layer rather than directly;
 *  - `SizeTransform` re-measures every child on every frame of the change, and
 *    measuring a 242-row list per frame is the definition of jank.
 *
 * The tabs now swap instantly (see `MainScreen`), which is both cheaper and
 * calmer. This spec is kept, scale-free, for any future transition that does
 * need one -- do not add a scale back to it.
 */
val tabEnterTransition: EnterTransition =
    fadeIn(animationSpec = tween(durationMillis = 120))

val tabExitTransition: ExitTransition =
    fadeOut(animationSpec = tween(durationMillis = 90))

val tabTransitionSpec
    get() = tabEnterTransition togetherWith tabExitTransition
