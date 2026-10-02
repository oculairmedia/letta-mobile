package com.letta.mobile.desktop.chat

import com.letta.mobile.ui.chat.session.ChatDockGeometry
import androidx.compose.runtime.MutableState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.canvas.CanvasDocumentStore
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.timeline.CanonicalTimelinePresentation
import com.letta.mobile.desktop.LocalDesktopChatFontScale
import com.letta.mobile.desktop.LocalDesktopChatFontScaleSetter
import com.letta.mobile.desktop.LocalDesktopOpenChatsOnCanvas
import com.letta.mobile.desktop.MAX_CHAT_FONT_SCALE
import com.letta.mobile.desktop.MIN_CHAT_FONT_SCALE
import com.letta.mobile.desktop.OpenDesktopCanvasParams
import com.letta.mobile.desktop.canvas.DesktopCanvasHostSync
import com.letta.mobile.desktop.canvas.DesktopCanvasOwner
import com.letta.mobile.desktop.openDesktopCanvasSession
import com.letta.mobile.desktop.phone.ChatPageFrame
import com.letta.mobile.desktop.phone.DesktopPhoneChatFrame
import com.letta.mobile.desktop.phone.DesktopPhoneChrome
import com.letta.mobile.desktop.phone.LocalDesktopPhone
import com.letta.mobile.desktop.phone.PhoneChatPageInputs
import com.letta.mobile.desktop.phone.chatAppearance
import com.letta.mobile.desktop.phone.opensOnCanvas
import com.letta.mobile.ui.canvas.CanvasWorkspace
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
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.compose.rememberDirectoryPickerLauncher

/*
 * letta-mobile-bglj6.1 (stage 3, desktop): the Conversations destination rendered with the shared
 * KMP chat page instead of ChatDetailPane, behind DesktopSharedChatPageFlag.
 */

/**
 * The one [DesktopChatSessionPort] for [controller], alive as long as the controller is. Its flows
 * share in the composition's scope only while the page collects them (WhileSubscribed), so a
 * replaced controller's port stops with its last collector.
 */
@Composable
internal fun rememberDesktopChatSessionPort(
    controller: DesktopChatController,
    onA2uiAction: (A2uiAction) -> Unit,
): DesktopChatSessionPort {
    val latestA2uiAction by rememberUpdatedState(onA2uiAction)
    val latestFontScaleSetter by rememberUpdatedState(LocalDesktopChatFontScaleSetter.current)
    val compositionScope = rememberCoroutineScope()
    return remember(controller, compositionScope) {
        DesktopChatSessionPort(
            controller = controller,
            scope = compositionScope,
            bindings = DesktopChatSessionBindings(
                onA2uiAction = { latestA2uiAction(it) },
                onSetFontScale = { latestFontScaleSetter(it) },
            ),
        )
    }
}

/** Where the page's host affordances go; each is the old page's existing destination. */
internal data class DesktopSharedChatPageNavigation(
    /** Opens the canvas beside the conversation (the composer's canvas button today). */
    val openCanvas: () -> Unit,
    val openAgent: (agentId: String) -> Unit,
    val openModelPicker: () -> Unit,
    /** The composer companion mascot was clicked: bring back the agent pane (the sidebar). */
    val openAgentPane: (() -> Unit)? = null,
    /** The pencil on the mascot: open the selected agent's editor. */
    val editAgent: (() -> Unit)? = null,
    /** Agent display names for provenance labels (the roster); desktop has no subagent opener. */
    val agentNamesById: Map<String, String> = emptyMap(),
)

internal data class DesktopSharedChatPageState(
    val port: DesktopChatSessionPort,
    val pagedTimeline: CanonicalTimelinePresentation?,
    val hostInputs: DesktopChatComposerHostInputs,
    /** Drives the ambient glow, exactly as on the old page. */
    val isThinking: Boolean,
    val errorMessage: String?,
    /** Where the conversation's board lives, and whose it is: the docked canvas. */
    val canvasStore: CanvasDocumentStore,
    val canvasOwner: DesktopCanvasOwner,
    /** Routes the shell's canvas opens to this page's dock (one live session per board). */
    val dockedCanvas: DesktopDockedCanvasRouter? = null,
    /** The shell's own control drawn at the end of the canvas header (background tasks). */
    val canvasHeaderTrailing: (@Composable () -> Unit)? = null,
    /**
     * Where the person put the docked panel, remembered by the app (read once per session); null
     * until the saved placement is read, and the page draws no dock until then.
     */
    val dockGeometry: MutableState<ChatDockGeometry?>,
)

