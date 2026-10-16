package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.desktopshell.ShellLayoutEvent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.shell.ShellWorkspaceNavigator
import com.letta.mobile.data.shell.ShellWorkspaceState
import com.letta.mobile.desktop.chat.DesktopChatController
import com.letta.mobile.desktop.chat.DesktopChatSessionPort
import com.letta.mobile.desktop.chat.DesktopChatSurfaceState
import com.letta.mobile.desktop.chat.DesktopDockedCanvasRouter
import com.letta.mobile.desktop.chat.rememberDesktopChatSessionPort

/** The shared KMP chat page's wiring: desktop's only chat (letta-mobile-ckmub.3.11). */
internal data class DesktopSharedChatWiring(
    val port: DesktopChatSessionPort,
    /** The conversation's board lives in the shared page's dock. */
    val dockedCanvas: DesktopDockedCanvasRouter,
)

/**
 * letta-mobile-bglj6.1.10: everything the pieces of [LettaDesktopApp] work with - the window's
 * bindings, the services, the shared workspace (letta-mobile-ckmub.17.3) and its verbs - built once per composition.
 */
internal data class DesktopShellContext(
    val shell: DesktopAppShellBindings,
    val core: DesktopShellCore,
    val panels: DesktopShellPanels,
    val navigator: ShellWorkspaceState,
    val router: ShellWorkspaceNavigator,
    /** Mascots picked in the editor this session: on the orbs at once, before the backend round-trips them. */
    val avatarOverrides: androidx.compose.runtime.MutableState<Map<String, MascotIdentity>>,
    val chatState: State<DesktopChatSurfaceState>,
    /** Sends an A2UI action over the live session, defaulting to the selected conversation. */
    val onA2uiAction: (A2uiAction) -> Unit,
    val sharedChat: DesktopSharedChatWiring,
    val imageIntake: DesktopImageIntake,
)

@Composable
internal fun rememberDesktopShellContext(
    shell: DesktopAppShellBindings,
    navigator: ShellWorkspaceState,
): DesktopShellContext {
    val core = rememberDesktopShellCore()
    val chatController = core.chatController
    val chatState = chatController.state.collectAsState()
    val panels = rememberDesktopShellPanels(core, chatState.value)
    val router = remember(navigator, chatController, chatState) {
        ShellWorkspaceNavigator(navigator, DesktopShellChatPort(chatController, chatState))
    }
    // No transport carries A2UI card actions (the shim's user_action frame is gone, by4vm.111) and
    // desktop has no registry-backed approval path (by4vm.2), so every card action is dropped, logged.
    val onA2uiAction: (A2uiAction) -> Unit = { action ->
        com.letta.mobile.util.Telemetry.event(
            "DesktopA2ui", "action.unroutable",
            "surfaceId" to action.surfaceId,
            "name" to action.name,
            level = com.letta.mobile.util.Telemetry.Level.WARN,
        )
    }
    return DesktopShellContext(
        shell = shell,
        core = core,
        panels = panels,
        navigator = navigator,
        router = router,
        avatarOverrides = remember { mutableStateOf(emptyMap()) },
        chatState = chatState,
        onA2uiAction = onA2uiAction,
        sharedChat = rememberDesktopSharedChatWiring(chatController, onA2uiAction, core.bootstrap.dataBindings.modelControl?.catalog),
        imageIntake = rememberDesktopImageIntake(core, navigator.selectedDestination == DesktopDestination.Conversations),
    )
}

@Composable
private fun rememberDesktopSharedChatWiring(
    chatController: DesktopChatController,
    onA2uiAction: (A2uiAction) -> Unit,
    modelCatalog: com.letta.mobile.data.repository.modelcontrol.ModelCatalogRepository?,
): DesktopSharedChatWiring {
    val port = rememberDesktopChatSessionPort(chatController, onA2uiAction, modelCatalog)
    val dockedCanvasRouter = remember { DesktopDockedCanvasRouter() }
    return DesktopSharedChatWiring(port = port, dockedCanvas = dockedCanvasRouter)
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

/** Shows the docked canvas under the chat. */
internal fun DesktopShellContext.showDockedCanvasAction(): () -> Unit {
    val docked = sharedChat.dockedCanvas
    return { showDocked(docked) }
}

/** A library or search pick: the docked board shows in the dock, any other in the side pane. */
internal fun DesktopShellContext.openCanvas(id: CanvasId) {
    val docked = sharedChat.dockedCanvas
    if (id == docked.dockedCanvasId) {
        showDocked(docked)
    } else {
        core.canvasShell.open(id)
    }
}

private fun DesktopShellContext.showDocked(docked: DesktopDockedCanvasRouter) {
    navigator.selectedDestination = DesktopDestination.Conversations
    docked.showDocked()
}
