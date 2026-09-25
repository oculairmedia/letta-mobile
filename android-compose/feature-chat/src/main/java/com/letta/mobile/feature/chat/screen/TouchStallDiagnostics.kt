package com.letta.mobile.feature.chat.screen

import androidx.compose.material3.DrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import com.letta.mobile.util.InputDiagnostics
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * letta-mobile-erx7m: gesture-state probes for the touch-stall investigation, logged under the
 * `Input` tag next to the app's `touch.dispatch` / `pointer.root` lines. Everything is gated on
 * [InputDiagnostics.enabled], which only debug builds switch on.
 */
private const val INPUT_TAG = "Input"

internal object SwipeUpToCanvasDiagnostics {
    /** The composer swipe claimed the stream (first consumed upward move past slop). */
    fun started() {
        if (!InputDiagnostics.enabled.get()) return
        Telemetry.event(INPUT_TAG, "swipeUpToCanvas.start")
    }

    /** How a claimed gesture ended: Committed, Released, Cancelled or detached. */
    fun ended(outcome: String) {
        if (!InputDiagnostics.enabled.get()) return
        val name = if (outcome == SwipeUpToCanvasOutcome.Committed.name) "commit" else "abandon"
        Telemetry.event(INPUT_TAG, "swipeUpToCanvas.$name", "result" to outcome)
    }
}

/** Logs every settled/target change of the agent drawer, including drags that settle back. */
@Composable
internal fun LogDrawerTransitions(drawerState: DrawerState) {
    LaunchedEffect(drawerState) {
        if (!InputDiagnostics.enabled.get()) return@LaunchedEffect
        snapshotFlow { drawerState.currentValue to drawerState.targetValue }
            .distinctUntilChanged()
            .collect { (current, target) ->
                Telemetry.event(
                    INPUT_TAG,
                    "drawer.state",
                    "current" to current.name,
                    "target" to target.name,
                    "animating" to drawerState.isAnimationRunning,
                )
            }
    }
}
