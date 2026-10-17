package com.letta.mobile.ui.shell.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import com.letta.mobile.data.canvas.CanvasConversationOptions
import com.letta.mobile.data.canvas.CanvasDocumentStore
import com.letta.mobile.data.canvas.CanvasOpLog
import com.letta.mobile.data.canvas.CanvasPresenceTransport
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.CanvasSyncTransport
import com.letta.mobile.data.canvas.InMemoryCanvasOpLog
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.storage.AssetStore
import com.letta.mobile.data.timeline.CanonicalTimelinePresentation
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.shell_chat_canvas_owner_fallback
import com.letta.mobile.sharedui.resources.shell_chat_canvas_title
import com.letta.mobile.ui.canvas.CanvasWorkspace
import com.letta.mobile.ui.chat.session.ChatDockGeometry
import com.letta.mobile.ui.chat.session.ChatSessionPort
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfaceIntent
import com.letta.mobile.ui.chat.session.ChatSurfaceMode
import com.letta.mobile.ui.chat.session.ChatSurfaceModeReducer
import com.letta.mobile.ui.chat.session.ChatSurfacePresentation
import com.letta.mobile.ui.chat.surface.ChatCanvasActions
import com.letta.mobile.ui.chat.surface.ChatCanvasPlaceholder
import com.letta.mobile.ui.chat.surface.ChatSurface
import com.letta.mobile.ui.chat.surface.ChatSurfaceAppearance
import com.letta.mobile.ui.chat.surface.ChatSurfacePlatform
import com.letta.mobile.ui.chat.surface.recents.ChatRecentInteractions
import org.jetbrains.compose.resources.stringResource

/** Where a conversation's board is kept, and how its edits and presence reach other devices. */
data class ShellChatBoard(
    val store: CanvasDocumentStore,
    val opLog: CanvasOpLog = InMemoryCanvasOpLog(),
    val syncTransport: CanvasSyncTransport? = null,
    val presence: CanvasPresenceTransport? = null,
    /** Where the board keeps its images; null keeps them inline. */
    val assets: AssetStore? = null,
)

/** Whose board it is: the open conversation (none yet for a new chat) and its agent. */
data class ShellCanvasOwner(
    val conversationId: String?,
    val agentId: String?,
    val agentName: String,
)

/** Where the chat page is drawn (docked under its board, or full screen), as the person moves it. */
@Stable
class ShellChatPresentation(initial: ChatSurfacePresentation) {
    var value: ChatSurfacePresentation by mutableStateOf(initial)
        private set

    fun onIntent(intent: ChatSurfaceIntent) {
        value = ChatSurfaceModeReducer.reduce(value, intent)
    }
}

/** The page's presentation, starting on the board when [openOnCanvas]; a new [session] starts it over. */
@Composable
fun rememberShellChatPresentation(openOnCanvas: Boolean, session: ChatSessionPort? = null): ShellChatPresentation =
    remember(session) { ShellChatPresentation(ChatSurfacePresentation.initial(openOnCanvas = openOnCanvas, hasCanvas = true)) }

/** One conversation's page: its session port, the host's navigation, its board and the dock's placement. */
class ShellChatPage(
    val port: ChatSessionPort,
    val host: ChatSurfaceHost,
    val board: ShellChatBoard,
    val owner: ShellCanvasOwner,
    /** Where the person put the docked panel; null until the saved placement is read (no dock until then). */
    val dockGeometry: MutableState<ChatDockGeometry?>,
)

/** The host's look for the page: its appearance and platform pieces, and what the board's header ends with. */
class ShellChatLook(
    val appearance: ChatSurfaceAppearance = ChatSurfaceAppearance(),
    val platform: ChatSurfacePlatform = ChatSurfacePlatform.Default,
    val pagedTimeline: CanonicalTimelinePresentation? = null,
    val recents: ChatRecentInteractions? = null,
    val canvasHeaderTrailing: (@Composable () -> Unit)? = null,
    /** The board on show, so the host can route its own opens of it to this page (desktop's dock); null when it leaves. */
    val onBoardShown: ((CanvasId?) -> Unit)? = null,
)