/**
 * letta-mobile-bglj6.1 (stage 4): the conversation opens on its canvas with the chat docked
 * under it; expanding gives the full-screen page, and swipe up or "Open canvas" returns to the
 * canvas. The canvas stays composed underneath (see ChatSurface).
 */
@Composable
internal fun DesktopSharedChatPage(
    state: DesktopSharedChatPageState,
    navigation: DesktopSharedChatPageNavigation,
    modifier: Modifier = Modifier,
) {
    val port = state.port
    SideEffect { port.updateHostInputs(state.hostInputs) }
    val openOnCanvas = LocalDesktopOpenChatsOnCanvas.current
    // The phone preview draws this page as Android's SharedChatPage does; null on the desktop.
    val phone = LocalDesktopPhone.current
    var presentation by remember {
        mutableStateOf(ChatSurfacePresentation.initial(phone.opensOnCanvas(openOnCanvas.enabled.value), hasCanvas = true))
    }
    val hasConversation = state.canvasOwner.conversationId != null
    val host = rememberDesktopChatSurfaceHost(port, navigation, phone)
    val ambientStatus = rememberDesktopAmbientStatus(state.isThinking, state.errorMessage)
    val session = rememberConversationCanvasSession(state.canvasStore, state.canvasOwner)
    state.dockedCanvas?.let { router ->
        DisposableEffect(router, session) {
            router.dockedCanvasId = session?.canvasId
            onDispose { router.dockedCanvasId = null }
        }
        LaunchedEffect(router, router.showRequests) {
            if (router.showRequests > 0) presentation = ChatSurfaceModeReducer.reduce(presentation, ChatSurfaceIntent.Collapse)
        }
    }
    var dockGeometry by state.dockGeometry
    val onIntent = remember { { intent: ChatSurfaceIntent -> presentation = ChatSurfaceModeReducer.reduce(presentation, intent) } }
    val frameInputs = PhoneChatPageInputs(presentation.mode, port.uiState) { onIntent(ChatSurfaceIntent.Collapse) }
    DesktopPhoneChatFrame(phone, frameInputs, modifier) { pageModifier, frame ->
        ChatSurface(
            port = port,
            presentation = presentation,
            onIntent = onIntent,
            host = host,
            modifier = pageModifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                // Bubble phase, so an open image viewer or popup takes its own Escape first.
                .onKeyEvent { event ->
                    val collapse = escapeCollapsesToCanvas(presentation.mode, event.key, event.type)
                    if (collapse) presentation = ChatSurfaceModeReducer.reduce(presentation, ChatSurfaceIntent.Collapse)
                    collapse
                },
            appearance = phone.chatAppearance(rememberDesktopChatAppearance()),
            platform = rememberDesktopChatPlatform(ambientStatus, frame),
            pagedTimeline = state.pagedTimeline,
            canvas = { actions ->
                if (hasConversation) {
                    DockedConversationCanvas(session, actions, state.canvasHeaderTrailing, frame.topChromeInset)
                } else {
                    ChatCanvasPlaceholder()
                }
            },
            dockGeometry = dockGeometry,
            onDockGeometryChange = { dockGeometry = it },
        )
    }
}

/** The desktop's appearance: its own font scale, already applied to the window's text through density. */
@Composable
private fun rememberDesktopChatAppearance(): ChatSurfaceAppearance {
    val fontScale = LocalDesktopChatFontScale.current
    return remember(fontScale) {
        ChatSurfaceAppearance(
            fontScale = fontScale,
            // The font-scale host already scales the window's text through density.
            fontScaleAppliedByHost = true,
            fontScaleRange = MIN_CHAT_FONT_SCALE..MAX_CHAT_FONT_SCALE,
        )
    }
}

/** The ambient glow behind the page, and what the host's [frame] asks of it (the phone's top chrome). */
@Composable
private fun rememberDesktopChatPlatform(ambientStatus: DesktopAmbientStatus, frame: ChatPageFrame): ChatSurfacePlatform =
    remember(ambientStatus, frame) {
        ChatSurfacePlatform(
            pageBackground = { content ->
                DesktopAmbientChatBackground(status = ambientStatus, modifier = Modifier.fillMaxSize()) { content() }
            },
            showKeyboardHints = frame.showKeyboardHints,
            topChromeInset = frame.topChromeInset,
        )
    }

