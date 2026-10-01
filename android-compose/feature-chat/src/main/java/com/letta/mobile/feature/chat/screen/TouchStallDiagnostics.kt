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
