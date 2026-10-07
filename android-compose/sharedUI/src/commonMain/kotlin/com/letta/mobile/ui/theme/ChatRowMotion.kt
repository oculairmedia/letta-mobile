package com.letta.mobile.ui.theme

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.Alignment

/**
 * letta-mobile-bglj6.1.19: the timeline rows' motion vocabulary, ported from the legacy Android
 * chat (designsystem LettaMotion and ChatMotionPolicy) so the shared page's disclosures, label
 * swaps and fold-aways play the same ramps: enter over [LettaMotionTokens.ENTER_MILLIS] on
 * LinearOutSlowIn, exit over [LettaMotionTokens.EXIT_MILLIS] on FastOutLinearIn, fades on the
 * shorter fast-fade durations.
 *
 * Every builder takes `reduced` (the [LocalReducedMotion] preference) and snaps when it is true.
 */
internal class ChatRowMotion(private val reduced: Boolean) {
    private val enterSpec: FiniteAnimationSpec<Float> = tween(LettaMotionTokens.ENTER_MILLIS, easing = LinearOutSlowInEasing)
    private val fastFadeInSpec: FiniteAnimationSpec<Float> =
        tween(LettaMotionTokens.FAST_FADE_IN_MILLIS, easing = LinearOutSlowInEasing)
    private val fastFadeOutSpec: FiniteAnimationSpec<Float> =
        tween(LettaMotionTokens.FAST_FADE_OUT_MILLIS, easing = FastOutLinearInEasing)

    private fun <T> enterSize(): FiniteAnimationSpec<T> = tween(LettaMotionTokens.ENTER_MILLIS, easing = LinearOutSlowInEasing)

    private fun <T> exitSize(): FiniteAnimationSpec<T> = tween(LettaMotionTokens.EXIT_MILLIS, easing = FastOutLinearInEasing)

    private fun <T> chipSize(): FiniteAnimationSpec<T> = tween(LettaMotionTokens.CHIP_MILLIS, easing = LinearOutSlowInEasing)

    /** Detail expansion (legacy ChatMotionPolicy.expansion): fade + vertical expand, 190 ms in, 130 ms out. */
    fun expansionEnter(): EnterTransition {
        if (reduced) return EnterTransition.None
        return fadeIn(enterSpec) + expandVertically(enterSize(), expandFrom = Alignment.Top)
    }

    fun expansionExit(): ExitTransition {
        if (reduced) return ExitTransition.None
        return fadeOut(fastFadeOutSpec) + shrinkVertically(exitSize(), shrinkTowards = Alignment.Top)
    }

    /**
     * The tool card unfurl (legacy LettaMotion.unfurlEnter, letta-mobile-vui8q): the body opens
     * from the leading edge and downwards at once, so it reads as the card opening rather than
     * content appearing. Mirrored on collapse.
     */
    fun unfurlEnter(): EnterTransition {
        if (reduced) return EnterTransition.None
        return fadeIn(enterSpec) +
            expandHorizontally(enterSize(), expandFrom = Alignment.Start) +
            expandVertically(enterSize(), expandFrom = Alignment.Top)
    }

    fun unfurlExit(): ExitTransition {
        if (reduced) return ExitTransition.None
        return fadeOut(fastFadeOutSpec) +
            shrinkHorizontally(exitSize(), shrinkTowards = Alignment.Start) +
            shrinkVertically(exitSize(), shrinkTowards = Alignment.Top)
    }

    /**
     * A block sliding in from a share of its own height while it grows (legacy
     * LettaMotion.verticalEnter); the reasoning body slides from a quarter of its height.
     */
    fun reasoningBodyEnter(): EnterTransition {
        if (reduced) return EnterTransition.None
        return fadeIn(fastFadeInSpec) +
            slideInVertically(enterSize(), initialOffsetY = { it / REASONING_SLIDE_DIVISOR }) +
            expandVertically(enterSize(), expandFrom = Alignment.Top)
    }

    fun reasoningBodyExit(): ExitTransition {
        if (reduced) return ExitTransition.None
        return fadeOut(fastFadeOutSpec) +
            slideOutVertically(exitSize(), targetOffsetY = { it / REASONING_SLIDE_DIVISOR }) +
            shrinkVertically(exitSize(), shrinkTowards = Alignment.Top)
    }

