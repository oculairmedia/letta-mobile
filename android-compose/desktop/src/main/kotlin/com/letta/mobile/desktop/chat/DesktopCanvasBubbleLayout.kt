package com.letta.mobile.desktop.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.letta.mobile.data.canvas.bubble.CanvasBubbleLayout
import com.letta.mobile.data.canvas.bubble.CanvasBubbleLayoutPrefs
import com.letta.mobile.desktop.data.DesktopCanvasBubbleLayoutStore
import com.letta.mobile.ui.chat.surface.LocalCanvasBubbleLayoutPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext

/** A slider or an edge drag reports every frame; the file is written once it has been still this long. */
private const val BUBBLE_LAYOUT_SAVE_DEBOUNCE_MS = 400L

/**
 * letta-mobile-zxk2n.29: binds the shared minimised-chat layout prefs to the desktop's properties
 * file. Read once, as the chat font scale is; written off the UI thread, debounced.
 */
@OptIn(FlowPreview::class)
@Composable
internal fun DesktopCanvasBubbleLayoutHost(
    store: DesktopCanvasBubbleLayoutStore = remember { DesktopCanvasBubbleLayoutStore() },
    content: @Composable () -> Unit,
) {
    val prefs = remember(store) {
        CanvasBubbleLayoutPrefs(CanvasBubbleLayout.Desktop, runCatching { store.load() }.getOrNull())
    }
    LaunchedEffect(prefs) {
        prefs.layout.drop(1).debounce(BUBBLE_LAYOUT_SAVE_DEBOUNCE_MS).collect { layout ->
            // A dropped write costs one session's layout, never a frame.
            withContext(Dispatchers.IO) { runCatching { store.save(layout) } }
        }
    }
    CompositionLocalProvider(LocalCanvasBubbleLayoutPrefs provides prefs, content = content)
}
