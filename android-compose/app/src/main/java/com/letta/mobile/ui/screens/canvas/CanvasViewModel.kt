package com.letta.mobile.ui.screens.canvas

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.canvas.CanvasPresenceTransport
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.library.CanvasBoardSessions
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.plugin.view.PluginViewsModel
import com.letta.mobile.data.storage.AssetStore
import com.letta.mobile.ui.canvas.plugin.CanvasDocumentViewerRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Retains one page hold across Activity recreation; its destination or backend owns the lifetime. */
@HiltViewModel
class CanvasViewModel @Inject constructor(
    /** The host's canvas presence transport. */
    val presenceTransport: CanvasPresenceTransport,
    /** The app's canvas assets, shared with the relay (see AppModule.provideCanvasAssetStore). */
    val assets: AssetStore,
    /** The enabled plugins, their view transport and this device's live-views switch. */
    val pluginViews: PluginViewsModel,
    /** The same trusted notebook viewers used by canvas pages and shell note modules. */
    val viewers: CanvasDocumentViewerRegistry,
    private val owner: AndroidCanvasBoards,
) : ViewModel() {
    private val shown = MutableStateFlow<CanvasSession?>(null)
    private var opening: Job? = null
    private var held: Pair<CanvasBoardSessions, CanvasSession>? = null

    /** The retained page session, replaced only when this page's backend owner changes. */
    val session = shown.asStateFlow()

    /** Binds this destination once, generating an untitled board id only once for its lifetime. */
    fun open(canvasId: CanvasId?, conversationId: ConversationId?, agentId: AgentId?) {
        if (opening != null) return
        val pageId = canvasId ?: if (conversationId == null) CanvasId.generate() else null
        opening = viewModelScope.launch {
            owner.boards.collectLatest { boards ->
                val opened = boards.acquirePage(pageId, conversationId, agentId)
                held = boards to opened
                shown.value = opened
                try {
                    awaitCancellation()
                } finally {
                    release()
                }
            }
        }
    }

    /** Rejects a stale page while the root and this ViewModel observe an owner change independently. */
    internal fun owns(boards: CanvasBoardSessions, candidate: CanvasSession): Boolean =
        held?.let { it.first === boards && it.second === candidate } == true

    override fun onCleared() {
        release()
        super.onCleared()
    }

    private fun release() {
        val previous = held ?: return
        held = null
        shown.value = null
        previous.first.release(previous.second)
    }
}
