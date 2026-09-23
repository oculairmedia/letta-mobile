package com.letta.mobile.desktop.canvas

import com.letta.mobile.data.canvas.CanvasOpLog
import com.letta.mobile.data.canvas.CanvasPresenceTransport
import com.letta.mobile.data.canvas.CanvasSyncTransport
import com.letta.mobile.data.canvas.FileCanvasOpLog
import com.letta.mobile.data.transport.iroh.IrohCanvasClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The desktop's canvas transport and op log, shared by every canvas session in the process.
 *
 * Sessions in this process (windows, panes, chat canvases) share edits and cursors directly. With
 * an Iroh host connected (see `DesktopIrohBindings`), [client] also carries them to the host,
 * which relays them to every other app on it, so a canvas is the same board everywhere.
 */
object DesktopCanvasHostSync {
    val opLog: CanvasOpLog = FileCanvasOpLog()

    @Suppress("NoDetachedCoroutineLifecycle") // Lives as long as the desktop process, like the op log.
    val client: IrohCanvasClient = IrohCanvasClient(CoroutineScope(SupervisorJob() + Dispatchers.IO), opLog)

    val syncTransport: CanvasSyncTransport get() = client.sync
    val presenceTransport: CanvasPresenceTransport get() = client.presence
}
