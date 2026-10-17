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
import com.letta.mobile.data.canvas.CanvasPresenceTransport
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.ui.canvas.CanvasWorkspace
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfaceIntent
import com.letta.mobile.ui.chat.session.ChatSurfaceModeReducer
import com.letta.mobile.ui.chat.session.ChatSurfacePresentation
import com.letta.mobile.ui.chat.session.SubagentViewSessions
import com.letta.mobile.ui.chat.session.TimelineChatTarget
import com.letta.mobile.ui.chat.surface.ChatCanvasActions
import com.letta.mobile.ui.chat.surface.ChatSurface
import com.letta.mobile.ui.chat.surface.rememberChatDockGeometry
import com.letta.mobile.ui.chat.subagents.SubagentDualViewBinding
import com.letta.mobile.ui.chat.subagents.SubagentDualViewFrame
import com.letta.mobile.ui.chat.subagents.SubagentDualViewHolder
import com.letta.mobile.ui.chat.subagents.SubagentDualViewOptions
import com.letta.mobile.ui.chat.subagents.rememberSubagentDualViewHolder
import com.letta.mobile.ui.chat.subagents.withSubagentDualView

/**
 * letta-mobile-o4ygk.4.5: the web's conversation page, the shared [ChatSurface] in the Pointer
 * idiom, as desktop draws it. It opens on the conversation's canvas with the chat docked under it
 * when [WebChatCanvas.openOnCanvas] is on (the default), else as the full-screen chat; expanding and "Open canvas"
 * move between the two.
 *
 * The canvas is the shared [CanvasWorkspace] over a [CanvasSession] from [WebChatCanvas.store], kept in the browser (ckmub.17.7)
 * and synced with edits and cursors through [WebChatCanvas.relay] (ckmub.17.9). The web has no mascot renderer: the agent orb shows.
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
    var dockGeometry by rememberChatDockGeometry(canvas.dockGeometry)
    val session = rememberConversationCanvas(canvas, port.target)
    val dualView = rememberWebSubagentDualView(port, navigation.subagentSessions)
    val host = rememberWebChatSurfaceHost(navigation, dualView?.holder)
    // A dispatched subagent's conversation opens beside this one (in its place on a narrow window), read-only.
    SubagentDualViewFrame(dualView, modifier, SubagentDualViewOptions(host = host)) { parentModifier ->
        ChatSurface(
            port = port,
            presentation = presentation,
            onIntent = { intent: ChatSurfaceIntent -> presentation = ChatSurfaceModeReducer.reduce(presentation, intent) },
            host = host,
            modifier = parentModifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            canvas = { actions -> ConversationCanvas(session, canvas.relay, actions) },
            dockGeometry = dockGeometry,
            onDockGeometryChange = { dockGeometry = it },
        )
    }
}

/** The conversation's subagent dual view, closed again for each conversation; null without [sessions]. */
@Composable
private fun rememberWebSubagentDualView(port: WebChatSessionPort, sessions: SubagentViewSessions?): SubagentDualViewBinding? {
    val holder = rememberSubagentDualViewHolder(ConversationId(port.target.conversationId))
    return remember(holder, sessions) { sessions?.let { SubagentDualViewBinding(holder, it) } }
}

/** The conversation's own board, created on first open in the canvas's store. */
@Composable
private fun rememberConversationCanvas(canvas: WebChatCanvas, target: TimelineChatTarget): CanvasSession? {
    val session by produceState<CanvasSession?>(null, canvas, target.conversationId) {
        value = CanvasSession.getOrCreateForConversation(
            store = canvas.store,
            conversationId = ConversationId(target.conversationId),
            options = CanvasConversationOptions(target.agentId, "Canvas (${target.agentName})", canvas.opLog, syncTransport = canvas.relay),
        )
    }
    return session
}

@Composable
private fun ConversationCanvas(session: CanvasSession?, presence: CanvasPresenceTransport, actions: ChatCanvasActions) {
    if (session == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    CanvasWorkspace(
        session = session, presenceTransport = presence,
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
private fun rememberWebChatSurfaceHost(navigation: WebChatPageNavigation, subagentViews: SubagentDualViewHolder?): ChatSurfaceHost {
    val latest by rememberUpdatedState(navigation)
    val agentNames = navigation.agentNamesById
    return remember(agentNames, subagentViews) {
        val host = ChatSurfaceHost(
            resolveAgentName = agentNames::get,
            openAgentPane = { latest.openAgentPane() },
        )
        // The web keeps no subagent registry: a row opens the child agent it names, in its default conversation.
        subagentViews?.let { host.withSubagentDualView(it) { emptyList() } } ?: host
    }
}
