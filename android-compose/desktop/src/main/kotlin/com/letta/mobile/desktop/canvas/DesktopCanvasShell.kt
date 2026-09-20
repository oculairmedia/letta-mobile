package com.letta.mobile.desktop.canvas

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.letta.mobile.data.canvas.CanvasId
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.desktop.OpenDesktopCanvasParams
import com.letta.mobile.desktop.openDesktopCanvasSession
import kotlinx.coroutines.CoroutineScope

/** Who a conversation's canvas belongs to, for [DesktopCanvasShell.openForConversation]. */
internal data class DesktopCanvasOwner(
    val conversationId: String?,
    val agentId: String?,
    val agentName: String,
)

/**
 * The shell's canvas wiring in one place: the store, the library the sidebar lists, and the
 * canvas currently open. The rail, sidebar, content pane and overlays all read [activeSession]
 * and call the open/create/close verbs instead of each writing the session state themselves.
 */
internal class DesktopCanvasShell(
    val store: DesktopCanvasDocumentStore,
    val library: DesktopCanvasLibrary,
    private val scope: CoroutineScope,
    sessionState: MutableState<CanvasSession?>,
) {
    var activeSession: CanvasSession? by sessionState

    fun open(id: CanvasId) = library.open(id) { activeSession = it }

    fun createNew(agentId: String?) = library.createNew(agentId) { activeSession = it }

    /** The conversation's own canvas, created on first open. */
    fun openForConversation(owner: DesktopCanvasOwner) = openDesktopCanvasSession(
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
    val store = remember { DesktopCanvasDocumentStore() }
    val library = rememberDesktopCanvasLibrary(store, scope)
    val sessionState = remember { mutableStateOf<CanvasSession?>(null) }
    val shell = remember(store, library, scope) { DesktopCanvasShell(store, library, scope, sessionState) }
    DesktopCanvasCrashReproHook(shell)
    // Opening, creating or closing a canvas can change what the library lists.
    LaunchedEffect(sessionState.value) { library.refresh() }
    return shell
}
