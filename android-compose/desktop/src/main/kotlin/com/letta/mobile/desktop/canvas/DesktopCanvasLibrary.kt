package com.letta.mobile.desktop.canvas

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.letta.mobile.data.canvas.CanvasConversationOptions
import com.letta.mobile.data.canvas.CanvasDocumentStore
import com.letta.mobile.data.canvas.library.CanvasLibraryController
import kotlinx.coroutines.CoroutineScope

/** Desktop supplies storage and sync bindings; library state and failure policy are shared. */
internal typealias DesktopCanvasLibrary = CanvasLibraryController

@Composable
internal fun rememberDesktopCanvasLibrary(
    store: CanvasDocumentStore,
    scope: CoroutineScope,
): DesktopCanvasLibrary = remember(store, scope) {
    CanvasLibraryController(
        store,
        scope,
        DesktopCanvasArchiveStore(),
        CanvasConversationOptions(opLog = DesktopCanvasHostSync.opLog, syncTransport = DesktopCanvasHostSync.syncTransport),
    )
}

/** The conversation list's archive filter, as it applies to the canvas library. */
internal fun com.letta.mobile.desktop.chat.ConversationArchiveFilter.toCanvasArchiveFilter(): com.letta.mobile.data.canvas.CanvasArchiveFilter =
    when (this) {
        com.letta.mobile.desktop.chat.ConversationArchiveFilter.Active -> com.letta.mobile.data.canvas.CanvasArchiveFilter.ACTIVE
        com.letta.mobile.desktop.chat.ConversationArchiveFilter.Archived -> com.letta.mobile.data.canvas.CanvasArchiveFilter.ARCHIVED
        com.letta.mobile.desktop.chat.ConversationArchiveFilter.All -> com.letta.mobile.data.canvas.CanvasArchiveFilter.ALL
    }
