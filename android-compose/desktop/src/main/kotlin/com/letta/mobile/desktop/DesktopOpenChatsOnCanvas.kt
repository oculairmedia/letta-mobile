package com.letta.mobile.desktop

import androidx.compose.runtime.staticCompositionLocalOf
import com.letta.mobile.desktop.data.DesktopOpenChatsOnCanvasStore
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * letta-mobile-bglj6.1: "Open conversations on the canvas" (default on). The shared chat page
 * reads it once for a conversation's initial presentation; the traditional full-screen chat is
 * the optional alternative. The user's later mode changes still win.
 */
internal class DesktopOpenChatsOnCanvas(
    private val store: DesktopOpenChatsOnCanvasStore = DesktopOpenChatsOnCanvasStore(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val state = MutableStateFlow(store.load())

    /** The saved preference; true unless the user turned it off. */
    val enabled: StateFlow<Boolean> = state.asStateFlow()

    /** Applies [enabled] now and writes it off the UI thread. A failed write keeps it for this session. */
    suspend fun setEnabled(enabled: Boolean) {
        state.value = enabled
        withContext(ioDispatcher) {
            try {
                store.save(enabled)
            } catch (_: IOException) {
                // Kept in memory for this session; the next successful save persists it.
            }
        }
    }

    companion object {
        /** The process-wide preference the app reads; tests construct their own. */
        val Default: DesktopOpenChatsOnCanvas by lazy { DesktopOpenChatsOnCanvas() }
    }
}

/** The preference the shell reads; overridable for tests and previews. */
internal val LocalDesktopOpenChatsOnCanvas = staticCompositionLocalOf { DesktopOpenChatsOnCanvas.Default }
