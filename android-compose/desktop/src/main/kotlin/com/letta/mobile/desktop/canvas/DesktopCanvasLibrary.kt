package com.letta.mobile.desktop.canvas

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.letta.mobile.data.canvas.CanvasArchiveStore
import com.letta.mobile.data.canvas.CanvasConversationOptions
import com.letta.mobile.data.canvas.CanvasCreateOptions
import com.letta.mobile.data.canvas.CanvasDocument
import com.letta.mobile.data.canvas.CanvasId
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.InMemoryCanvasArchiveStore
import com.letta.mobile.util.Telemetry
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
    private val archive: CanvasArchiveStore = InMemoryCanvasArchiveStore(),
) {
    private val _documents = MutableStateFlow<List<CanvasDocument>>(emptyList())
    val documents: StateFlow<List<CanvasDocument>> = _documents.asStateFlow()

    private val _archived = MutableStateFlow<Set<CanvasId>>(emptySet())

    /** Canvases set aside from the everyday list; see [CanvasArchiveStore]. */
    val archived: StateFlow<Set<CanvasId>> = _archived.asStateFlow()

    /** Archives [id], or restores it when [archived] is false. */
    fun setArchived(id: CanvasId, archived: Boolean) {
        scope.launch {
            // A disk failure leaves the list as it was rather than ending the library's scope.
            try {
                archive.setArchived(id, archived)
                _archived.value = archive.archivedIds()
            } catch (e: java.io.IOException) {
                Telemetry.error("DesktopCanvasLibrary", "archive.updateFailed", e)
            }
        }
    }

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
        _archived.value = try {
            archive.archivedIds()
        } catch (e: java.io.IOException) {
            _archived.value
        }
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
): DesktopCanvasLibrary = remember(store, scope) { DesktopCanvasLibrary(store, scope, DesktopCanvasArchiveStore()) }

/** The conversation list's archive filter, as it applies to the canvas library. */
internal fun com.letta.mobile.desktop.chat.ConversationArchiveFilter.toCanvasArchiveFilter(): com.letta.mobile.data.canvas.CanvasArchiveFilter =
    when (this) {
        com.letta.mobile.desktop.chat.ConversationArchiveFilter.Active -> com.letta.mobile.data.canvas.CanvasArchiveFilter.ACTIVE
        com.letta.mobile.desktop.chat.ConversationArchiveFilter.Archived -> com.letta.mobile.data.canvas.CanvasArchiveFilter.ARCHIVED
        com.letta.mobile.desktop.chat.ConversationArchiveFilter.All -> com.letta.mobile.data.canvas.CanvasArchiveFilter.ALL
    }
