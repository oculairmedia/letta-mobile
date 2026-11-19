package com.letta.mobile.ui.components.motion

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.TransformOrigin
import com.letta.mobile.ui.theme.tokens.LocalLettaReducedMotion

/**
 * A panel extending from the control it belongs to (a card rising out of a prompt bar, a tray dropping
 * from a toolbar) and folding back into it, for an AnimatedVisibility around the panel.
 *
 * Entering, the panel's edge at its anchor ([anchorEdge]: Alignment.Bottom for a panel above its
 * anchor, Alignment.Top for one below) stays pinned while the rest unfolds away from it on the
 * emphasized-decelerate curve ([enterEasing]), it fades in over the first part of that, and it grows
 * from just under full size around the same edge on a soft spring, so it lands with a slight settle.
 * Leaving is quicker and plainer: it folds back into the anchor on the emphasized-accelerate curve
 * ([exitEasing]) as it fades. [rememberPanelSettle] adds the arrival's afterglow for a panel that lights
 * its seam with the anchor.
 *
 * Every builder takes `reduced` (the reduced-motion preference): then nothing moves in space and the
 * panel only fades, briefly. Every animation is finite, so it settles under a paused test clock.
 */
object LettaPanelMotion {
    /** The panel unfolding from its anchor: the emphasized-decelerate duration. */
    const val ENTER_MILLIS: Int = 400

    /** The panel folding back: quick, so ending something never feels held up. */
    const val EXIT_MILLIS: Int = 180

    /** The fade over the start of the unfold: the panel is opaque well before it has landed. */
    const val FADE_IN_MILLIS: Int = 150

    /** The fade while it folds back. */
    const val FADE_OUT_MILLIS: Int = 120

    /** The only motion under reduced motion: a short fade in and out. */
    const val REDUCED_FADE_MILLIS: Int = 120

    /** The arrival's afterglow dying away ([rememberPanelSettle]). */
    const val SETTLE_MILLIS: Int = 700

    /** How much brighter a seam lit by [rememberPanelSettle] glows the moment the panel lands (1 doubles it). */
    const val SETTLE_GLOW_GAIN: Float = 0.8f

    /** How far under full size the panel starts and ends. */
    const val SCALE_FROM: Float = 0.94f

    /** Under 1, so the growth runs a hair past full size and settles back: the "landing". */
    const val SCALE_DAMPING_RATIO: Float = 0.7f

    /** Material's emphasized decelerate: fast off the anchor, a long gentle arrival. */
    val enterEasing: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** Material's emphasized accelerate: eases off, then gets out of the way. */
    val exitEasing: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** The panel extending from its anchor at [anchorEdge]; a short fade alone when [reduced]. */
    fun enter(reduced: Boolean, anchorEdge: Alignment.Vertical): EnterTransition {
        if (reduced) return fadeIn(tween(REDUCED_FADE_MILLIS))
        return expandVertically(tween(ENTER_MILLIS, easing = enterEasing), expandFrom = anchorEdge) +
            fadeIn(tween(FADE_IN_MILLIS)) +
            scaleIn(
                spring(dampingRatio = SCALE_DAMPING_RATIO, stiffness = Spring.StiffnessMediumLow),
                initialScale = SCALE_FROM,
                transformOrigin = originAt(anchorEdge),
            )
    }

    /** The panel folding back into its anchor at [anchorEdge]; a short fade alone when [reduced]. */
    fun exit(reduced: Boolean, anchorEdge: Alignment.Vertical): ExitTransition {
        if (reduced) return fadeOut(tween(REDUCED_FADE_MILLIS))
        return shrinkVertically(tween(EXIT_MILLIS, easing = exitEasing), shrinkTowards = anchorEdge) +
            fadeOut(tween(FADE_OUT_MILLIS)) +
            scaleOut(tween(EXIT_MILLIS, easing = exitEasing), targetScale = SCALE_FROM, transformOrigin = originAt(anchorEdge))
    }

    private fun originAt(edge: Alignment.Vertical): TransformOrigin = when (edge) {
        Alignment.Top -> TransformOrigin(CENTRE, 0f)
        Alignment.Bottom -> TransformOrigin(CENTRE, 1f)
        else -> TransformOrigin.Center
    }

    private const val CENTRE = 0.5f
}

/**
 * The afterglow of a panel arriving: 1 the moment [shown] turns true (not when it is already true on
 * the first composition: nothing arrived), dying away to 0 over [LettaPanelMotion.SETTLE_MILLIS] on
 * the decelerate curve; 0 throughout under reduced motion. Read the returned value at draw time (a
 * seam's glow brightened by it) so the settle redraws without recomposing.
 */
@Composable
fun rememberPanelSettle(shown: Boolean): () -> Float {
    val reduced = LocalLettaReducedMotion.current
    val settle = remember { Animatable(0f) }
    val firstShown = remember { booleanArrayOf(shown) }
    LaunchedEffect(shown) {
        val arrived = shown && !firstShown[0] && !reduced
        firstShown[0] = false
        if (arrived) {
            settle.snapTo(1f)
            settle.animateTo(0f, tween(LettaPanelMotion.SETTLE_MILLIS, easing = LettaPanelMotion.enterEasing))
        } else {
            settle.snapTo(0f)
        }
    }
    return remember(settle) { { settle.value } }
}
