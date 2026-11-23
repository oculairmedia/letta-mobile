package com.letta.mobile.ui.components.gesture

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp

/** The line an item is flicked along: sideways, or up and down. */
enum class SwipeAwayAxis { Horizontal, Vertical }

/** How far an item must be dragged before letting go puts it away. */
@Immutable
sealed interface SwipeAwayThreshold {
    /** The distance in px for an item [extentPx] long along the swipe's axis. */
    fun px(density: Density, extentPx: Float): Float

    /** A fixed distance, whatever the item's size (a snackbar). */
    @Immutable
    class Distance(val distance: Dp) : SwipeAwayThreshold {
        override fun px(density: Density, extentPx: Float): Float = with(density) { distance.toPx() }
    }

    /** A share of the item's own length along the axis (a card that varies in width). */
    @Immutable
    class Fraction(val fraction: Float) : SwipeAwayThreshold {
        override fun px(density: Density, extentPx: Float): Float = extentPx * fraction
    }
}

/** What the item does once it is let go past the threshold. */
enum class SwipeAwayExit {
    /** It slides on off the edge it was flicked towards, then the dismissal runs. */
    OffEdge,

    /** The dismissal runs at once, where it was let go: the caller's own exit (a fold, a fade) takes it away. */
    InPlace,
}

/**
 * How letting go plays: the [exit] past the threshold, and the durations of the slide off the edge
 * ([exitMillis], for [SwipeAwayExit.OffEdge]) and of the spring back short of it ([settleMillis]).
 * Callers pass their motion tokens; under reduced motion both snap.
 */
@Immutable
class SwipeAwayRelease(val exit: SwipeAwayExit, val exitMillis: Int, val settleMillis: Int)

/**
 * One swipe-away gesture: the [axis] it follows, the [threshold] that commits it, how [release] plays
 * and how much the item [fade]s as it travels its own length (0 not at all, 1 to nothing).
 */
@Immutable
class SwipeAwaySpec(
    val threshold: SwipeAwayThreshold,
    val release: SwipeAwayRelease,
    val axis: SwipeAwayAxis = SwipeAwayAxis.Horizontal,
    val fade: Float = FULL_FADE,
) {
    companion object {
        /** A fully travelled item is fully transparent. */
        const val FULL_FADE: Float = 1f
    }
}
