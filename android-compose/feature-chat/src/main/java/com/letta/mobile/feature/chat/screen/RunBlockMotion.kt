package com.letta.mobile.feature.chat.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.motion.ChatMotionPolicy

/**
 * The run header expands in and collapses out instead of snapping (letta-mobile-qygvv.20).
 *
 * A first composition shows the header in its resting state without playing the enter motion, so
 * lazy recycling never replays it; only a change of [visible] animates.
 */
@Composable
internal fun ColumnScope.AnimatedRunHeader(
    visible: Boolean,
    reducedMotion: Boolean,
    content: @Composable () -> Unit,
) {
    val motion = ChatMotionPolicy.of(reducedMotion).expansion
    AnimatedVisibility(visible = visible, enter = motion.enter, exit = motion.exit, label = "RunHeader") {
        content()
    }
}

/**
 * Cross-fades the header's label - "Working" to "Thought for 2s" - rather than swapping the text in
 * one frame when the run completes or its settled copy replaces the live one.
 */
@Composable
internal fun RunHeaderLabel(
    label: String,
    reducedMotion: Boolean,
    content: @Composable (String) -> Unit,
) {
    val swap = ChatMotionPolicy.of(reducedMotion).terminalSwap
    AnimatedContent(
        targetState = label,
        transitionSpec = { swap.enter togetherWith swap.exit },
        label = "RunHeaderLabel",
    ) { content(it) }
}

/** The completed run's body lift under its header, eased rather than jumped. */
@Composable
internal fun animatedRunBodyLift(lifted: Boolean, reducedMotion: Boolean): State<Dp> = animateDpAsState(
    targetValue = if (lifted) CompletedRunBodyLift else 0.dp,
    animationSpec = if (reducedMotion) snap() else tween(RunBodyLiftMillis),
    label = "RunBodyLift",
)

private val CompletedRunBodyLift = (-22).dp
private const val RunBodyLiftMillis = 220
