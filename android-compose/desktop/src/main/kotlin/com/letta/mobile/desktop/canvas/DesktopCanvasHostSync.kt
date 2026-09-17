package com.letta.mobile.desktop.canvas

import com.letta.mobile.data.canvas.CanvasOpLog
import com.letta.mobile.data.canvas.CanvasPresenceTransport
import com.letta.mobile.data.canvas.CanvasSyncTransport
import com.letta.mobile.data.canvas.InMemoryCanvasPresenceTransport
import com.letta.mobile.data.canvas.LoopbackCanvasSyncTransport

/**
 * Shared in-process transport and op-log instances for desktop canvas sessions.
 *
 * Allows multiple windows, panes, and chat canvas sessions to collaborate
 * in real time with synchronized op projection and presence cursors.
 */
object DesktopCanvasHostSync {
    val syncTransport: CanvasSyncTransport = LoopbackCanvasSyncTransport()
    val presenceTransport: CanvasPresenceTransport = InMemoryCanvasPresenceTransport()
    val opLog: CanvasOpLog = DesktopCanvasOpLog()
}
