package com.letta.mobile.desktop.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.canvas.CanvasDocumentStore
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.timeline.CanonicalTimelinePresentation
import com.letta.mobile.desktop.LocalDesktopChatFontScale
import com.letta.mobile.desktop.LocalDesktopChatFontScaleSetter
import com.letta.mobile.desktop.OpenDesktopCanvasParams
import com.letta.mobile.desktop.canvas.DesktopCanvasHostSync
import com.letta.mobile.desktop.canvas.DesktopCanvasOwner
import com.letta.mobile.desktop.openDesktopCanvasSession
import com.letta.mobile.ui.canvas.CanvasWorkspace
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfaceModeReducer
import com.letta.mobile.ui.chat.session.ChatSurfacePresentation
import com.letta.mobile.ui.chat.surface.ChatCanvasActions
import com.letta.mobile.ui.chat.surface.ChatSurface
import com.letta.mobile.ui.chat.surface.ChatSurfaceAppearance
import com.letta.mobile.ui.chat.surface.ChatSurfacePlatform
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.compose.rememberDirectoryPickerLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/*
 * letta-mobile-bglj6.1 (stage 3, desktop): the Conversations destination rendered with the shared
 * KMP chat page instead of ChatDetailPane, behind DesktopSharedChatPageFlag.
 */

/**
 * The one [DesktopChatSessionPort] for [controller], alive as long as the controller is. Its flows
 * run in a child of the composition's scope that is cancelled when the controller changes.
 */
@Composable
internal fun rememberDesktopChatSessionPort(
    controller: DesktopChatController,
    onA2uiAction: (A2uiAction) -> Unit,
): DesktopChatSessionPort {
    val latestA2uiAction by rememberUpdatedState(onA2uiAction)
    val latestFontScaleSetter by rememberUpdatedState(LocalDesktopChatFontScaleSetter.current)
    val parentScope = rememberCoroutineScope()
    val portScope = remember(controller) {
        CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    }
    DisposableEffect(portScope) { onDispose { portScope.cancel() } }
    return remember(controller, portScope) {
        DesktopChatSessionPort(
            controller = controller,
            scope = portScope,
            bindings = DesktopChatSessionBindings(
                onA2uiAction = { latestA2uiAction(it) },
                onSetFontScale = { latestFontScaleSetter(it) },
                canSubmitApprovals = { controller.canSubmitApprovals.value },
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
    var presentation by remember { mutableStateOf(ChatSurfacePresentation.CanvasFirst) }
    val host = rememberDesktopChatSurfaceHost(port, navigation)
    val ambientStatus = rememberDesktopAmbientStatus(state.isThinking, state.errorMessage)
    val session = rememberConversationCanvasSession(state.canvasStore, state.canvasOwner)
    ChatSurface(
        port = port,
        presentation = presentation,
        onIntent = { intent -> presentation = ChatSurfaceModeReducer.reduce(presentation, intent) },
        host = host,
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        appearance = ChatSurfaceAppearance(
            fontScale = LocalDesktopChatFontScale.current,
            // The font-scale host already scales the window's text through density.
            fontScaleAppliedByHost = true,
        ),
        platform = remember(ambientStatus) {
            ChatSurfacePlatform(
                pageBackground = { content ->
                    DesktopAmbientChatBackground(status = ambientStatus, modifier = Modifier.fillMaxSize()) { content() }
                },
            )
        },
        pagedTimeline = state.pagedTimeline,
        canvas = { actions -> DockedConversationCanvas(session, actions) },
    )
}

/** The selected conversation's own board, created on first open (the side pane's same session). */
@Composable
private fun rememberConversationCanvasSession(
    store: CanvasDocumentStore,
    owner: DesktopCanvasOwner,
): CanvasSession? {
    val session by produceState<CanvasSession?>(null, store, owner.conversationId, owner.agentId) {
        value = null
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
private fun DockedConversationCanvas(session: CanvasSession?, actions: ChatCanvasActions) {
    if (session == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    CanvasWorkspace(
        session = session,
        presenceTransport = DesktopCanvasHostSync.presenceTransport,
        assets = DesktopCanvasHostSync.assets,
        onNavigateBack = actions::back,
        onShareToChat = actions::shareToChat,
        modifier = Modifier.fillMaxSize(),
    )
}

/** The page's host navigation, with the same folder picker the old working-directory row used. */
@Composable
private fun rememberDesktopChatSurfaceHost(
    port: DesktopChatSessionPort,
    navigation: DesktopSharedChatPageNavigation,
): ChatSurfaceHost {
    val directoryPicker = rememberDirectoryPickerLauncher(
        dialogSettings = FileKitDialogSettings(title = "Choose working directory"),
    ) { directory ->
        directory?.let { port.actions.changeWorkingDirectory(it.file.absolutePath) }
    }
    val supportsWorkingDirectory = port.capabilities.workingDirectory
    return remember(navigation, directoryPicker, supportsWorkingDirectory) {
        ChatSurfaceHost(
            openCanvas = navigation.openCanvas,
            openAgent = navigation.openAgent,
            openModelPicker = navigation.openModelPicker,
            pickWorkingDirectory = if (supportsWorkingDirectory) ({ directoryPicker.launch() }) else null,
        )
    }
}
