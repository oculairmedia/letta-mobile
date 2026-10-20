package com.letta.mobile.ui.canvas.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.letta.mobile.data.canvas.library.CanvasLibraryController
import com.letta.mobile.ui.host.Overlay
import com.letta.mobile.ui.host.OverlayAction
import com.letta.mobile.ui.host.OverlayPresentation
import com.letta.mobile.ui.host.OverlayPriority
import com.letta.mobile.ui.host.OverlayResponse
import com.letta.mobile.ui.host.OverlayTone
import com.letta.mobile.ui.host.OverlayWhile

/** Persistent window notice for library failures; retry reloads without repeating edits or creation. */
@Composable
fun CanvasLibraryNotice(controller: CanvasLibraryController) {
    val state by controller.state.collectAsState()
    var retryAttempt by remember(controller) { mutableIntStateOf(0) }
    val notice = state.error?.let { message ->
        Overlay.Banner(
            message = if (state.isLoading) "Retrying canvas storage…" else message,
            key = "canvas-library-storage",
            presentation = OverlayPresentation(priority = OverlayPriority.High, durationMillis = null, tone = OverlayTone.Error),
            response = if (state.isLoading) OverlayResponse.None else OverlayResponse(OverlayAction("Retry") {
                retryAttempt++
                controller.refresh()
            }),
        )
    }
    key(controller) { OverlayWhile(notice, key = "$retryAttempt:${state.error}:${state.isLoading}") }
}
