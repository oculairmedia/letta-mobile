package com.letta.mobile.ui.motion

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.unit.IntSize
import com.letta.mobile.ui.components.rememberReducedMotionEnabled
import com.letta.mobile.ui.theme.LettaMotionTokens

/**
 * Pre-allocated animation specs and tokens for chat activity motion semantics.
 * Top-level immutable references guarantee zero per-frame allocation during token lookup.
 */
object ChatMotionTokens {
    // -------------------------------------------------------------------------
    // 1. Running Cue (.4 shimmer, active task indicator)
    // -------------------------------------------------------------------------
    const val RUNNING_CUE_DURATION_MILLIS = 1000
    const val RUNNING_CUE_MIN_ALPHA = 0.15f
    const val RUNNING_CUE_MAX_ALPHA = 0.35f
    const val RUNNING_CUE_STATIC_ALPHA = 0.25f

    val StandardRunningCueSpec: AnimationSpec<Float> = infiniteRepeatable(
        animation = tween(RUNNING_CUE_DURATION_MILLIS, easing = LinearEasing),
        repeatMode = RepeatMode.Reverse,
    )
    val ReducedRunningCueSpec: FiniteAnimationSpec<Float> = snap()

    // -------------------------------------------------------------------------
    // 2. Insertion (.5 timeline primitives)
    // -------------------------------------------------------------------------
    val StandardInsertionEnter: EnterTransition =
        fadeIn(animationSpec = tween(LettaMotionTokens.FAST_FADE_IN_MILLIS, easing = LinearOutSlowInEasing)) +
            slideInVertically(
                animationSpec = tween(LettaMotionTokens.ENTER_MILLIS, easing = LinearOutSlowInEasing),
                initialOffsetY = { it / 5 },
            ) +
            expandVertically(
                animationSpec = tween(LettaMotionTokens.ENTER_MILLIS, easing = LinearOutSlowInEasing),
            )

    val StandardInsertionExit: ExitTransition =
        fadeOut(animationSpec = tween(LettaMotionTokens.FAST_FADE_OUT_MILLIS, easing = FastOutLinearInEasing)) +
            slideOutVertically(
                animationSpec = tween(LettaMotionTokens.EXIT_MILLIS, easing = FastOutLinearInEasing),
                targetOffsetY = { it / 5 },
            ) +
            shrinkVertically(
                animationSpec = tween(LettaMotionTokens.EXIT_MILLIS, easing = FastOutLinearInEasing),
            )

    val StandardInsertionSizeSpec: FiniteAnimationSpec<IntSize> =
        tween(durationMillis = LettaMotionTokens.CONTENT_SIZE_MILLIS, easing = FastOutSlowInEasing)

    // -------------------------------------------------------------------------
    // 3. Expansion (.8 auto-expand/collapse)
    // -------------------------------------------------------------------------
    val StandardExpansionEnter: EnterTransition =
        fadeIn(animationSpec = tween(LettaMotionTokens.ENTER_MILLIS, easing = LinearOutSlowInEasing)) +
            expandVertically(
                animationSpec = tween(LettaMotionTokens.ENTER_MILLIS, easing = LinearOutSlowInEasing),
            )

    val StandardExpansionExit: ExitTransition =
        fadeOut(animationSpec = tween(LettaMotionTokens.FAST_FADE_OUT_MILLIS, easing = FastOutLinearInEasing)) +
            shrinkVertically(
                animationSpec = tween(LettaMotionTokens.EXIT_MILLIS, easing = FastOutLinearInEasing),
            )

    val StandardExpansionSizeSpec: FiniteAnimationSpec<IntSize> =
        tween(durationMillis = LettaMotionTokens.CONTENT_SIZE_MILLIS, easing = FastOutSlowInEasing)

    // -------------------------------------------------------------------------
    // 4. Staged Completion Collapse (.8 auto-expand/collapse)
    // -------------------------------------------------------------------------
    val StandardStagedCollapseEnter: EnterTransition =
        fadeIn(animationSpec = tween(LettaMotionTokens.FAST_FADE_IN_MILLIS, easing = LinearOutSlowInEasing)) +
            expandVertically(
                animationSpec = tween(LettaMotionTokens.CHIP_MILLIS, easing = FastOutSlowInEasing),
            )

    val StandardStagedCollapseExit: ExitTransition =
        fadeOut(animationSpec = tween(LettaMotionTokens.FAST_FADE_OUT_MILLIS, easing = FastOutLinearInEasing)) +
            shrinkVertically(
                animationSpec = tween(LettaMotionTokens.EXIT_MILLIS, easing = FastOutLinearInEasing),
            )

