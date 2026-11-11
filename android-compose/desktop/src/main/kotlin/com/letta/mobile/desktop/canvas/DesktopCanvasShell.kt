package com.letta.mobile.desktop.canvas

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.CanvasSessionRegistry
import com.letta.mobile.ui.canvas.notes.notebookCanvasViewers
import com.letta.mobile.ui.canvas.plugin.CanvasDocumentViewerRegistry
import com.letta.mobile.data.canvas.CanvasDocumentStore
import com.letta.mobile.data.canvas.library.CanvasBoardSessions
import com.letta.mobile.data.canvas.library.CanvasLibraryController
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.desktop.OpenDesktopCanvasParams
import com.letta.mobile.desktop.openDesktopCanvasSession
import com.letta.mobile.ui.canvas.boards.CanvasPageBoard
import com.letta.mobile.ui.shell.workspace.ShellCanvasOwner
import kotlinx.coroutines.CoroutineScope

/**
 * The shell's canvas wiring in one place: the store, the library the sidebar lists, the canvas open
 * ([activeSession], which every pane reads and changes only through these verbs) and its views.
 */
internal class DesktopCanvasShell(
    val store: CanvasDocumentStore,
    val library: CanvasLibraryController,
    private val scope: CoroutineScope,
    /** The window's live boards, where its mounts register and its canvas_* tools look them up. */
    val sessions: CanvasSessionRegistry = CanvasSessionRegistry(),
    val viewers: CanvasDocumentViewerRegistry = notebookCanvasViewers(DesktopNotebookCanvasStore.notebooks),
    /** One live session per board for the page and every canvas module; the page holds what it opens. */
    val boards: CanvasBoardSessions = CanvasBoardSessions(store, DesktopCanvasHostSync.options, sessions),
) {
    private val page = CanvasPageBoard(boards)
    var activeSession: CanvasSession? by page

    fun open(id: CanvasId) = library.open(id, boards, page::take)

    fun createNew(agentId: String?) = library.createNew(agentId?.let(::AgentId)) { activeSession = it }

    /** The conversation's own canvas, created on first open. */
    fun openForConversation(owner: ShellCanvasOwner) = openDesktopCanvasSession(
        OpenDesktopCanvasParams(
            scope = scope,
            store = store,
            conversationId = owner.conversationId,
            agentId = owner.agentId,
            agentName = owner.agentName,
            onSessionReady = { activeSession = it },
        ),
    )

    fun close() {
        activeSession = null
    }
}

@Composable
internal fun rememberDesktopCanvasShell(scope: CoroutineScope): DesktopCanvasShell {
    val store = remember { DesktopNotebookCanvasStore.documents }
    val library = rememberDesktopCanvasLibrary(store, scope)
    val shell = remember(store, library, scope) { DesktopCanvasShell(store, library, scope) }
    DesktopCanvasCrashReproHook(shell)
    // Opening, creating or closing a canvas can change what the library lists.
    LaunchedEffect(shell.activeSession) { library.refresh() }
    return shell
}
