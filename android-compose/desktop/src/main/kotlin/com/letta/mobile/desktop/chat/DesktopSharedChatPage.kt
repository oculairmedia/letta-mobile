package com.letta.mobile.desktop.chat

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.canvas.CanvasDocumentStore
import com.letta.mobile.data.chat.display.ChatDisplayOptions
import com.letta.mobile.data.chat.display.ChatFontScale
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.timeline.CanonicalTimelinePresentation
import com.letta.mobile.desktop.LocalDesktopChatFontScale
import com.letta.mobile.desktop.LocalDesktopChatFontScaleSetter
import com.letta.mobile.desktop.LocalDesktopOpenChatsOnCanvas
import com.letta.mobile.desktop.canvas.DesktopCanvasHostSync
import com.letta.mobile.desktop.phone.ChatPageFrame
import com.letta.mobile.desktop.phone.DesktopPhoneChatFrame
import com.letta.mobile.desktop.phone.DesktopPhoneChrome
import com.letta.mobile.desktop.phone.LocalDesktopPhone
import com.letta.mobile.desktop.phone.PhoneChatPageInputs
import com.letta.mobile.desktop.phone.chatAppearance
import com.letta.mobile.desktop.phone.opensOnCanvas
import com.letta.mobile.ui.ambient.AmbientMotionStatus
import com.letta.mobile.ui.chat.session.ChatComposerHostInputs
import com.letta.mobile.ui.chat.session.ChatDockGeometry
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfaceIntent
import com.letta.mobile.ui.chat.subagents.SubagentRingsOverlay
import com.letta.mobile.ui.chat.subagents.rememberSubagentDualViewHost
import com.letta.mobile.ui.chat.surface.ChatSurfaceAppearance
import com.letta.mobile.ui.chat.surface.ChatSurfacePlatform
import com.letta.mobile.ui.chat.surface.ambient.rememberChatAmbientStatus
import com.letta.mobile.ui.chat.surface.recents.ChatRecentInteractions
import com.letta.mobile.ui.shell.workspace.ShellCanvasOwner
import com.letta.mobile.ui.shell.workspace.ShellChatBoard
import com.letta.mobile.ui.shell.workspace.ShellChatDestination
import com.letta.mobile.ui.shell.workspace.ShellChatLook
import com.letta.mobile.ui.shell.workspace.ShellChatPage
import com.letta.mobile.ui.shell.workspace.rememberShellChatPresentation

