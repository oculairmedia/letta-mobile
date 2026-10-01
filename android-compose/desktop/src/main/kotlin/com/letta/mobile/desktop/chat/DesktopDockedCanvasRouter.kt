package com.letta.mobile.desktop.chat

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.letta.mobile.data.canvas.CanvasId

/**
 * letta-mobile-bglj6.1: while the shared chat page is on, the conversation's board lives in the
 * page's docked canvas. Requests that would open it again in the side pane (the composer's canvas
 * command, "open canvas", a library or search pick of the same board) are routed to the dock
 * instead, so one board never has two live CanvasSessions.
 */
@Stable
internal class DesktopDockedCanvasRouter {
    /** The board the docked canvas holds, reported by the page while it is composed. */
    var dockedCanvasId: CanvasId? by mutableStateOf(null)

    /** Bumped on every request to show the docked canvas; the page collapses to it. */
    var showRequests: Int by mutableIntStateOf(0)
        private set

    fun showDocked() {
        showRequests++
    }

    /** A library or search pick: the docked board shows in the dock, any other in the side pane. */
    fun open(id: CanvasId, openSidePane: (CanvasId) -> Unit) {
        if (id == dockedCanvasId) showDocked() else openSidePane(id)
    }
}

/** The side pane shows a board only when it is not the one already docked under the chat. */
internal fun showsCanvasSidePane(sidePane: CanvasId?, docked: CanvasId?): Boolean = sidePane != null && sidePane != docked
