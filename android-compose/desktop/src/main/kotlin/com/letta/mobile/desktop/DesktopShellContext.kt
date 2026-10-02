package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.canvas.CanvasId
import com.letta.mobile.data.desktopshell.ShellLayoutEvent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.desktop.chat.DesktopChatController
import com.letta.mobile.desktop.chat.DesktopChatSessionPort
import com.letta.mobile.desktop.chat.DesktopChatSurfaceState
import com.letta.mobile.desktop.chat.DesktopDockedCanvasRouter
import com.letta.mobile.desktop.chat.rememberDesktopChatSessionPort

/** letta-mobile-bglj6.1: the shared KMP chat page's wiring, present only while its preview flag is on. */
internal data class DesktopSharedChatWiring(
    val port: DesktopChatSessionPort?,
    /** Only while the shared page is on does the conversation's board live in its dock. */
    val dockedCanvas: DesktopDockedCanvasRouter?,
)

/**
 * letta-mobile-bglj6.1.10: everything the pieces of [LettaDesktopApp] work with - the window's
 * bindings, the services, the navigation and the overlays - built once per composition.
 */
internal data class DesktopShellContext(
    val shell: DesktopAppShellBindings,
    val core: DesktopShellCore,
    val panels: DesktopShellPanels,
    val navigator: DesktopShellNavigator,
    val router: DesktopShellRouter,
    val overlays: DesktopOverlayVisibility,
    val chatState: State<DesktopChatSurfaceState>,
    /** Sends an A2UI action over the live session, defaulting to the selected conversation. */
    val onA2uiAction: (A2uiAction) -> Unit,
    val sharedChat: DesktopSharedChatWiring,
    val imageIntake: DesktopImageIntake,
)

@Composable
internal fun rememberDesktopShellContext(
    shell: DesktopAppShellBindings,
    navigator: DesktopShellNavigator,
): DesktopShellContext {
    val overlays = remember { DesktopOverlayVisibility() }
    val core = rememberDesktopShellCore()
    val chatController = core.chatController
    val chatState = chatController.state.collectAsState()
    val panels = rememberDesktopShellPanels(core, chatState.value)
    val router = remember(navigator, chatController, chatState) {
        DesktopShellRouter(navigator, chatController, chatState)
    }
    val onA2uiAction: (A2uiAction) -> Unit = { action ->
        val resolved = resolveA2uiAction(action, chatState.value.selectedConversationId)
        core.sessionGraph.value.channelTransport.sendA2uiAction(resolved)
    }
    return DesktopShellContext(
        shell = shell,
        core = core,
        panels = panels,
        navigator = navigator,
        router = router,
        overlays = overlays,
        chatState = chatState,
        onA2uiAction = onA2uiAction,
        sharedChat = rememberDesktopSharedChatWiring(chatController, onA2uiAction),
        imageIntake = rememberDesktopImageIntake(core, navigator),
    )
}

/** An action that names no conversation goes to the selected one. */
private fun resolveA2uiAction(action: A2uiAction, selectedConversationId: String?): A2uiAction {
    if (!action.conversationId.isNullOrBlank()) return action
    return action.copy(conversationId = selectedConversationId)
}

@Composable
private fun rememberDesktopSharedChatWiring(
    chatController: DesktopChatController,
    onA2uiAction: (A2uiAction) -> Unit,
): DesktopSharedChatWiring {
    // letta-mobile-bglj6.1: the shared KMP chat page's port, built only while the preview flag is on.
    val sharedChatEnabled by LocalDesktopSharedChatPageFlag.current.enabled.collectAsState()
    val port = if (sharedChatEnabled) {
        rememberDesktopChatSessionPort(chatController, onA2uiAction)
    } else {
        null
    }
    val dockedCanvasRouter = remember { DesktopDockedCanvasRouter() }
    return DesktopSharedChatWiring(
        port = port,
        dockedCanvas = dockedCanvasRouter.takeIf { port != null },
    )
}

/**
 * The companion mascot is the way into its agent: bring the agent pane (the sidebar) back if it
 * was collapsed, and leave any editor.
 */
internal fun DesktopShellContext.openAgentPane() {
    navigator.navigate(DesktopDestination.Conversations)
    core.layout.controller.dispatch(ShellLayoutEvent.SetSidebarCollapsed(false))
}

/** A new chat with the focused agent. */
internal fun DesktopShellContext.openNewChatForFocusedAgent(focus: DesktopAgentFocus) {
    router.openNewChat(focus.selectedAgentId?.let(::AgentId))
}

/** Shows the docked canvas under the chat; null while the shared page (and so the dock) is off. */
internal fun DesktopShellContext.showDockedCanvasAction(): (() -> Unit)? {
    val docked = sharedChat.dockedCanvas ?: return null
    return { showDocked(docked) }
}

/** A library or search pick: the docked board shows in the dock, any other in the side pane. */
internal fun DesktopShellContext.openCanvas(id: CanvasId) {
    val docked = sharedChat.dockedCanvas
    if (docked != null && id == docked.dockedCanvasId) {
        showDocked(docked)
    } else {
        core.canvasShell.open(id)
    }
}

private fun DesktopShellContext.showDocked(docked: DesktopDockedCanvasRouter) {
    navigator.selectedDestination = DesktopDestination.Conversations
    docked.showDocked()
}
