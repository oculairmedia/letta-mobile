package com.letta.mobile.feature.chat

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
 * Chat-local motion ramps. Streaming growth remains very short and linear so
 * token chunks do not stack into wobble; user-triggered expansion gets the
 * slower Material easing used for readable local state changes.
 */
internal object ChatMotion {
    const val StreamingSizeMillis = 60
    const val ContentSizeMillis = 220
    const val EnterMillis = 190
    const val ExitMillis = 130
    const val FastFadeInMillis = 120
    const val FastFadeOutMillis = 90
    const val ChipMillis = 150

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
