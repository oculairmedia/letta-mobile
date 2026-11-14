package com.letta.mobile.ui.shell.workspace

import com.letta.mobile.data.voice.session.VoiceChatSessions
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.unit.Dp
import com.letta.mobile.data.canvas.CanvasConversationOptions
import com.letta.mobile.data.canvas.CanvasDocumentStore
import com.letta.mobile.data.canvas.CanvasOpLog
import com.letta.mobile.data.canvas.CanvasPresenceTransport
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.CanvasSyncTransport
import com.letta.mobile.data.canvas.InMemoryCanvasOpLog
import com.letta.mobile.data.canvas.mount.client.ConversationCanvasTarget
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.ui.canvas.boards.LocalCanvasBoardSessions
import com.letta.mobile.ui.canvas.mount.ConversationCanvasFrame
import com.letta.mobile.util.runCatchingCancellable
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
import com.letta.mobile.ui.chat.subagents.SubagentDualViewBinding
import com.letta.mobile.ui.chat.subagents.SubagentDualViewFrame
import com.letta.mobile.ui.chat.subagents.SubagentDualViewOptions
import com.letta.mobile.ui.chat.surface.ChatCanvasActions
import com.letta.mobile.ui.chat.surface.ChatCanvasPlaceholder
import com.letta.mobile.ui.chat.surface.ChatSurface
import com.letta.mobile.ui.chat.surface.ChatSurfaceAppearance
import com.letta.mobile.ui.chat.surface.ChatSurfacePlatform
import com.letta.mobile.ui.chat.surface.LocalChatCanvasOnBoard
import com.letta.mobile.ui.chat.surface.recents.ChatRecentInteractions
import com.letta.mobile.ui.shell.LocalShellFloatingChromeInset
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LettaSpinner
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
    /**
     * The board on show, so the host can route its own opens of it to this page (desktop's dock); null when it
     * leaves, and while a mounted board waits under the chat page (it is not on show until canvas mode).
     */
    val onBoardShown: ((CanvasId?) -> Unit)? = null,
    /** The conversation's subagent dual view; null draws the page alone. */
    val subagentViews: SubagentDualViewBinding? = null,
    /** Opens the chat session voice mode talks through; null leaves voice mode off the chat bar. */
    val voiceSessions: VoiceChatSessions? = null,
)

/**
 * The one conversation destination desktop and web mount. The
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
    // The page draws under the workspace's floating chrome and rests its content below it.
    val platform = look.platform.underFloatingChrome(LocalShellFloatingChromeInset.current)
    // letta-mobile-zxk2n.8: a subagent's conversation opens beside this one (in its place on a narrow page), read-only.
    val dualViewOptions = SubagentDualViewOptions(look.appearance, page.host, platform.topChromeInset)
    SubagentDualViewFrame(look.subagentViews, modifier, dualViewOptions) { parentModifier ->
        ParentChatPage(ParentChatInputs(page, presentation, look, platform), parentModifier)
    }
}

private class ParentChatInputs(
    val page: ShellChatPage,
    val presentation: ShellChatPresentation,
    val look: ShellChatLook,
    /** The look's platform with the floating chrome folded in. */
    val platform: ChatSurfacePlatform,
)

/** The conversation's own page: the shared chat surface over its board. */
@Composable
private fun ParentChatPage(inputs: ParentChatInputs, modifier: Modifier) {
    val page = inputs.page
    val presentation = inputs.presentation
    val look = inputs.look
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
        platform = inputs.platform,
        pagedTimeline = look.pagedTimeline,
        canvas = { actions ->
            if (page.owner.conversationId != null) {
                // The board the conversation shows: its mount when the host has one, else its own.
                ConversationCanvasFrame(page.owner.mountTarget()) { mounted ->
                    ConversationBoard(BoardInputs(page, look, inputs.platform.topChromeInset, mounted), actions)
                }
            } else {
                ChatCanvasPlaceholder()
            }
        },
        dockGeometry = dockGeometry,
        onDockGeometryChange = { dockGeometry = it },
        recents = look.recents,
        voiceSessions = look.voiceSessions,
    )
}

/** Escape on the full-screen page goes back to the board (Android's Back does the same). */
fun escapeCollapsesToCanvas(mode: ChatSurfaceMode, key: Key, type: KeyEventType): Boolean =
    mode == ChatSurfaceMode.FullScreen && key == Key.Escape && type == KeyEventType.KeyDown

/** The conversation the owner's board belongs to, for its mount; null for a new chat. */
private fun ShellCanvasOwner.mountTarget(): ConversationCanvasTarget? =
    ConversationCanvasTarget.of(agentId?.let(::AgentId), conversationId?.let(::ConversationId))

/**
 * The board on show: the [mounted] canvas held through the window's boards, or else (no mount, no
 * window boards, or a mount this device cannot open) the conversation's own board, created on
 * first open. None for a chat that has no conversation yet.
 */
@Composable
private fun rememberConversationBoard(board: ShellChatBoard, owner: ShellCanvasOwner, mounted: CanvasId?): CanvasSession? {
    val title = stringResource(
        Res.string.shell_chat_canvas_title,
        owner.agentName.ifBlank { stringResource(Res.string.shell_chat_canvas_owner_fallback) },
    )
    val boards = LocalCanvasBoardSessions.current
    val session by produceState<CanvasSession?>(null, board, owner.conversationId, owner.agentId, mounted) {
        value = null
        val conversation = owner.conversationId ?: return@produceState
        val held = mounted?.let { canvas -> boards?.let { runCatchingCancellable { it.acquire(canvas) }.getOrNull() } }
        value = held ?: CanvasSession.getOrCreateForConversation(
            store = board.store,
            conversationId = ConversationId(conversation),
            options = CanvasConversationOptions(
                agentId = owner.agentId,
                title = title,
                opLog = board.opLog,
                syncTransport = board.syncTransport,
            ),
        )
        if (held != null) awaitDispose { boards?.release(held) }
    }
    return session
}

private class BoardInputs(val page: ShellChatPage, val look: ShellChatLook, val chromeTopInset: Dp, val mounted: CanvasId?)

@Composable
private fun ConversationBoard(inputs: BoardInputs, actions: ChatCanvasActions) {
    val board = inputs.page.board
    val session = rememberConversationBoard(board, inputs.page.owner, inputs.mounted)
    // A mounted board under the chat page is not on show: the host may show it in its side pane
    // (the same live session, through the window's boards) until canvas mode puts it on show.
    val onBoard = LocalChatCanvasOnBoard.current
    val shown = session?.canvasId?.takeIf { inputs.mounted == null || onBoard }
    inputs.look.onBoardShown?.let { report ->
        DisposableEffect(report, shown) {
            report(shown)
            onDispose { report(null) }
        }
    }
    if (session == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LettaSpinner(size = LettaDimens.Control.spinnerLg) }
        return
    }
    CanvasWorkspace(
        session = session,
        presenceTransport = board.presence,
        assets = board.assets,
        // The canvas is the page: no title bar or back arrow, just its actions pill.
        onNavigateBack = null,
        onShareToChat = actions::shareToChat,
        showTitle = false,
        headerTrailing = inputs.look.canvasHeaderTrailing,
        modifier = Modifier.fillMaxSize(),
        // A chat card's "Show on canvas" frames its artifact here (letta-mobile-bglj6.13).
        cameraRequest = actions.camera,
        // The host's chrome over the board's top edge (the phone's status bar and header).
        chromeTopInset = inputs.chromeTopInset,
    )
}
