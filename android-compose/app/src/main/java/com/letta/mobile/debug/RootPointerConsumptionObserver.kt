package com.letta.mobile.debug

import androidx.compose.ui.Modifier
import ca.oculair.meridian.BuildConfig
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import com.letta.mobile.util.Telemetry

/**
 * letta-mobile-erx7m: a pure observer at the root of the app's composition. For every pointer
 * DOWN / UP it logs the change set Compose delivered and which changes were already consumed by
 * the time the event came back up to the root (Final pass runs root-first, after every
 * descendant's Initial and Main passes).
 *
 * What it tells us when touch stalls:
 *  - no `pointer.root` line for a DOWN that `touch.dispatch` saw → Compose dropped the event
 *    before hit testing (bad event / detached view);
 *  - `consumed` lists the DOWN → some node swallows DOWNs;
 *  - `pressed` counts more pointers than the finger has → a phantom pointer is stuck pressed in
 *    Compose's state (every awaitEachGesture detector then waits for "all pointers up").
 *
 * It never consumes anything. [debugRootPointerObserver] attaches it in debug builds only.
 */
internal fun debugRootPointerObserver(): Modifier =
    if (BuildConfig.DEBUG) Modifier.rootPointerConsumptionObserver() else Modifier

private fun Modifier.rootPointerConsumptionObserver(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val initial = awaitPointerEvent(PointerEventPass.Initial)
            if (!initial.isStreamBoundary()) continue
            val consumedBeforeMain = initial.changes.map { it.toSnapshot() }
            val final = awaitPointerEvent(PointerEventPass.Final)
            logRootPointerEvent(final, consumedBeforeMain)
        }
    }
}

private fun PointerEvent.isStreamBoundary(): Boolean =
    changes.any { it.pressed != it.previousPressed }

private fun PointerInputChange.toSnapshot() = PointerChangeSnapshot(
    id = id.value,
    pressed = pressed,
    previousPressed = previousPressed,
    consumed = isConsumed,
)

private fun logRootPointerEvent(event: PointerEvent, beforeMain: List<PointerChangeSnapshot>) {
    val after = event.changes.map { it.toSnapshot() }
    Telemetry.event(
        TouchDispatchDiagnostics.TAG,
        "pointer.root",
        "type" to event.type.toString(),
        "changes" to summarizePointerChanges(after),
        "pressed" to after.count { it.pressed },
        "consumedBeforeMain" to beforeMain.filter { it.consumed }.map { it.id },
        "consumed" to after.filter { it.consumed }.map { it.id },
    )
}

/** Plain copy of the parts of a [PointerInputChange] a stall report needs. */
internal data class PointerChangeSnapshot(
    val id: Long,
    val pressed: Boolean,
    val previousPressed: Boolean,
    val consumed: Boolean,
)

/** One token per change: `id:DOWN`, `id:UP`, `id:held` or `id:hover`, with `*` when consumed. */
internal fun summarizePointerChanges(changes: List<PointerChangeSnapshot>): String =
    changes.joinToString(separator = " ") { change ->
        val phase = when {
            change.pressed && !change.previousPressed -> "DOWN"
            !change.pressed && change.previousPressed -> "UP"
            change.pressed -> "held"
            else -> "hover"
        }
        "${change.id}:$phase" + if (change.consumed) "*" else ""
    }