/*
 * letta-mobile-bglj6.1, ckmub.3.11, ckmub.17.4: the Conversations destination is the shared
 * [ShellChatDestination]; this file is its desktop container (port, dock router, board sync, ambient
 * background, working-directory picker, the phone preview's frame).
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
    /** letta-mobile-by4vm.85: the host catalog the effort chip reads; null leaves the chip off. */
    modelCatalog: com.letta.mobile.data.repository.modelcontrol.ModelCatalogRepository? = null,
): DesktopChatSessionPort {
    val latestA2uiAction by rememberUpdatedState(onA2uiAction)
    val latestFontScaleSetter by rememberUpdatedState(LocalDesktopChatFontScaleSetter.current)
    val compositionScope = rememberCoroutineScope()
    return remember(controller, compositionScope, modelCatalog) {
        DesktopChatSessionPort(
            controller = controller,
            scope = compositionScope,
            bindings = DesktopChatSessionBindings(
                onA2uiAction = { latestA2uiAction(it) },
                onSetFontScale = { latestFontScaleSetter(it) },
                modelCatalog = modelCatalog,
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
    /** letta-mobile-y5q9z: the agent's conversations for the docked chat's "+" (recent interactions). */
    val recentInteractions: ChatRecentInteractions? = null,
)

internal data class DesktopSharedChatPageState(
    val port: DesktopChatSessionPort,
    val pagedTimeline: CanonicalTimelinePresentation?,
    val hostInputs: ChatComposerHostInputs,
    /** Tints the ambient glow "failed" (the controller keeps its error until the next send). */
    val errorMessage: String?,
    /** Where the conversation's board lives, and whose it is: the docked canvas. */
    val canvasStore: CanvasDocumentStore,
    val canvasOwner: ShellCanvasOwner,
    /** Routes the shell's canvas opens to this page's dock (one live session per board). */
    val dockedCanvas: DesktopDockedCanvasRouter? = null,
    /** The shell's own control drawn at the end of the canvas header (background tasks). */
    val canvasHeaderTrailing: (@Composable () -> Unit)? = null,
    /**
     * Where the person put the docked panel, remembered by the app (read once per session); null
     * until the saved placement is read, and the page draws no dock until then.
     */
    val dockGeometry: MutableState<ChatDockGeometry?>,
    /** The conversation's subagents, drawn as the shared rings over the full-screen page; null draws none. */
    val subagents: DesktopChatSubagents? = null,
)

/**
 * letta-mobile-bglj6.1 (stage 4): the conversation opens on its canvas with the chat docked
 * under it; expanding gives the full-screen page, and swipe up or "Open canvas" returns to the
 * canvas. The page itself is the shared [ShellChatDestination].
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
    val presentation = rememberShellChatPresentation(phone.opensOnCanvas(openOnCanvas.enabled.value))
    val directoryPicker = rememberDesktopWorkingDirectoryPicker(port)
    val host = rememberDesktopChatSurfaceHost(port, navigation, directoryPicker, phone)
    val pageHost = rememberSubagentDualViewHost(host, state.subagents?.views, state.subagents?.entries.orEmpty())
    // The run's own state, as the composer's Stop button and the docked panel's glow read it: the
    // shell's "thinking" flag clears at the first reply and put the page's glow out mid-run.
    val uiState by port.uiState.collectAsState()
    val displayOptions by port.displayOptions.collectAsState()
    val ambientStatus = rememberChatAmbientStatus(isThinking = uiState.isRunInFlight, failed = state.errorMessage != null)
    val router = state.dockedCanvas
    router?.let { LaunchedEffect(it, it.showRequests) { if (it.showRequests > 0) presentation.onIntent(ChatSurfaceIntent.Collapse) } }
    val onBoardShown = remember(router) { router?.let { docked -> { id: CanvasId? -> docked.dockedCanvasId = id } } }
    val board = remember(state.canvasStore) { desktopChatBoard(state.canvasStore) }
    val frameInputs = PhoneChatPageInputs(presentation.value.mode, port.uiState) { presentation.onIntent(ChatSurfaceIntent.Collapse) }
    DesktopPhoneChatFrame(phone, frameInputs, modifier) { pageModifier, frame ->
        ShellChatDestination(
            page = ShellChatPage(port, pageHost, board, state.canvasOwner, state.dockGeometry),
            presentation = presentation,
            modifier = pageModifier.fillMaxSize(),
            look = ShellChatLook(
                appearance = phone.chatAppearance(rememberDesktopChatAppearance(displayOptions)),
                platform = rememberDesktopChatPlatform(ambientStatus, frame, state.subagents),
                pagedTimeline = state.pagedTimeline,
                recents = navigation.recentInteractions,
                canvasHeaderTrailing = state.canvasHeaderTrailing,
                onBoardShown = onBoardShown,
                subagentViews = state.subagents?.views,
            ),
        )
    }
    DesktopWorkingDirectoryPickerHost(directoryPicker)
}

/** The conversation's board in the desktop's store, synced and with presence over the host relay. */
private fun desktopChatBoard(store: CanvasDocumentStore) = ShellChatBoard(
    store = store,
    opLog = DesktopCanvasHostSync.opLog,
    syncTransport = DesktopCanvasHostSync.syncTransport,
    presence = DesktopCanvasHostSync.presenceTransport,
    assets = DesktopCanvasHostSync.assets,
)

/**
 * The desktop's appearance: its own font scale, already applied to the window's text through
 * density, and the Chat options' [displayOptions] (letta-mobile-zxk2n.5).
 */
@Composable
private fun rememberDesktopChatAppearance(displayOptions: ChatDisplayOptions): ChatSurfaceAppearance {
    val fontScale = LocalDesktopChatFontScale.current
    return remember(fontScale, displayOptions) {
        ChatSurfaceAppearance(
            displayOptions = displayOptions,
            fontScale = fontScale,
            // The font-scale host already scales the window's text through density.
            fontScaleAppliedByHost = true,
            fontScaleRange = ChatFontScale.MIN..ChatFontScale.MAX,
        )
    }
}

/** The ambient glow, what the host's [frame] asks (the phone's top chrome), and the subagent rings (ckmub.3.19). */
@Composable
private fun rememberDesktopChatPlatform(ambientStatus: AmbientMotionStatus, frame: ChatPageFrame, subagents: DesktopChatSubagents?): ChatSurfacePlatform =
    remember(ambientStatus, frame, subagents) {
        ChatSurfacePlatform(
            pageBackground = { content ->
                DesktopAmbientChatBackground(status = ambientStatus, modifier = Modifier.fillMaxSize()) { content() }
            },
            showKeyboardHints = frame.showKeyboardHints,
            topChromeInset = frame.topChromeInset,
            timelineOverlay = subagents?.let { rings -> { SubagentRingsOverlay(rings.entries, rings.loader, Modifier, rings.onViewConversation) } },
        )
    }

/** The page's host navigation; the working-directory row opens the shared picker (letta-mobile-zxk2n.6). */
@Composable
private fun rememberDesktopChatSurfaceHost(
    port: DesktopChatSessionPort,
    navigation: DesktopSharedChatPageNavigation,
    directoryPicker: DesktopWorkingDirectoryPicker,
    phone: DesktopPhoneChrome? = null,
): ChatSurfaceHost {
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
            pickWorkingDirectory = if (supportsWorkingDirectory) {
                { directoryPicker.open(port.composer.value.workingDirectory?.path) }
            } else {
                null
            },
            openAgentPane = openDrawer ?: if (hasAgentPane) ({ latest.openAgentPane?.invoke() }) else null,
            editAgent = if (hasEditAgent) ({ latest.editAgent?.invoke() }) else null,
            openAgentSwitcher = openDrawer,
        )
    }
}
