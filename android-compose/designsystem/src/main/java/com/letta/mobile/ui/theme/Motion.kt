package com.letta.mobile.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.snap
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * Letta design system motion timings and easing curves. Extracted from
 * ChatScreen as the canonical motion reference (letta-mobile-awbf.1).
 *
 * Streaming growth remains very short and linear so token chunks do not
 * stack into wobble; user-triggered expansion gets the slower Material
 * easing used for readable local state changes.
 */
object LettaMotion {
    const val StreamingSizeMillis = LettaMotionTokens.StreamingSizeMillis
    const val ContentSizeMillis = LettaMotionTokens.ContentSizeMillis
    const val EnterMillis = LettaMotionTokens.EnterMillis
    const val ExitMillis = LettaMotionTokens.ExitMillis
    const val FastFadeInMillis = LettaMotionTokens.FastFadeInMillis
    const val FastFadeOutMillis = LettaMotionTokens.FastFadeOutMillis
    const val ChipMillis = LettaMotionTokens.ChipMillis

    val streamingSizeSpec: FiniteAnimationSpec<IntSize> =
        tween(durationMillis = StreamingSizeMillis, easing = LinearEasing)

    val contentSizeSpec: FiniteAnimationSpec<IntSize> =
        tween(durationMillis = ContentSizeMillis, easing = FastOutSlowInEasing)

    val instantSizeSpec: FiniteAnimationSpec<IntSize> = snap()

    val chipSizeSpec: FiniteAnimationSpec<IntSize> =
        tween(durationMillis = ChipMillis, easing = LinearOutSlowInEasing)

    val chipFadeInSpec: FiniteAnimationSpec<Float> =
        tween(durationMillis = FastFadeInMillis, easing = LinearOutSlowInEasing)

    val chipFadeOutSpec: FiniteAnimationSpec<Float> =
        tween(durationMillis = FastFadeOutMillis, easing = FastOutLinearInEasing)

    val chipCrossfadeSpec: FiniteAnimationSpec<Float> =
        tween(durationMillis = ChipMillis, easing = FastOutSlowInEasing)

    fun expandEnter(
        expandFrom: Alignment.Vertical = Alignment.Top,
    ): EnterTransition =
        fadeIn(animationSpec = tween(durationMillis = EnterMillis, easing = LinearOutSlowInEasing)) +
            expandVertically(
                animationSpec = tween(durationMillis = EnterMillis, easing = LinearOutSlowInEasing),
                expandFrom = expandFrom,
            )

    fun expandExit(
        shrinkTowards: Alignment.Vertical = Alignment.Top,
    ): ExitTransition =
        fadeOut(animationSpec = tween(durationMillis = FastFadeOutMillis, easing = FastOutLinearInEasing)) +
            shrinkVertically(
                animationSpec = tween(durationMillis = ExitMillis, easing = FastOutLinearInEasing),
                shrinkTowards = shrinkTowards,
            )

    // letta-mobile-vui8q: tool card unfurl. Horizontal expand from the
    // leading edge (Alignment.Start in LTR, mirrored in RTL by Compose)
    // combined with vertical expand for the content height, plus a soft
    // fadeIn. Visually communicates 'the card is opening' rather than
    // 'content appeared.' Mirrored cleanly on collapse.
    fun unfurlEnter(): EnterTransition =
        fadeIn(animationSpec = tween(durationMillis = EnterMillis, easing = LinearOutSlowInEasing)) +
            expandHorizontally(
                animationSpec = tween(durationMillis = EnterMillis, easing = LinearOutSlowInEasing),
                expandFrom = Alignment.Start,
            ) +
            expandVertically(
                animationSpec = tween(durationMillis = EnterMillis, easing = LinearOutSlowInEasing),
                expandFrom = Alignment.Top,
            )

    fun unfurlExit(): ExitTransition =
        fadeOut(animationSpec = tween(durationMillis = FastFadeOutMillis, easing = FastOutLinearInEasing)) +
            shrinkHorizontally(
                animationSpec = tween(durationMillis = ExitMillis, easing = FastOutLinearInEasing),
                shrinkTowards = Alignment.Start,
            ) +
            shrinkVertically(
                animationSpec = tween(durationMillis = ExitMillis, easing = FastOutLinearInEasing),
                shrinkTowards = Alignment.Top,
            )

    fun instantEnter(): EnterTransition = EnterTransition.None

    fun instantExit(): ExitTransition = ExitTransition.None

    fun verticalEnter(
        slideDivisor: Int = 5,
        expandFrom: Alignment.Vertical = Alignment.Top,
    ): EnterTransition =
        fadeIn(animationSpec = tween(durationMillis = FastFadeInMillis, easing = LinearOutSlowInEasing)) +
            slideInVertically(
                animationSpec = tween<IntOffset>(durationMillis = EnterMillis, easing = LinearOutSlowInEasing),
                initialOffsetY = { it / slideDivisor },
            ) +
            expandVertically(
                animationSpec = tween(durationMillis = EnterMillis, easing = LinearOutSlowInEasing),
                expandFrom = expandFrom,
            )

    fun verticalExit(
        slideDivisor: Int = 5,
        shrinkTowards: Alignment.Vertical = Alignment.Top,
    ): ExitTransition =
        fadeOut(animationSpec = tween(durationMillis = FastFadeOutMillis, easing = FastOutLinearInEasing)) +
            slideOutVertically(
                animationSpec = tween<IntOffset>(durationMillis = ExitMillis, easing = FastOutLinearInEasing),
                targetOffsetY = { it / slideDivisor },
            ) +
            shrinkVertically(
                animationSpec = tween(durationMillis = ExitMillis, easing = FastOutLinearInEasing),
                shrinkTowards = shrinkTowards,
            )

    fun horizontalEnter(): EnterTransition =
        fadeIn(animationSpec = chipFadeInSpec) +
            expandHorizontally(animationSpec = chipSizeSpec, expandFrom = Alignment.Start)

    fun horizontalExit(): ExitTransition =
        fadeOut(animationSpec = chipFadeOutSpec) +
            shrinkHorizontally(animationSpec = chipSizeSpec, shrinkTowards = Alignment.Start)
}
