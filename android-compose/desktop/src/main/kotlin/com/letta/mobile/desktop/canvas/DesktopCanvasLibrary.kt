package com.letta.mobile.desktop.canvas

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.letta.mobile.data.canvas.CanvasConversationOptions
import com.letta.mobile.data.canvas.CanvasCreateOptions
import com.letta.mobile.data.canvas.CanvasDocument
import com.letta.mobile.data.canvas.CanvasId
import com.letta.mobile.data.canvas.CanvasSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The desktop's list of every canvas, plus the two ways a canvas becomes the active session
 * (open an existing one, or start a fresh one). Canvases are shared across agents and
 * conversations, so the library is not filtered by the focused agent.
 *
 * Plain class on purpose: the shell only collects [documents] and forwards the callbacks, and
 * the behaviour is testable without composing anything.
 */
internal class DesktopCanvasLibrary(
    private val store: DesktopCanvasDocumentStore,
    private val scope: CoroutineScope,
) {
    private val _documents = MutableStateFlow<List<CanvasDocument>>(emptyList())
    val documents: StateFlow<List<CanvasDocument>> = _documents.asStateFlow()

    fun refresh() {
        scope.launch { refreshNow() }
    }

    fun open(id: CanvasId, onReady: (CanvasSession) -> Unit) {
        scope.launch {
            val session = CanvasSession.open(store, id, hostOptions()) ?: return@launch
            onReady(session)
        }
    }

    fun createNew(agentId: String?, onReady: (CanvasSession) -> Unit) {
        scope.launch {
            val ordinal = store.listAll().size + 1
            val session = CanvasSession.create(
                store = store,
                options = CanvasCreateOptions(
                    title = "Canvas $ordinal",
                    agentId = agentId,
                    opLog = DesktopCanvasHostSync.opLog,
                    syncTransport = DesktopCanvasHostSync.syncTransport,
                ),
            )
            refreshNow()
            onReady(session)
        }
    }

    private suspend fun refreshNow() {
        _documents.value = store.listAll()
    }

    private fun hostOptions() = CanvasConversationOptions(
        opLog = DesktopCanvasHostSync.opLog,
        syncTransport = DesktopCanvasHostSync.syncTransport,
    )
}

@Composable
internal fun rememberDesktopCanvasLibrary(
    store: DesktopCanvasDocumentStore,
    scope: CoroutineScope,
): DesktopCanvasLibrary = remember(store, scope) { DesktopCanvasLibrary(store, scope) }
