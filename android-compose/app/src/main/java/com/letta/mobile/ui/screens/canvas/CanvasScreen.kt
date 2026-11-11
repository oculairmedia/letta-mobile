package com.letta.mobile.ui.screens.canvas

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.canvas.CanvasPresenceTransport
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.plugin.view.PluginViewsModel
import com.letta.mobile.data.plugin.view.ViewPlatform
import com.letta.mobile.pluginview.ProvidePluginViews
import com.letta.mobile.pluginview.WebViewPluginViewHost
import com.letta.mobile.ui.canvas.CanvasWorkspace
import com.letta.mobile.ui.canvas.plugin.CanvasDocumentViewerRegistry
import com.letta.mobile.ui.canvas.plugin.ProvideCanvasDocumentViewerRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import com.letta.mobile.ui.canvas.boards.LocalCanvasBoardSessions
import kotlinx.coroutines.awaitCancellation
import javax.inject.Inject

/** Android services used by each [CanvasScreen] mount's window-owned [CanvasSession]. */
@HiltViewModel
class CanvasViewModel @Inject constructor(
    val presenceTransport: CanvasPresenceTransport,
    /** The app's canvas assets, shared with the relay (see AppModule.provideCanvasAssetStore). */
    val assets: com.letta.mobile.data.storage.AssetStore,
    /** The host's enabled plugins, their view transport and this device's live-views switch (letta-mobile-cdgpr.8). */
    val pluginViews: PluginViewsModel,
    val viewers: CanvasDocumentViewerRegistry,
) : ViewModel()

/** Production Android host of a mount-held, persistent [CanvasSession] in [CanvasWorkspace]. */
@Composable
fun CanvasScreen(
    canvasId: String,
    conversationId: String? = null,
    agentId: String? = null,
    onNavigateBack: (() -> Unit)?,
    onShareToChat: ((ByteArray, String) -> Unit)? = null,
    viewModel: CanvasViewModel = hiltViewModel(),
    /** False when the canvas is the page itself (under the shared chat): no title bar or back. */
    showTitle: Boolean = true,
    /** Host chrome floating over the board's top edge; see [CanvasWorkspace]. */
    chromeTopInset: Dp = 0.dp,
    /** The shared chat's "Show on canvas" camera requests (letta-mobile-bglj6.13); null elsewhere. */
    cameraRequest: com.letta.mobile.ui.canvas.CanvasCameraRequest? = null,
) {
    val boards = checkNotNull(LocalCanvasBoardSessions.current)
    val session by produceState<CanvasSession?>(null, boards, canvasId, conversationId, agentId) {
        value = null
        val opened = boards.acquirePage(
            canvasId.takeIf(String::isNotBlank)?.let(::CanvasId),
            conversationId?.takeIf(String::isNotBlank)?.let(::ConversationId),
            agentId?.let(::AgentId),
        )
        try {
            value = opened
            awaitCancellation()
        } finally {
            boards.release(opened)
        }
    }
    val activeSession = session
    if (activeSession != null) {
        // Live plugin pages (letta-mobile-s416w.13): the WebView host, with the host's enabled
        // plugins and view transport from the shared model (letta-mobile-cdgpr.8). With live views
        // off on this device, or no plugins on the host, every plugin element keeps its fallback card.
        // The view model's scope outlives a removed element, so a page's teardown can finish.
        val pluginHost = remember(viewModel) { WebViewPluginViewHost(viewModel.viewModelScope) }
        ProvidePluginViews(viewModel.pluginViews, pluginHost, activeSession.canvasId.value, ViewPlatform.ANDROID) {
          ProvideCanvasDocumentViewerRegistry(viewModel.viewers) {
            CanvasWorkspace(
                session = activeSession,
                presenceTransport = viewModel.presenceTransport,
                assets = viewModel.assets,
                onNavigateBack = onNavigateBack,
                onShareToChat = onShareToChat,
                showTitle = showTitle,
                chromeTopInset = chromeTopInset,
                cameraRequest = cameraRequest,
            )
          }
        }
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}
