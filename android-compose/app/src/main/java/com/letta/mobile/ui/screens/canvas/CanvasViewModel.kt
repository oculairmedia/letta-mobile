package com.letta.mobile.ui.screens.canvas

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.canvas.CanvasPresenceTransport
import com.letta.mobile.data.canvas.library.CanvasBoardSessions
import com.letta.mobile.data.canvas.library.CanvasPage
import com.letta.mobile.data.canvas.library.CanvasPageHold
import com.letta.mobile.data.canvas.library.CanvasPageState
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.plugin.view.PluginViewsModel
import com.letta.mobile.data.storage.AssetStore
import com.letta.mobile.ui.canvas.plugin.CanvasDocumentViewerRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Binds the shared page lifecycle to a navigation destination's Android ViewModel scope. */
@HiltViewModel
class CanvasViewModel @Inject constructor(
    /** The host's presence transport. */
    val presenceTransport: CanvasPresenceTransport,
    /** Assets shared with the relay. */
    val assets: AssetStore,
    /** Enabled plugin view services. */
    val pluginViews: PluginViewsModel,
    /** Trusted notebook document viewers. */
    val viewers: CanvasDocumentViewerRegistry,
    owner: AndroidCanvasBoards,
) : ViewModel(), CanvasPage {
    private val page = CanvasPageHold(owner, viewModelScope)
    override val state get() = page.state
    override fun open(canvasId: CanvasId?, conversationId: ConversationId?, agentId: AgentId?) =
        page.open(canvasId, conversationId, agentId)
    override fun retry() = page.retry()
    override fun stateFor(boards: CanvasBoardSessions, observed: CanvasPageState) = page.stateFor(boards, observed)
    override fun close() = page.close()
    override fun onCleared() { close(); super.onCleared() }
}
