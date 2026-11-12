package com.letta.mobile.ui.canvas.boards

import com.letta.mobile.data.canvas.mount.client.ConversationCanvasPicker
import com.letta.mobile.ui.canvas.mount.ProvideConversationCanvasPicker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.library.CanvasBoardSessions
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.ui.canvas.ProvideCanvasHost
import com.letta.mobile.ui.canvas.plugin.CanvasDocumentViewerRegistry

/**
 * Opens a stored board by its id, for a view that shows one board wherever it is placed (a canvas
 * module in a shell lane). The source decides which store the id is read from.
 */
fun interface CanvasBoardSource {
    /**
     * A session over the stored board [canvasId], or null when there is no such board. The view
     * holds it until it gives it back with [release].
     */
    suspend fun open(canvasId: CanvasId): CanvasSession?

    /** Gives back a session [open] handed out, once the view no longer shows it. */
    fun release(session: CanvasSession) {}
}

/**
 * Where a view that shows a board by id opens it. Null (the default) is a host that cannot open
 * boards by id; such a view shows only boards already live in the host's
 * [com.letta.mobile.ui.canvas.LocalCanvasSessionRegistry].
 */
val LocalCanvasBoardSource = staticCompositionLocalOf<CanvasBoardSource?> { null }

/** The window's board owner, including serialized host-specific session creation. */
val LocalCanvasBoardSessions = staticCompositionLocalOf<CanvasBoardSessions?> { null }

/** Retained page-key namespace belonging to the host's current board table. */
val LocalCanvasBoardGeneration = staticCompositionLocalOf { 0L }

/** A source opening boards through the window's [sessions], one live session per board. */
fun canvasBoardSource(sessions: CanvasBoardSessions): CanvasBoardSource = object : CanvasBoardSource {
    override suspend fun open(canvasId: CanvasId): CanvasSession? = sessions.acquire(canvasId)

    override fun release(session: CanvasSession) = sessions.release(session)
}

/**
 * Lets every view inside [content] open boards by id through the window's [sessions]; a host
 * passes the table it owns (see [CanvasBoardSessions] for who keeps a session alive and how long).
 */
@Composable
fun ProvideCanvasBoardSource(sessions: CanvasBoardSessions, content: @Composable () -> Unit) {
    val source = remember(sessions) { canvasBoardSource(sessions) }
    CompositionLocalProvider(LocalCanvasBoardSource provides source, content = content)
}

/**
 * A host's canvas services ([ProvideCanvasHost]: its live boards and views) together with the
 * window's table [boards], where every view inside [content] opens boards by id, and the window's
 * [picker] (which board each conversation shows; null is a host without canvas mounts).
 */
@Composable
fun ProvideCanvasHostWithBoards(
    viewers: CanvasDocumentViewerRegistry?,
    boards: CanvasBoardSessions,
    picker: ConversationCanvasPicker? = null,
    content: @Composable () -> Unit,
) {
    ProvideCanvasHost(boards.registry, viewers) {
        CompositionLocalProvider(LocalCanvasBoardSessions provides boards) {
            ProvideConversationCanvasPicker(picker) { ProvideCanvasBoardSource(boards, content) }
        }
    }
}
