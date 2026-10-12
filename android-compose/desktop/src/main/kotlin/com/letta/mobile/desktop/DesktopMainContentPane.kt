package com.letta.mobile.desktop

import com.letta.mobile.desktop.phone.ClosePhoneDrawerOnNavigation
import com.letta.mobile.desktop.phone.LocalDesktopPhone
import com.letta.mobile.desktop.phone.PhoneAwareContent
import com.letta.mobile.desktop.phone.isBareChatPage
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.ui.shell.ShellPage
import com.letta.mobile.ui.shell.ShellPageContent
import com.letta.mobile.data.repository.SubagentRepository
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.desktop.agent.DesktopEditAgentSurface
import com.letta.mobile.desktop.workspace.DesktopAgentSettingsPane
import com.letta.mobile.desktop.workspace.DesktopDreamingDeps
import com.letta.mobile.desktop.workspace.DesktopDreamingPane
import com.letta.mobile.desktop.chat.showsCanvasSidePane
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.desktop.memory.DesktopBlockApi
import com.letta.mobile.ui.canvas.CanvasWorkspace
import kotlinx.coroutines.CoroutineScope
import com.letta.mobile.ui.components.LettaSidePane

internal data class DesktopMainContentInputs(
    val editingAgentId: String?,
    val selectedDestination: DesktopDestination,
    /** The shared destination of the route on screen, without the editor/canvas panels beside it. */
    val pageDestination: ShellDestination,
    val modelOptions: List<Pair<String, String>>,
    val agentRepository: IAgentRepository,
    val blockApi: DesktopBlockApi?,
    val secureSettingsStore: SecureSettingsStore,
    val chatScope: CoroutineScope,
    val selectedConversationId: String?,
    val destinationInputs: DestinationContentInputs,
    val subagentRepository: SubagentRepository?,
    val activeCanvasSession: CanvasSession? = null,
    /** The board the shared page has docked; the side pane never shows it a second time. */
    val dockedCanvasId: com.letta.mobile.data.canvas.CanvasId? = null,
    /** letta-mobile-ckmub.3.11: the shared KMP chat page, desktop's only chat. */
    val chatPage: @Composable (Modifier) -> Unit,
)

internal data class DesktopMainContentActions(
    val onEditAgentClose: () -> Unit,
    val onEditAgentSaved: (com.letta.mobile.avatar.core.MascotIdentity, Boolean) -> Unit,
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
    ClosePhoneDrawerOnNavigation(phone, inputs.selectedDestination, inputs.selectedConversationId, inputs.editingAgentId)
    val bare = isBareChatPage(inputs.selectedDestination, inputs.editingAgentId)
    PhoneAwareContent(phone, title = inputs.selectedDestination.label, bare = bare, modifier = modifier) { paneModifier ->
        MainContentRow(inputs, actions, paneModifier)
    }
}

@Composable
private fun MainContentRow(inputs: DesktopMainContentInputs, actions: DesktopMainContentActions, modifier: Modifier) {
    val editing = inputs.editingAgentId
    val canvas = inputs.activeCanvasSession
    // The editor is a panel beside the chat, not a page: the conversation stays in view.
    androidx.compose.foundation.layout.Row(modifier = modifier) {
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) { MainDestination(inputs, actions) }
        when {
            editing != null -> EditAgentSidePane(editing, inputs, actions)
            canvas != null && showsCanvasSidePane(canvas.canvasId, inputs.dockedCanvasId) -> CanvasSidePane(canvas, actions)
        }
    }
}

/** letta-mobile-ckmub.3.4: the desktop maps its route to a shared destination; ShellPage draws its page. */
@Composable
private fun MainDestination(inputs: DesktopMainContentInputs, actions: DesktopMainContentActions) {
    val chat = ShellPageContent<ShellDestination.Chat> { _, modifier -> DesktopChatContainer(inputs.chatPage, modifier) }
    val deps = desktopShellPageDeps(inputs.destinationInputs, actions.destinationActions, chat)
    ShellPage(destination = inputs.pageDestination, deps = deps, modifier = Modifier.fillMaxSize())
}

/**
 * The desktop chat container: the bundled runtime's crash banner (F03) over the shared chat page.
 * The page draws the background-tasks toggle in its canvas header.
 */
@Composable
private fun DesktopChatContainer(chatPage: @Composable (Modifier) -> Unit, modifier: Modifier) {
    Column(modifier) {
        DesktopLocalRuntimeBanner()
        chatPage(Modifier.weight(1f).fillMaxWidth())
    }
}

@Composable
private fun EditAgentSidePane(agentId: String, inputs: DesktopMainContentInputs, actions: DesktopMainContentActions) {
    LettaSidePane(title = "Edit agent", onClose = actions.onEditAgentClose, initialWidth = 460.dp) {
        DesktopAgentSettingsPane(
            agentId = agentId,
            scope = inputs.chatScope,
            dreaming = { modifier ->
                DesktopDreamingPane(agentId, inputs.chatScope, DesktopDreamingDeps(inputs.secureSettingsStore, inputs.subagentRepository), modifier)
            },
        ) { modifier ->
            DesktopEditAgentSurface(
                agentId = agentId,
                modelOptions = inputs.modelOptions,
                agentRepository = inputs.agentRepository,
                blockApi = inputs.blockApi,
                settings = inputs.secureSettingsStore,
                scope = inputs.chatScope,
                onSaved = actions.onEditAgentSaved,
                modifier = modifier,
            )
        }
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
