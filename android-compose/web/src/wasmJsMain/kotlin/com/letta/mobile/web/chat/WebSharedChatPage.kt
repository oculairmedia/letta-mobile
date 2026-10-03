package com.letta.mobile.web.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.letta.mobile.data.canvas.CanvasConversationOptions
import com.letta.mobile.data.canvas.CanvasDocumentStore
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.ui.canvas.CanvasWorkspace
import com.letta.mobile.ui.chat.session.ChatDockGeometry
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfaceIntent
import com.letta.mobile.ui.chat.session.ChatSurfaceModeReducer
import com.letta.mobile.ui.chat.session.ChatSurfacePresentation
import com.letta.mobile.ui.chat.session.TimelineChatTarget
import com.letta.mobile.ui.chat.surface.ChatCanvasActions
import com.letta.mobile.ui.chat.surface.ChatSurface
import com.letta.mobile.web.WebChatCanvas

/** Where the page's host affordances go in the web shell. */
internal data class WebChatPageNavigation(
    /** The agent's mascot beside the composer: show the agent list (the sidebar). */
    val openAgentPane: () -> Unit,
    /** Agent display names for provenance labels. */
    val agentNamesById: Map<String, String> = emptyMap(),
)

/**
 * letta-mobile-o4ygk.4.5: the web's conversation page, the shared [ChatSurface] in the Pointer
 * idiom, as desktop draws it. It opens on the conversation's canvas with the chat docked under it
 * when [WebChatCanvas.openOnCanvas] is on (the default), else as the full-screen chat; expanding and "Open canvas"
 * move between the two.
 *
 * The canvas is the shared [CanvasWorkspace] over an in-memory [CanvasSession] from [WebChatCanvas.store]:
 * it lasts while the tab does, with no persistence or sync to other devices yet
 * (letta-mobile-o4ygk.4.6). The mascot
 * companion is off because the web has no mascot renderer, so the page shows the agent orb.
 */
@Composable
internal fun WebSharedChatPage(
    port: WebChatSessionPort,
    canvas: WebChatCanvas,
    navigation: WebChatPageNavigation,
    modifier: Modifier = Modifier,
) {
    var presentation by remember(port) {
        mutableStateOf(ChatSurfacePresentation.initial(openOnCanvas = canvas.openOnCanvas, hasCanvas = true))
    }
    var dockGeometry by remember { mutableStateOf(ChatDockGeometry.Default) }
    val session = rememberConversationCanvas(canvas.store, port.target)
    ChatSurface(
        port = port,
        presentation = presentation,
        onIntent = { intent: ChatSurfaceIntent -> presentation = ChatSurfaceModeReducer.reduce(presentation, intent) },
        host = rememberWebChatSurfaceHost(navigation),
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        canvas = { actions -> ConversationCanvas(session, actions) },
        dockGeometry = dockGeometry,
        onDockGeometryChange = { dockGeometry = it },
    )
}

/** The conversation's own board, created on first open in [store]. */
@Composable
private fun rememberConversationCanvas(store: CanvasDocumentStore, target: TimelineChatTarget): CanvasSession? {
    val session by produceState<CanvasSession?>(null, store, target.conversationId) {
        value = CanvasSession.getOrCreateForConversation(
            store = store,
            conversationId = target.conversationId,
            options = CanvasConversationOptions(agentId = target.agentId, title = "Canvas (${target.agentName})"),
        )
    }
    return session
}

@Composable
private fun ConversationCanvas(session: CanvasSession?, actions: ChatCanvasActions) {
    if (session == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    CanvasWorkspace(
        session = session,
        // The canvas is the page: no title bar or back arrow, just its actions pill.
        onNavigateBack = null,
        onShareToChat = actions::shareToChat,
        showTitle = false,
        modifier = Modifier.fillMaxSize(),
        // A chat card's "Show on canvas" frames its artifact here.
        cameraRequest = actions.camera,
    )
}

/** One host instance that forwards to the latest navigation, so timeline rows stay skippable. */
@Composable
private fun rememberWebChatSurfaceHost(navigation: WebChatPageNavigation): ChatSurfaceHost {
    val latest by rememberUpdatedState(navigation)
    val agentNames = navigation.agentNamesById
    return remember(agentNames) {
        ChatSurfaceHost(
            resolveAgentName = agentNames::get,
            openAgentPane = { latest.openAgentPane() },
        )
    }
}
