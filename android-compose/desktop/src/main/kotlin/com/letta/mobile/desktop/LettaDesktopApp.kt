package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.letta.mobile.data.desktopshell.ConversationTabsState
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.desktop.chat.DesktopChatConnectionState
import com.letta.mobile.desktop.chat.DesktopChatSurfaceState
import dev.nucleusframework.application.NucleusApplicationScope
import java.awt.Window
import kotlinx.coroutines.flow.StateFlow

/** Application-scoped inputs the desktop shell composes over. */
internal data class DesktopAppShellBindings(
    val nucleusApplicationScope: NucleusApplicationScope,
    val window: Window,
    val deepLinks: StateFlow<DesktopDeepLinkRequest?>,
    val quickQuery: DesktopQuickQueryCoordinator,
)

/**
 * The desktop shell. letta-mobile-bglj6.1.10: it only assembles the pieces - the services and
 * navigation ([rememberDesktopShellContext]), the state derived from them each composition
 * ([rememberDesktopShellFrame]), the effects, the window content and the header chrome.
 */
@Composable
internal fun LettaDesktopApp(
    shell: DesktopAppShellBindings,
    onActiveTitleChange: (String) -> Unit = {},
    onHeaderChromeChange: (DesktopHeaderChromeState) -> Unit = {},
) {
    // Launch on the fleet dashboard: it is the only view that says something
    // before a conversation is selected.
    val selectedDestination = rememberSaveable { mutableStateOf(DesktopDestination.Home) }
    // Spotify-style library toggle: icon rail <-> expanded names-and-spaces list.
    val railExpanded = rememberSaveable { mutableStateOf(false) }
    val navigator = remember { DesktopShellNavigator(selectedDestination, railExpanded) }
    val context = rememberDesktopShellContext(shell, navigator)
    val chatState = context.chatState.value
    val conversationTabs = remember(chatState.sessionGraphId) { mutableStateOf(ConversationTabsState()) }
    val frame = rememberDesktopShellFrame(context, chatState)
    DesktopShellEffects(context, frame, conversationTabs)
    DesktopShellRoutingEffects(context, frame, onActiveTitleChange)
    DesktopMaterialTheme {
        DesktopShellWindowContent(context, frame)
        DesktopShellHeaderChrome(context, frame, conversationTabs, onHeaderChromeChange)
    }
}

/** The window title, and deep links into the app. */
@Composable
private fun DesktopShellRoutingEffects(
    context: DesktopShellContext,
    frame: DesktopShellFrame,
    onActiveTitleChange: (String) -> Unit,
) {
    val navigator = context.navigator
    val chatState = frame.chatState
    val activeTitle = desktopActiveTitle(navigator.selectedDestination, chatState.selectedConversation?.title)
    LaunchedEffect(activeTitle) { onActiveTitleChange(activeTitle) }
    DesktopDeepLinkRouting(
        deepLinks = context.shell.deepLinks,
        chatState = chatState,
        actions = DesktopDeepLinkRoutingActions(
            // Deep links must win over the full-page agent editor, matching
            // the sidebar navigation paths that clear edit mode before routing.
            onDestinationSelected = navigator::navigate,
            onSelectConversation = { conversationId ->
                navigator.editAgentId = null
                context.core.chatController.selectConversation(conversationId)
            },
            onOpenAgent = { context.router.openAgent(AgentId(it)) },
        ),
    )
}

private data class DesktopDeepLinkRoutingActions(
    val onDestinationSelected: (DesktopDestination) -> Unit,
    val onSelectConversation: (String) -> Unit,
    val onOpenAgent: (String) -> Unit,
)

/**
 * Routes deep links into the app, buffering targets that arrive before the
 * conversation list has loaded (cold-start protocol activation):
 * selectConversation ignores unknown ids and openAgent would treat the empty
 * list as "no existing chat", so both wait for the initial load to settle.
 */
@Composable
private fun DesktopDeepLinkRouting(
    deepLinks: StateFlow<DesktopDeepLinkRequest?>,
    chatState: DesktopChatSurfaceState,
    actions: DesktopDeepLinkRoutingActions,
) {
    var pendingConversationId by remember { mutableStateOf<String?>(null) }
    var pendingAgentId by remember { mutableStateOf<String?>(null) }
    DesktopDeepLinkEffect(
        deepLinks = deepLinks,
        onDestinationSelected = actions.onDestinationSelected,
        onConversationSelected = { pendingConversationId = it },
        onAgentSelected = { pendingAgentId = it },
    )
    LaunchedEffect(pendingConversationId, chatState.conversations) {
        val target = pendingConversationId ?: return@LaunchedEffect
        if (chatState.conversations.any { it.id == target }) {
            actions.onSelectConversation(target)
            pendingConversationId = null
        }
    }
    LaunchedEffect(pendingAgentId, chatState.isLoading, chatState.connectionState) {
        val target = pendingAgentId ?: return@LaunchedEffect
        if (initialConversationLoadSettled(chatState)) {
            actions.onOpenAgent(target)
            pendingAgentId = null
        }
    }
}

private fun initialConversationLoadSettled(chatState: DesktopChatSurfaceState): Boolean {
    if (chatState.isLoading) return false
    return chatState.connectionState != DesktopChatConnectionState.Loading
}

private fun desktopActiveTitle(destination: DesktopDestination, conversationTitle: String?): String {
    if (destination != DesktopDestination.Conversations) return destination.label
    return conversationTitle ?: "Letta Desktop"
}
