package com.letta.mobile.desktop.canvas

import com.letta.mobile.data.canvas.CanvasOpLog
import com.letta.mobile.data.canvas.CanvasPresenceTransport
import com.letta.mobile.data.canvas.CanvasRelayClient
import com.letta.mobile.data.canvas.CanvasSyncTransport
import com.letta.mobile.data.canvas.FileCanvasDeliveryStore
import com.letta.mobile.data.canvas.FileCanvasOpLog
import com.letta.mobile.data.canvas.StoreCanvasClosedBoardApplier
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

    /**
     * Where the desktop's canvases keep their images (and any other large things put on a board),
     * in `~/.letta/canvas/assets`. One store for the boards and the relay: the image a board places
     * is the one sent to the host, and one fetched from the host is the one the board draws.
     */
    val assets: com.letta.mobile.data.storage.AssetStore = com.letta.mobile.data.storage.FileAssetStore(
        DesktopCanvasDocumentStore.defaultRootDirectory().resolve("assets").toFile(),
    )

    val relay: CanvasRelayClient = CanvasRelayClient(
        opLog = opLog,
        delivery = FileCanvasDeliveryStore(
            DesktopCanvasDocumentStore.defaultRootDirectory().resolve("delivery.json").toFile(),
        ),
        topicOf = { documents.relayTopicOf(it) },
        assets = assets,
        // Ops for a board no window has open go straight into the stored canvas (qygvv.23).
        closedBoard = StoreCanvasClosedBoardApplier(documents, opLog),
    )

    @Suppress("NoDetachedCoroutineLifecycle") // Lives as long as the desktop process, like the op log.
    val client: IrohCanvasRelayClient = IrohCanvasRelayClient(CoroutineScope(SupervisorJob() + Dispatchers.IO), relay)

    val syncTransport: CanvasSyncTransport get() = relay
    val presenceTransport: CanvasPresenceTransport get() = relay
}