    /** An inline chip (a spinner, a leading label) easing in sideways (legacy LettaMotion.horizontalEnter). */
    fun horizontalEnter(): EnterTransition {
        if (reduced) return EnterTransition.None
        return fadeIn(fastFadeInSpec) + expandHorizontally(chipSize(), expandFrom = Alignment.Start)
    }

    fun horizontalExit(): ExitTransition {
        if (reduced) return ExitTransition.None
        return fadeOut(fastFadeOutSpec) + shrinkHorizontally(chipSize(), shrinkTowards = Alignment.Start)
    }

    /**
     * The settled copy replacing the live one ("Working" to "Thought for 2s"; legacy
     * ChatMotionPolicy.terminalSwap): a 120 ms fade in over a 90 ms fade out.
     */
    fun terminalSwap(): ContentTransform {
        // The label's width follows the copy on the content-size ramp (or snaps), never the
        // default spring, so a reduced-motion swap does not still glide.
        val size = SizeTransform(clip = false) { _, _ -> if (reduced) snap() else bodyLiftSize() }
        if (reduced) return ContentTransform(EnterTransition.None, ExitTransition.None, sizeTransform = size)
        return ContentTransform(
            targetContentEnter = fadeIn(tween(LettaMotionTokens.FAST_FADE_IN_MILLIS, easing = FastOutSlowInEasing)),
            initialContentExit = fadeOut(fastFadeOutSpec),
            sizeTransform = size,
        )
    }

    /**
     * The run's label folding up once the run is no longer the newest, with the steps below easing
     * up into its place: legacy's settled-run body lift (RunBlockMotion animatedRunBodyLift, 220 ms
     * on the content-size ramp). Shared geometry keeps the label's own height constant as the run
     * settles, so there is no header growth to pull the body back over; the body's rise is this
     * fold, on the same ramp.
     */
    fun runLabelFoldExit(): ExitTransition {
        if (reduced) return ExitTransition.None
        return fadeOut(fastFadeOutSpec) + shrinkVertically(bodyLiftSize(), shrinkTowards = Alignment.Top)
    }

    /** The run's leading label folding sideways (the tool summary slides home), on the same ramp. */
    fun runLeadFoldExit(): ExitTransition {
        if (reduced) return ExitTransition.None
        return fadeOut(fastFadeOutSpec) + shrinkHorizontally(bodyLiftSize(), shrinkTowards = Alignment.Start)
    }

    /** Legacy RunBlockMotion's lift: 220 ms, FastOutSlowIn. */
    private fun <T> bodyLiftSize(): FiniteAnimationSpec<T> = tween(LettaMotionTokens.CONTENT_SIZE_MILLIS, easing = FastOutSlowInEasing)

    private companion object {
        const val REASONING_SLIDE_DIVISOR = 4
    }
}

/**
 * letta-mobile-bglj6.1.19: the legacy composer's and action sheet's press and swap springs, the
 * Material 3 expressive motion scheme's fast specs that the Android app theme installs
 * (designsystem Theme: `MotionScheme.expressive()`). Compose Multiplatform's material3 keeps
 * `MaterialTheme.motionScheme` internal, so the shared page carries the token values itself.
 */
internal object ChatExpressiveMotion {
    /** `MotionScheme.expressive().fastSpatialSpec()`: a quick spring with a little bounce, for shape and scale. */
    fun <T> fastSpatial(): FiniteAnimationSpec<T> = spring(dampingRatio = FAST_SPATIAL_DAMPING, stiffness = FAST_SPATIAL_STIFFNESS)

    /** `MotionScheme.expressive().fastEffectsSpec()`: a quick critically damped spring, for alpha and colour. */
    fun <T> fastEffects(): FiniteAnimationSpec<T> = spring(dampingRatio = FAST_EFFECTS_DAMPING, stiffness = FAST_EFFECTS_STIFFNESS)

    const val FAST_SPATIAL_DAMPING: Float = 0.6f
    const val FAST_SPATIAL_STIFFNESS: Float = 800f
    const val FAST_EFFECTS_DAMPING: Float = 1f
    const val FAST_EFFECTS_STIFFNESS: Float = 3800f
}
