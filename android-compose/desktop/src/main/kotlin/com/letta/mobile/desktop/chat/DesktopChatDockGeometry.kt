package com.letta.mobile.desktop.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.letta.mobile.desktop.data.DesktopChatDockGeometryStore
import com.letta.mobile.ui.chat.session.ChatDockGeometry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** A drag reports every frame; the file is written once the panel has been still this long. */
private const val DOCK_GEOMETRY_SAVE_DEBOUNCE_MS = 400L

/**
 * letta-mobile-bglj6.1: the docked chat panel's placement, loaded from [store] once and written
 * back off the UI thread, debounced, whenever it changes. One placement for every conversation:
 * where the chat sits is a property of the person's window, not of the conversation.
 */
@Composable
internal fun rememberDesktopChatDockGeometry(
    store: DesktopChatDockGeometryStore = remember { DesktopChatDockGeometryStore() },
): MutableState<ChatDockGeometry> {
    val geometry = remember(store) { mutableStateOf(store.load()) }
    // What the file holds, so an unmoved panel never writes.
    val saved = remember(store) { SavedDockGeometry(geometry.value) }
    val current = geometry.value
    LaunchedEffect(store, current) {
        if (current == saved.value) return@LaunchedEffect
        delay(DOCK_GEOMETRY_SAVE_DEBOUNCE_MS)
        // A dropped write costs one session's placement, never a frame.
        withContext(Dispatchers.IO) { runCatching { store.save(current) } }.onSuccess { saved.value = current }
    }
    return geometry
}

private class SavedDockGeometry(var value: ChatDockGeometry)
