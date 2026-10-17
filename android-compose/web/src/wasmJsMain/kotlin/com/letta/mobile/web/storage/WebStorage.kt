package com.letta.mobile.web.storage

import com.letta.mobile.data.canvas.CanvasRelayAttachment
import com.letta.mobile.data.canvas.CanvasRelayClient
import com.letta.mobile.data.canvas.CanvasRelayDial
import com.letta.mobile.data.canvas.KeyValueCanvasStorage
import com.letta.mobile.data.chat.display.OpenChatsOnCanvas
import com.letta.mobile.data.storage.KeyValueStore
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.ui.chat.session.ChatDockGeometry
import com.letta.mobile.ui.chat.surface.LayoutPrefStorage
import com.letta.mobile.ui.chat.surface.chatDockGeometryStorage
import com.letta.mobile.ui.shell.workspace.ShellChatBoard
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow

/**
 * The conversation page's board in the browser, synced with edits and cursors through the shared
 * relay (letta-mobile-ckmub.17.9), where the dock's placement is kept, and whether the page opens on
 * the board.
 */
internal class WebChatCanvas(
    val board: ShellChatBoard,
    val dockGeometry: LayoutPrefStorage<ChatDockGeometry>,
    val openOnCanvas: Boolean,
)

/**
 * letta-mobile-ckmub.17.7: the browser's storage, bound to the shared stores. Small preferences go
 * to localStorage ([settings]); boards, their op logs and their delivery record to IndexedDB
 * ([canvases]). Everything above this is the shared code desktop runs over its own files.
 */
internal class WebStorage(
    val settings: SecureSettingsStore = LocalStorageSettingsStore(),
    documents: KeyValueStore = IndexedDbKeyValueStore(),
) {
    /** The boards and their op log, the stores the shared canvas session and relay take. */
    val canvases: KeyValueCanvasStorage = KeyValueCanvasStorage(documents)

    /**
     * letta-mobile-ckmub.17.9: the shared relay client over [canvases], every board's sync and
     * cursors on this page. It takes no asset store yet: the web has none (letta-mobile-ckmub.17.13),
     * so another app's images show their previews here.
     */
    val canvasRelay: CanvasRelayClient = canvases.relayClient()

    /** The conversation page's board, dock placement and "open on the canvas" choice. */
    fun chatCanvas(): WebChatCanvas = WebChatCanvas(
        board = ShellChatBoard(
            store = canvases.documents,
            opLog = canvases.opLog,
            syncTransport = canvasRelay,
            presence = canvasRelay,
        ),
        dockGeometry = chatDockGeometryStorage(settings),
        openOnCanvas = OpenChatsOnCanvas(settings).enabled.value,
    )

    /**
     * Carries [canvasRelay] to the host while the caller runs: over an Iroh backend ([iroh]) it
     * follows [dials], the live session's way to the relay, through the same attachment desktop
     * uses; on any other backend every board stays on this device, and says so.
     */
    suspend fun followCanvasHost(dials: Flow<CanvasRelayDial?>, iroh: Boolean) = coroutineScope {
        val attachment = CanvasRelayAttachment(this, canvasRelay)
        if (iroh) attachment.attach(dials).join() else attachment.detach()
    }
}
