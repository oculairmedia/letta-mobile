package com.letta.mobile.web.storage

import com.letta.mobile.data.canvas.KeyValueCanvasStorage
import com.letta.mobile.data.chat.display.OpenChatsOnCanvas
import com.letta.mobile.data.storage.KeyValueStore
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.ui.chat.session.ChatDockGeometry
import com.letta.mobile.ui.chat.surface.LayoutPrefStorage
import com.letta.mobile.ui.chat.surface.chatDockGeometryStorage
import com.letta.mobile.ui.shell.workspace.ShellChatBoard

/**
 * The conversation page's board in the browser (sync to other devices is letta-mobile-ckmub.17.9),
 * where the dock's placement is kept, and whether the page opens on the board.
 */
internal class WebChatCanvas(
    val board: ShellChatBoard,
    val dockGeometry: LayoutPrefStorage<ChatDockGeometry>,
    val openOnCanvas: Boolean,
)

/**
 * letta-mobile-ckmub.17.7: the browser's storage, bound to the shared stores. Small preferences go
 * to localStorage ([settings]); boards and their op logs to IndexedDB ([canvases]). Everything
 * above this is the shared code desktop runs over its own files.
 */
internal class WebStorage(
    val settings: SecureSettingsStore = LocalStorageSettingsStore(),
    documents: KeyValueStore = IndexedDbKeyValueStore(),
) {
    /** The boards and their op log, the stores the shared canvas session and relay take. */
    val canvases: KeyValueCanvasStorage = KeyValueCanvasStorage(documents)

    /** The conversation page's board, dock placement and "open on the canvas" choice. */
    fun chatCanvas(): WebChatCanvas = WebChatCanvas(
        board = ShellChatBoard(store = canvases.documents, opLog = canvases.opLog),
        dockGeometry = chatDockGeometryStorage(settings),
        openOnCanvas = OpenChatsOnCanvas(settings).enabled.value,
    )
}