/** Escape on the full-screen page goes back to the canvas (Android's Back does the same). */
internal fun escapeCollapsesToCanvas(mode: ChatSurfaceMode, key: Key, type: KeyEventType): Boolean =
    mode == ChatSurfaceMode.FullScreen && key == Key.Escape && type == KeyEventType.KeyDown

/** The selected conversation's own board, created on first open (the side pane's same session). */
@Composable
private fun rememberConversationCanvasSession(
    store: CanvasDocumentStore,
    owner: DesktopCanvasOwner,
): CanvasSession? {
    val session by produceState<CanvasSession?>(null, store, owner.conversationId, owner.agentId) {
        value = null
        // A new chat has no board yet: never fall back to a shared default board for it.
        if (owner.conversationId == null) return@produceState
        openDesktopCanvasSession(
            OpenDesktopCanvasParams(
                scope = this,
                store = store,
                conversationId = owner.conversationId,
                agentId = owner.agentId,
                agentName = owner.agentName,
                onSessionReady = { value = it },
            ),
        )
    }
    return session
}

@Composable
private fun DockedConversationCanvas(
    session: CanvasSession?,
    actions: ChatCanvasActions,
    headerTrailing: (@Composable () -> Unit)?,
    /** The phone's status bar (and header, over the full-screen page), which the board draws under. */
    chromeTopInset: Dp = 0.dp,
) {
    if (session == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    CanvasWorkspace(
        session = session,
        presenceTransport = DesktopCanvasHostSync.presenceTransport,
        assets = DesktopCanvasHostSync.assets,
        // The canvas is the page: no title bar or back arrow, just its actions pill.
        onNavigateBack = null,
        onShareToChat = actions::shareToChat,
        showTitle = false,
        headerTrailing = headerTrailing,
        modifier = Modifier.fillMaxSize(),
        // A chat card's "Show on canvas" frames its artifact here (letta-mobile-bglj6.13).
        cameraRequest = actions.camera,
        chromeTopInset = chromeTopInset,
    )
}

/** The page's host navigation, with the same folder picker the old working-directory row used. */
@Composable
private fun rememberDesktopChatSurfaceHost(
    port: DesktopChatSessionPort,
    navigation: DesktopSharedChatPageNavigation,
    phone: DesktopPhoneChrome? = null,
): ChatSurfaceHost {
    val directoryPicker = rememberDirectoryPickerLauncher(
        dialogSettings = FileKitDialogSettings(title = "Choose working directory"),
    ) { directory ->
        directory?.let { port.actions.changeWorkingDirectory(it.file.absolutePath) }
    }
    val supportsWorkingDirectory = port.capabilities.collectAsState().value.workingDirectory
    // The shell rebuilds [navigation] with fresh lambdas on every recomposition (a stream token);
    // the host keeps one instance that forwards to the latest, so timeline rows stay skippable.
    val latest by rememberUpdatedState(navigation)
    val hasAgentPane = navigation.openAgentPane != null
    val hasEditAgent = navigation.editAgent != null
    // Keyed on the roster: rows resolve names while they compose, so new names are a new host.
    val agentNames = navigation.agentNamesById
    return remember(directoryPicker, supportsWorkingDirectory, hasAgentPane, hasEditAgent, agentNames, phone) {
        // On the phone the agent pane is the drawer, and it is also the agent switcher (Android's header pill).
        val openDrawer: (() -> Unit)? = phone?.let { chrome -> { chrome.drawerOpen = true } }
        ChatSurfaceHost(
            openCanvas = { latest.openCanvas() },
            openAgent = { agentId -> latest.openAgent(agentId) },
            openModelPicker = { latest.openModelPicker() },
            resolveAgentName = agentNames::get,
            pickWorkingDirectory = if (supportsWorkingDirectory) ({ directoryPicker.launch() }) else null,
            openAgentPane = openDrawer ?: if (hasAgentPane) ({ latest.openAgentPane?.invoke() }) else null,
            editAgent = if (hasEditAgent) ({ latest.editAgent?.invoke() }) else null,
            openAgentSwitcher = openDrawer,
        )
    }
}
