package com.letta.mobile.desktop.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import com.letta.mobile.desktop.data.DesktopChatDockGeometryStore
import com.letta.mobile.ui.chat.session.ChatDockGeometry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

/** A drag reports every frame; the file is written once the panel has been still this long. */
private const val DOCK_GEOMETRY_SAVE_DEBOUNCE_MS = 400L

/**
 * letta-mobile-bglj6.1: the docked chat panel's placement, read from [store] off the UI thread
 * (null until it arrives, so the page draws no dock rather than one that jumps; hosts remember
 * this once per app session) and written back off it, debounced, whenever it
 * changes. A placement still waiting out the debounce when the page leaves is written then, so
 * the last move is never lost. One placement for every conversation: where the chat sits is a
 * property of the person's window, not of the conversation.
 */
@Composable
internal fun rememberDesktopChatDockGeometry(
    store: DesktopChatDockGeometryStore = remember { DesktopChatDockGeometryStore() },
): MutableState<ChatDockGeometry?> {
    val geometry = remember(store) { mutableStateOf<ChatDockGeometry?>(null) }
    LaunchedEffect(store) {
        val loaded = withContext(Dispatchers.IO) { runCatching { store.load() }.getOrDefault(ChatDockGeometry.Default) }
        geometry.value = loaded
        // What the file holds, so an unmoved panel never writes.
        var saved = loaded
        try {
            snapshotFlow { geometry.value }.collectLatest { next ->
                if (next == null || next == saved) return@collectLatest
                delay(DOCK_GEOMETRY_SAVE_DEBOUNCE_MS)
                if (persistDockGeometry(store, next)) saved = next
            }
        } finally {
            // Leaving the page cancels the debounce: write the pending placement anyway.
            val pending = geometry.value
            if (pending != null && pending != saved) withContext(NonCancellable) { persistDockGeometry(store, pending) }
        }
    }
    return geometry
}

/** A dropped write costs one session's placement, never a frame. */
private suspend fun persistDockGeometry(store: DesktopChatDockGeometryStore, geometry: ChatDockGeometry): Boolean =
    withContext(Dispatchers.IO) { runCatching { store.save(geometry) }.isSuccess }