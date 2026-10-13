package com.letta.mobile.desktop

import com.letta.mobile.desktop.phone.ClosePhoneDrawerOnNavigation
import com.letta.mobile.desktop.phone.LocalDesktopPhone
import com.letta.mobile.desktop.phone.PhoneAwareContent
import com.letta.mobile.desktop.phone.isBareChatPage
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.ui.shell.ShellAdminPages
import com.letta.mobile.ui.shell.ShellPage
import com.letta.mobile.ui.shell.ShellPageContent
import com.letta.mobile.ui.shell.ShellPageDeps
import com.letta.mobile.desktop.chat.showsCanvasSidePane
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.ui.canvas.CanvasWorkspace
import com.letta.mobile.ui.components.LettaSidePane

internal data class DesktopMainContentInputs(
    /** letta-mobile-ckmub.3.24: the agent editor beside the chat (an agent's, or a new agent's), if open. */
    val agentEditor: ShellDestination.AgentEditor?,
    /** The shared agent editor page, bound to this session. */
    val agentEditorPage: ShellPageContent<ShellDestination.AgentEditor>,
    val selectedDestination: DesktopDestination,
    /** The shared destination of the route on screen, without the editor/canvas panels beside it. */
    val pageDestination: ShellDestination,
    val selectedConversationId: String?,
    val destinationInputs: DestinationContentInputs,
    val activeCanvasSession: CanvasSession? = null,
    /** The board the shared page has docked; the side pane never shows it a second time. */
    val dockedCanvasId: com.letta.mobile.data.canvas.CanvasId? = null,
    /** letta-mobile-ckmub.3.11: the shared KMP chat page, desktop's only chat. */
    val chatPage: @Composable (Modifier) -> Unit,
    /** letta-mobile-ckmub.2.6: the shared Runs, Templates and Projects pages, bound to this session. */
    val adminPages: ShellAdminPages = ShellAdminPages(),
)

internal data class DesktopMainContentActions(
    val onEditAgentClose: () -> Unit,
    val destinationActions: DestinationContentActions,
    val onCloseCanvas: () -> Unit = {},
    val onShareCanvasToChat: ((bytes: ByteArray, mimeType: String) -> Unit)? = null,
)

@Composable
internal fun DesktopMainContentPane(
    inputs: DesktopMainContentInputs,
    actions: DesktopMainContentActions,
    modifier: Modifier = Modifier,
) {
    // The phone preview frames every destination but the chat page in a phone app bar; null on the desktop.
    val phone = LocalDesktopPhone.current
    ClosePhoneDrawerOnNavigation(phone, inputs.selectedDestination, inputs.selectedConversationId, inputs.agentEditor)
    val bare = isBareChatPage(inputs.selectedDestination, inputs.agentEditor)
    PhoneAwareContent(phone, title = inputs.selectedDestination.label, bare = bare, modifier = modifier) { paneModifier ->
        MainContentRow(inputs, actions, paneModifier)
    }
}

@Composable
private fun MainContentRow(inputs: DesktopMainContentInputs, actions: DesktopMainContentActions, modifier: Modifier) {
    val editor = inputs.agentEditor
    val canvas = inputs.activeCanvasSession
    // The editor is a panel beside the chat, not a page: the conversation stays in view.
    androidx.compose.foundation.layout.Row(modifier = modifier) {
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) { MainDestination(inputs, actions) }
        when {
            editor != null -> AgentEditorSidePane(editor, inputs, actions)
            canvas != null && showsCanvasSidePane(canvas.canvasId, inputs.dockedCanvasId) -> CanvasSidePane(canvas, actions)
        }
    }
}

/** letta-mobile-ckmub.3.4: the desktop maps its route to a shared destination; ShellPage draws its page. */
@Composable
private fun MainDestination(inputs: DesktopMainContentInputs, actions: DesktopMainContentActions) {
    // The bundled runtime's crash notice is a window banner (DesktopShellWindowContent), not part of the page.
    val chat = ShellPageContent<ShellDestination.Chat> { _, modifier -> inputs.chatPage(modifier) }
    val deps = desktopShellPageDeps(inputs.destinationInputs, actions.destinationActions, DesktopHostPages(chat, inputs.adminPages))
    ShellPage(destination = inputs.pageDestination, deps = deps, modifier = Modifier.fillMaxSize())
}

/** letta-mobile-ckmub.3.24: the shared agent editor, through ShellPage's AgentEditor destination. */
@Composable
private fun AgentEditorSidePane(editor: ShellDestination.AgentEditor, inputs: DesktopMainContentInputs, actions: DesktopMainContentActions) {
    val title = if (editor.agentId == null) "New agent" else "Edit agent"
    LettaSidePane(title = title, onClose = actions.onEditAgentClose, initialWidth = 460.dp) {
        ShellPage(destination = editor, deps = ShellPageDeps(agentEditor = inputs.agentEditorPage), modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun CanvasSidePane(session: CanvasSession, actions: DesktopMainContentActions) {
    val canvasDocument by session.document.collectAsState()
    LettaSidePane(
        title = canvasDocument?.title ?: "Canvas",
        onClose = actions.onCloseCanvas,
        initialWidth = 540.dp,
        // The board's own title pill (with its back arrow) is the header: a full pane
        // header above it only pushed the board down.
        showHeader = false,
    ) {
        CanvasWorkspace(
            session = session,
            presenceTransport = com.letta.mobile.desktop.canvas.DesktopCanvasHostSync.presenceTransport,
            assets = com.letta.mobile.desktop.canvas.DesktopCanvasHostSync.assets,
            onNavigateBack = actions.onCloseCanvas,
            onShareToChat = actions.onShareCanvasToChat,
            // No right button on a finger: holding one on open board is how it picks several.
            longPressDrawsSelectionBox = true,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
