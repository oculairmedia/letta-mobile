package com.letta.mobile.ui.chat.surface

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import com.letta.mobile.data.canvas.bubble.CanvasBubbleLayout
import com.letta.mobile.data.canvas.bubble.CanvasBubbleLayoutPrefs
import com.letta.mobile.ui.chat.session.ChatDockGeometry
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext

/**
 * letta-mobile-ckmub.3.14: where a platform keeps one shared layout preference (a properties
 * file on desktop). Both calls may block and may throw; the shared hosts below run them on [io]
 * and treat a failure as "nothing stored" / "not written", so a broken file costs a session's
 * layout, never a frame.
 */
@Immutable
class LayoutPrefStorage<T : Any>(
    val load: () -> T?,
    val save: (T) -> Unit,
    val io: CoroutineContext = Dispatchers.Default,
)

/** A slider, an edge drag or a panel drag reports every frame; storage is written once it has been still this long. */
const val LAYOUT_PREF_SAVE_DEBOUNCE_MS = 400L

/**
 * letta-mobile-zxk2n.29: provides the minimised-chat layout prefs ([LocalCanvasBubbleLayoutPrefs])
 * for [defaults], read once from [storage] and written back off the UI thread, debounced.
 */
@OptIn(FlowPreview::class)
@Composable
fun CanvasBubbleLayoutHost(
    defaults: CanvasBubbleLayout,
    storage: LayoutPrefStorage<CanvasBubbleLayout>,
    content: @Composable () -> Unit,
) {
    val prefs = remember(storage, defaults) {
        CanvasBubbleLayoutPrefs(defaults, runCatching { storage.load() }.getOrNull())
    }
    LaunchedEffect(prefs) {
        prefs.layout.drop(1).debounce(LAYOUT_PREF_SAVE_DEBOUNCE_MS).collect { layout ->
            writePref(storage, layout)
        }
    }
    CompositionLocalProvider(LocalCanvasBubbleLayoutPrefs provides prefs, content = content)
}

/**
 * letta-mobile-bglj6.1: the docked chat panel's placement, read from [storage] off the UI thread
 * (null until it arrives, so the page draws no dock rather than one that jumps) and written back,
 * debounced, whenever it changes. A placement still waiting out the debounce when the caller
 * leaves is written then, so the last move is never lost. One placement for every conversation:
 * where the chat sits is a property of the window, not of the conversation.
 */
@Composable
fun rememberChatDockGeometry(storage: LayoutPrefStorage<ChatDockGeometry>): MutableState<ChatDockGeometry?> {
    val geometry = remember(storage) { mutableStateOf<ChatDockGeometry?>(null) }
    LaunchedEffect(storage) {
        val loaded = withContext(storage.io) { runCatching { storage.load() }.getOrNull() } ?: ChatDockGeometry.Default
        geometry.value = loaded
        // What storage holds, so an unmoved panel never writes.
        var saved = loaded
        try {
            snapshotFlow { geometry.value }.collectLatest { next ->
                if (next == null || next == saved) return@collectLatest
                delay(LAYOUT_PREF_SAVE_DEBOUNCE_MS)
                if (writePref(storage, next)) saved = next
            }
        } finally {
            // Leaving cancels the debounce: write the pending placement anyway.
            val pending = geometry.value
            if (pending != null && pending != saved) withContext(NonCancellable) { writePref(storage, pending) }
        }
    }
    return geometry
}

private suspend fun <T : Any> writePref(storage: LayoutPrefStorage<T>, value: T): Boolean =
    withContext(storage.io) { runCatching { storage.save(value) }.isSuccess }
