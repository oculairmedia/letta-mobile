package com.letta.mobile.ui.screens.canvas

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.canvas.library.CanvasPageState
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.plugin.view.ViewPlatform
import com.letta.mobile.pluginview.ProvidePluginViews
import com.letta.mobile.pluginview.WebViewPluginViewHost
import com.letta.mobile.ui.canvas.CanvasWorkspace
import com.letta.mobile.ui.canvas.plugin.ProvideCanvasDocumentViewerRegistry
import com.letta.mobile.ui.canvas.boards.LocalCanvasBoardSessions

/** Activity canvas binding, kept separate so its navigation and page lifetimes can be exercised. */
@Composable
internal fun AndroidCanvasHost(
    deps: com.letta.mobile.MainActivityDependencies,
    config: com.letta.mobile.data.model.LettaConfig?,
    content: @Composable () -> Unit,
) {
    val owner = deps.canvasOwner
    LaunchedEffect(owner, config?.id) { owner.updateBackend(config) }
    val context by owner.context.collectAsStateWithLifecycle()
    // Which board each conversation shows and the side menu's pick, over the window's mounts (this
    // device's until the host's mount client is wired). A new backend starts the picker over.
    val picker = remember(context.generation) {
        com.letta.mobile.data.canvas.mount.client.ConversationCanvasPicker(
            com.letta.mobile.data.canvas.mount.client.ConversationCanvasResolver(owner.mounts),
        )
    }
    androidx.compose.runtime.CompositionLocalProvider(
        com.letta.mobile.ui.canvas.boards.LocalCanvasBoardGeneration provides context.generation,
    ) {
        com.letta.mobile.ui.canvas.boards.ProvideCanvasHostWithBoards(deps.canvasViewers, context.boards, picker, content = content)
    }
}

@Composable
internal fun rememberCanvasPageState(
    boards: com.letta.mobile.data.canvas.library.CanvasBoardSessions,
    viewModel: CanvasViewModel,
    canvasId: CanvasId?,
    conversationId: ConversationId?,
    agentId: AgentId?,
): CanvasPageState {
    LaunchedEffect(boards, viewModel, canvasId, conversationId, agentId) {
        viewModel.open(canvasId, conversationId, agentId)
    }
    val observed by viewModel.state.collectAsStateWithLifecycle()
    return viewModel.stateFor(boards, observed)
}

/** Android host observing the shared destination lifecycle and rendering [CanvasWorkspace] when ready. */
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
    val state = rememberCanvasPageState(
        boards, viewModel, canvasId.takeIf(String::isNotBlank)?.let(::CanvasId),
        conversationId?.takeIf(String::isNotBlank)?.let(::ConversationId), agentId?.let(::AgentId),
    )
    when (state) {
        is CanvasPageState.Ready -> {
            val activeSession = state.session
            // The ViewModel's scope lets live plugin teardown finish after a page is removed.
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
        }
        is CanvasPageState.Failed -> com.letta.mobile.ui.canvas.CanvasWorkspaceLoadFailure(Modifier.fillMaxSize(), viewModel::retry)
        CanvasPageState.Closed -> {
            LaunchedEffect(Unit) { onNavigateBack?.invoke() }
            com.letta.mobile.ui.chat.surface.ChatCanvasPlaceholder()
        }
        CanvasPageState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    }
}