    val StandardStagedCollapseSizeSpec: FiniteAnimationSpec<IntSize> =
        tween(durationMillis = LettaMotionTokens.CHIP_MILLIS, easing = FastOutSlowInEasing)

    // -------------------------------------------------------------------------
    // 5. Terminal Swap (.10 streaming markdown fade, .11 reasoning typewriter)
    // -------------------------------------------------------------------------
    const val TYPEWRITER_STEP_DELAY_MILLIS = 15L
    const val TYPEWRITER_REDUCED_STEP_DELAY_MILLIS = 0L

    val StandardTerminalSwapCrossfadeSpec: FiniteAnimationSpec<Float> =
        tween(durationMillis = LettaMotionTokens.FAST_FADE_IN_MILLIS, easing = FastOutSlowInEasing)

    val StandardTerminalSwapEnter: EnterTransition =
        fadeIn(animationSpec = StandardTerminalSwapCrossfadeSpec)

    val StandardTerminalSwapExit: ExitTransition =
        fadeOut(animationSpec = tween(durationMillis = LettaMotionTokens.FAST_FADE_OUT_MILLIS, easing = FastOutLinearInEasing))

    // -------------------------------------------------------------------------
    // Reduced-motion instant singletons (Snap / None)
    // -------------------------------------------------------------------------
    val InstantSizeSpec: FiniteAnimationSpec<IntSize> = snap()
    val InstantFloatSpec: FiniteAnimationSpec<Float> = snap()
    val InstantEnter: EnterTransition = EnterTransition.None
    val InstantExit: ExitTransition = ExitTransition.None
}

/**
 * Policy contract for active work / pulse / shimmer indicators (.4 shimmer).
 */
@Immutable
data class RunningCuePolicy(
    val spec: AnimationSpec<Float>,
    val minAlpha: Float,
    val maxAlpha: Float,
    val staticAlpha: Float,
    val allowInfiniteAnimation: Boolean,
)

/**
 * Policy contract for timeline / activity item insertions (.5 timeline primitives).
 */
@Immutable
data class InsertionPolicy(
    val enter: EnterTransition,
    val exit: ExitTransition,
    val sizeSpec: FiniteAnimationSpec<IntSize>,
)

/**
 * Policy contract for card / tool detail expansion (.8 auto-expand/collapse).
 */
@Immutable
data class ExpansionPolicy(
    val enter: EnterTransition,
    val exit: ExitTransition,
    val sizeSpec: FiniteAnimationSpec<IntSize>,
)

/**
 * Policy contract for staged completion collapse of reasoning / tool blocks (.8 auto-expand/collapse).
 */
@Immutable
data class StagedCollapsePolicy(
    val enter: EnterTransition,
    val exit: ExitTransition,
    val sizeSpec: FiniteAnimationSpec<IntSize>,
)

/**
 * Policy contract for terminal content swap & typewriter reveals (.10 streaming markdown fade, .11 reasoning typewriter).
 */
@Immutable
data class TerminalSwapPolicy(
    val enter: EnterTransition,
    val exit: ExitTransition,
    val crossfadeSpec: FiniteAnimationSpec<Float>,
    val typewriterStepDelayMillis: Long,
)

/**
 * Central motion policy for chat activity components. Exposes semantic motion roles:
 * - [runningCue]: Active work indicators and shimmer cues (.4 shimmer)
 * - [insertion]: Timeline item entrance and exit (.5 timeline primitives)
 * - [expansion]: Tool and card detail expansion (.8 auto-expand/collapse)
 * - [stagedCollapse]: Completion collapse to summary headers (.8 auto-expand/collapse)
 * - [terminalSwap]: Final state crossfades and typewriter reveals (.10 streaming markdown fade, .11 reasoning typewriter)
 *
 * Guideline: Callers should request a semantic role from this policy rather than hardcoding durations.
 * Note: Outer LazyColumn item placement is unanimated; content text parsing is kept strictly separate from motion code.
 */
@Stable
interface ChatMotionPolicy {
    val isReducedMotionEnabled: Boolean
    val runningCue: RunningCuePolicy
    val insertion: InsertionPolicy
    val expansion: ExpansionPolicy
    val stagedCollapse: StagedCollapsePolicy
    val terminalSwap: TerminalSwapPolicy

