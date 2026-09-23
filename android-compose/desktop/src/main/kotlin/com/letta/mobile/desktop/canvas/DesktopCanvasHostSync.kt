package com.letta.mobile.desktop.canvas

import com.letta.mobile.data.canvas.CanvasOpLog
import com.letta.mobile.data.canvas.CanvasPresenceTransport
import com.letta.mobile.data.canvas.CanvasRelayClient
import com.letta.mobile.data.canvas.CanvasSyncTransport
import com.letta.mobile.data.canvas.FileCanvasDeliveryStore
import com.letta.mobile.data.canvas.FileCanvasOpLog
import com.letta.mobile.data.canvas.relayTopicOf
import com.letta.mobile.data.transport.iroh.IrohCanvasRelayClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The desktop's canvas transport, op log and delivery record, shared by every canvas session in
 * the process.
 *
 * Sessions in this process (windows, panes, chat canvases) share edits and cursors directly. With
 * an Iroh host (see `DesktopIrohBindings`), [client] carries them to the host, which relays them to
 * every other app on it; queued edits survive restarts in `~/.letta/canvas/delivery.json`.
 */
object DesktopCanvasHostSync {
    val opLog: CanvasOpLog = FileCanvasOpLog()

    private val documents = DesktopCanvasDocumentStore()

    val relay: CanvasRelayClient = CanvasRelayClient(
        opLog = opLog,
        delivery = FileCanvasDeliveryStore(
            DesktopCanvasDocumentStore.defaultRootDirectory().resolve("delivery.json").toFile(),
        ),
        topicOf = { documents.relayTopicOf(it) },
    )

    @Suppress("NoDetachedCoroutineLifecycle") // Lives as long as the desktop process, like the op log.
    val client: IrohCanvasRelayClient = IrohCanvasRelayClient(CoroutineScope(SupervisorJob() + Dispatchers.IO), relay)

    val syncTransport: CanvasSyncTransport get() = relay
    val presenceTransport: CanvasPresenceTransport get() = relay
}
