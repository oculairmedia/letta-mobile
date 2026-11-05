package com.letta.mobile.ui.canvas.plugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * What the viewer on show must finish before the person switches away from it: a note saves its
 * draft and refuses while a conflict or a failed write remains. The host asks each check in turn
 * and keeps the viewer when any says no, so switching views never drops unsaved work.
 */
class CanvasViewerLeaveChecks {
    private val checks = mutableListOf<() -> (suspend () -> Boolean)>()

    internal fun add(check: () -> (suspend () -> Boolean)): () -> Unit {
        checks += check
        return { checks -= check }
    }

    suspend fun allowLeave(): Boolean = checks.toList().all { it()() }
}

val LocalCanvasViewerLeaveChecks = staticCompositionLocalOf<CanvasViewerLeaveChecks?> { null }

/** Asks [check] before the person leaves the viewer this composition is in; true lets them leave. */
@Composable
fun CanvasViewerLeaveCheck(check: suspend () -> Boolean) {
    val checks = LocalCanvasViewerLeaveChecks.current ?: return
    val current = rememberUpdatedState(check)
    DisposableEffect(checks) {
        val remove = checks.add { current.value }
        onDispose(remove)
    }
}