    companion object {
        /**
         * Resolves the appropriate singleton policy ([Standard] or [Reduced]) matching [reducedMotionEnabled].
         */
        fun of(reducedMotionEnabled: Boolean): ChatMotionPolicy =
            if (reducedMotionEnabled) Reduced else Standard

        /** Canonical standard motion policy. Immutable singleton. */
        val Standard: ChatMotionPolicy = StandardChatMotionPolicy

        /** Canonical reduced-motion policy with instant snaps and zero infinite animations. Immutable singleton. */
        val Reduced: ChatMotionPolicy = ReducedChatMotionPolicy
    }
}

private object StandardChatMotionPolicy : ChatMotionPolicy {
    override val isReducedMotionEnabled: Boolean = false

    override val runningCue: RunningCuePolicy = RunningCuePolicy(
        spec = ChatMotionTokens.StandardRunningCueSpec,
        minAlpha = ChatMotionTokens.RUNNING_CUE_MIN_ALPHA,
        maxAlpha = ChatMotionTokens.RUNNING_CUE_MAX_ALPHA,
        staticAlpha = ChatMotionTokens.RUNNING_CUE_STATIC_ALPHA,
        allowInfiniteAnimation = true,
    )

    override val insertion: InsertionPolicy = InsertionPolicy(
        enter = ChatMotionTokens.StandardInsertionEnter,
        exit = ChatMotionTokens.StandardInsertionExit,
        sizeSpec = ChatMotionTokens.StandardInsertionSizeSpec,
    )

    override val expansion: ExpansionPolicy = ExpansionPolicy(
        enter = ChatMotionTokens.StandardExpansionEnter,
        exit = ChatMotionTokens.StandardExpansionExit,
        sizeSpec = ChatMotionTokens.StandardExpansionSizeSpec,
    )

    override val stagedCollapse: StagedCollapsePolicy = StagedCollapsePolicy(
        enter = ChatMotionTokens.StandardStagedCollapseEnter,
        exit = ChatMotionTokens.StandardStagedCollapseExit,
        sizeSpec = ChatMotionTokens.StandardStagedCollapseSizeSpec,
    )

    override val terminalSwap: TerminalSwapPolicy = TerminalSwapPolicy(
        enter = ChatMotionTokens.StandardTerminalSwapEnter,
        exit = ChatMotionTokens.StandardTerminalSwapExit,
        crossfadeSpec = ChatMotionTokens.StandardTerminalSwapCrossfadeSpec,
        typewriterStepDelayMillis = ChatMotionTokens.TYPEWRITER_STEP_DELAY_MILLIS,
    )
}

private object ReducedChatMotionPolicy : ChatMotionPolicy {
    override val isReducedMotionEnabled: Boolean = true

    override val runningCue: RunningCuePolicy = RunningCuePolicy(
        spec = ChatMotionTokens.ReducedRunningCueSpec,
        minAlpha = ChatMotionTokens.RUNNING_CUE_STATIC_ALPHA,
        maxAlpha = ChatMotionTokens.RUNNING_CUE_STATIC_ALPHA,
        staticAlpha = ChatMotionTokens.RUNNING_CUE_STATIC_ALPHA,
        allowInfiniteAnimation = false,
    )

    override val insertion: InsertionPolicy = InsertionPolicy(
        enter = ChatMotionTokens.InstantEnter,
        exit = ChatMotionTokens.InstantExit,
        sizeSpec = ChatMotionTokens.InstantSizeSpec,
    )

    override val expansion: ExpansionPolicy = ExpansionPolicy(
        enter = ChatMotionTokens.InstantEnter,
        exit = ChatMotionTokens.InstantExit,
        sizeSpec = ChatMotionTokens.InstantSizeSpec,
    )

    override val stagedCollapse: StagedCollapsePolicy = StagedCollapsePolicy(
        enter = ChatMotionTokens.InstantEnter,
        exit = ChatMotionTokens.InstantExit,
        sizeSpec = ChatMotionTokens.InstantSizeSpec,
    )

    override val terminalSwap: TerminalSwapPolicy = TerminalSwapPolicy(
        enter = ChatMotionTokens.InstantEnter,
        exit = ChatMotionTokens.InstantExit,
        crossfadeSpec = ChatMotionTokens.InstantFloatSpec,
        typewriterStepDelayMillis = ChatMotionTokens.TYPEWRITER_REDUCED_STEP_DELAY_MILLIS,
    )
}

/**
 * Returns the [ChatMotionPolicy] corresponding to system motion preferences.
 * Seamlessly integrates with [rememberReducedMotionEnabled].
 */
@Composable
fun rememberChatMotionPolicy(
    reducedMotionEnabled: Boolean = rememberReducedMotionEnabled(),
): ChatMotionPolicy = ChatMotionPolicy.of(reducedMotionEnabled)