/**
 * letta-mobile-c3np7.4.4 / ckmub.17.4: the one conversation destination desktop and web mount. The
 * conversation opens on its own board with the chat docked under it (or full screen); expanding,
 * "Open canvas" and Escape move between the two. A new chat has no board yet, so it shows the
 * placeholder rather than a shared default board. Hosts keep only their port and look.
 */
@Composable
fun ShellChatDestination(
    page: ShellChatPage,
    presentation: ShellChatPresentation,
    modifier: Modifier = Modifier,
    look: ShellChatLook = ShellChatLook(),
) {
    val session = rememberConversationBoard(page.board, page.owner)
    look.onBoardShown?.let { report ->
        DisposableEffect(report, session) {
            report(session?.canvasId)
            onDispose { report(null) }
        }
    }
    var dockGeometry by page.dockGeometry
    ChatSurface(
        port = page.port,
        presentation = presentation.value,
        onIntent = presentation::onIntent,
        host = page.host,
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Bubble phase, so an open image viewer or popup takes its own Escape first.
            .onKeyEvent { event ->
                val collapse = escapeCollapsesToCanvas(presentation.value.mode, event.key, event.type)
                if (collapse) presentation.onIntent(ChatSurfaceIntent.Collapse)
                collapse
            },
        appearance = look.appearance,
        platform = look.platform,
        pagedTimeline = look.pagedTimeline,
        canvas = { actions ->
            if (page.owner.conversationId != null) {
                ConversationBoard(BoardInputs(session, page.board, look), actions)
            } else {
                ChatCanvasPlaceholder()
            }
        },
        dockGeometry = dockGeometry,
        onDockGeometryChange = { dockGeometry = it },
        recents = look.recents,
    )
}

/** Escape on the full-screen page goes back to the board (Android's Back does the same). */
fun escapeCollapsesToCanvas(mode: ChatSurfaceMode, key: Key, type: KeyEventType): Boolean =
    mode == ChatSurfaceMode.FullScreen && key == Key.Escape && type == KeyEventType.KeyDown

/** The conversation's own board, created on first open; none for a chat that has no conversation yet. */
@Composable
private fun rememberConversationBoard(board: ShellChatBoard, owner: ShellCanvasOwner): CanvasSession? {
    val title = stringResource(
        Res.string.shell_chat_canvas_title,
        owner.agentName.ifBlank { stringResource(Res.string.shell_chat_canvas_owner_fallback) },
    )
    val session by produceState<CanvasSession?>(null, board, owner.conversationId, owner.agentId) {
        value = null
        val conversation = owner.conversationId ?: return@produceState
        value = CanvasSession.getOrCreateForConversation(
            store = board.store,
            conversationId = ConversationId(conversation),
            options = CanvasConversationOptions(
                agentId = owner.agentId,
                title = title,
                opLog = board.opLog,
                syncTransport = board.syncTransport,
            ),
        )
    }
    return session
}

private class BoardInputs(val session: CanvasSession?, val board: ShellChatBoard, val look: ShellChatLook)

@Composable
private fun ConversationBoard(inputs: BoardInputs, actions: ChatCanvasActions) {
    val session = inputs.session
    if (session == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    CanvasWorkspace(
        session = session,
        presenceTransport = inputs.board.presence,
        assets = inputs.board.assets,
        // The canvas is the page: no title bar or back arrow, just its actions pill.
        onNavigateBack = null,
        onShareToChat = actions::shareToChat,
        showTitle = false,
        headerTrailing = inputs.look.canvasHeaderTrailing,
        modifier = Modifier.fillMaxSize(),
        // A chat card's "Show on canvas" frames its artifact here (letta-mobile-bglj6.13).
        cameraRequest = actions.camera,
        // The host's chrome over the board's top edge (the phone's status bar and header).
        chromeTopInset = inputs.look.platform.topChromeInset,
    )
}
