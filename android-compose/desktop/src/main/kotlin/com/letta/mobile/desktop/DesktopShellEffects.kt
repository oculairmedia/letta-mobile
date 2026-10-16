package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import com.letta.mobile.data.dreaming.DreamNotifyPrefs
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.shell.ShellWorkspaceState
import com.letta.mobile.desktop.chat.DesktopChatConnectionState
import com.letta.mobile.desktop.chat.DesktopChatSurfaceState
import com.letta.mobile.desktop.chat.DesktopConversationSummary

/**
 * letta-mobile-bglj6.1.10: the shell's effects - controller lifecycles, the tab strip's sync,
 * the tray/taskbar/notification integration, the quick-query window, and the focused agent's
 * skills and slash commands.
 */
@Composable
internal fun DesktopShellEffects(
    context: DesktopShellContext,
    frame: DesktopShellFrame,
) {
    DesktopShellChatEffects(context, frame)
    SyncShellWorkspace(context.navigator, frame.chatState)
    DesktopShellNucleusEffects(context, frame)
    DesktopShellQuickQueryBridge(context, frame)
    DesktopShellFocusedAgentLoaders(context, frame)
}

@Composable
private fun DesktopShellChatEffects(
    context: DesktopShellContext,
    frame: DesktopShellFrame,
) {
    val chatController = context.core.chatController
    val chatState = frame.chatState
    val selectedDestination = context.navigator.selectedDestination
    // letta-mobile folder-settings #2: re-read the working directory whenever
    // the selected conversation changes (or its identity resolves for the
    // first time after a fresh connect).
    LaunchedEffect(chatState.selectedConversationId, chatState.selectedConversation?.agentId) {
        chatController.workingDirectory.refresh()
    }
    CommandPaletteKeyDispatcherEffect(onOpenPalette = { context.overlays.commandPalette = true })
    // letta-mobile-zxk2n.5: mod+Alt+Up / Down for the previous / next conversation.
    ConversationStepKeyDispatcherEffect(context, frame)
    DesktopControllerLifecycles(
        DesktopControllerLifecycleParams(
            chatController = chatController,
            libraries = context.panels.libraries,
            selection = DesktopDestinationSelection(
                selectedDestination = selectedDestination,
                selectedConversationAgentId = chatState.selectedConversation?.agentId?.let(::DesktopAgentId),
            ),
            cronPanel = context.panels.cronPanel,
        ),
    )
}

/**
 * Reports the chat's selection to the shared workspace (letta-mobile-ckmub.17.3): a new session
 * resets it, the shown conversation is the agent's last active one and gets a tab while the chat is
 * the page, and once the gateway has reported its conversations, gone ones lose their tabs.
 */
@Composable
private fun SyncShellWorkspace(workspace: ShellWorkspaceState, chatState: DesktopChatSurfaceState) {
    val session = chatState.sessionGraphId.toString()
    LaunchedEffect(workspace, session) { workspace.sessionChanged(session) }
    val shown = chatState.selectedConversationId?.let(::ConversationId)
    val shownAgent = chatState.selectedConversation?.agentId?.let(::AgentId)
    LaunchedEffect(workspace, workspace.page.kind, shown, shownAgent) { workspace.conversationShown(shown, shownAgent) }
    LaunchedEffect(workspace, chatState.connectionState, chatState.conversations) {
        if (chatState.connectionState.reportedConversations) {
            workspace.conversationsReported(chatState.conversations.mapTo(mutableSetOf()) { ConversationId(it.id) })
        }
    }
}

/** Live and NoConversations are the states in which the gateway's conversation list is authoritative. */
private val DesktopChatConnectionState.reportedConversations: Boolean
    get() = this == DesktopChatConnectionState.Live || this == DesktopChatConnectionState.NoConversations

/**
 * Background work can belong to a conversation the user has switched away from; the
 * taskbar/media/notification integration is labelled with the agent that is actually working,
 * not the current selection.
 */
@Composable
private fun DesktopShellNucleusEffects(context: DesktopShellContext, frame: DesktopShellFrame) {
    val chatController = context.core.chatController
    val chatState = frame.chatState
    val activity = frame.activity
    val workingAgentName = workingAgentName(
        WorkingAgentNameParams(
            thinkingAgentId = activity.thinkingAgentId,
            thinkingConversationId = activity.thinkingConversationId,
            railAgents = frame.focus.railAgents,
            conversations = chatState.conversations,
            fallback = frame.focus.selectedAgentName,
        ),
    )
    DesktopNucleusEffects(
        bindings = DesktopNucleusEffectBindings(
            applicationScope = context.shell.nucleusApplicationScope,
            window = context.shell.window,
            controller = context.core.nucleusController,
            // Read through the controller's live state: the toast fires from a
            // coroutine after composition-captured chatState may be stale.
            replyPreviewFor = { conversationId ->
                notificationReplyPreview(
                    chatController.state.value.messagesByConversationId[conversationId],
                )
            },
            onOpenConversation = { context.router.openConversation(ConversationId(it)) },
            onReplyToConversation = chatController::replyFromNotification,
            attention = rememberShellAttentionBindings(context),
        ),
        state = desktopNucleusEffectState(
            DesktopNucleusRuntimeState(
                thinkingConversationId = activity.thinkingConversationId,
                isStreamingReply = activity.isStreamingReplySelected,
                selectedConversationId = chatState.selectedConversationId,
                agentName = workingAgentName,
                errorMessage = chatState.errorMessage,
                workProgress = subagentWorkProgress(frame.lists.activeSubagents.map { it.status }),
            ),
        ),
        actions = DesktopNucleusEffectActions(
            onOpenCommandPalette = { context.overlays.commandPalette = true },
            // Clear the full-page agent editor like the sidebar and deep-link
            // paths do, or the editor branch keeps rendering over Settings.
            onOpenSettings = { context.navigator.navigate(DesktopDestination.Settings) },
            onQuickQuery = context.shell.quickQuery::open,
        ),
    )
    val dreamPrefs = remember(context.core.bootstrap.secureSettingsStore) { DreamNotifyPrefs(context.core.bootstrap.secureSettingsStore) }
    DreamFinishedNotificationsEffect(
        entries = frame.lists.activeSubagents,
        prefs = dreamPrefs,
        sink = DreamNotificationSink(
            agentName = { id -> frame.focus.railAgents.firstOrNull { it.first == id }?.second },
            onActivate = { activateDesktopWindow(context.shell.window) },
        ),
    )
}

/**
 * letta-mobile-bzvro.27: the window's run registry and the person's mutes, with agent and
 * conversation lookups read through the controller's live state (the toasts fire from coroutines).
 */
@Composable
private fun rememberShellAttentionBindings(context: DesktopShellContext): DesktopAttentionBindings {
    val chatController = context.core.chatController
    val prefs = context.core.attentionPrefs
    return remember(chatController, prefs) {
        DesktopAttentionBindings(
            runs = chatController.runs,
            prefs = prefs,
            agentNameFor = { agentId ->
                chatController.state.value.conversations.firstOrNull { it.agentId == agentId }?.agentName
            },
            agentIdForConversation = { conversationId ->
                chatController.state.value.conversations.firstOrNull { it.id == conversationId }?.agentId
            },
        )
    }
}

/**
 * Publishes the unified search + routing into the application-scoped quick-query window. Picking a
 * result mirrors the in-app command palette; free text goes to the selected conversation and raises
 * the main window to show the streaming response.
 */
@Composable
private fun DesktopShellQuickQueryBridge(context: DesktopShellContext, frame: DesktopShellFrame) {
    val quickQuery = context.shell.quickQuery
    LaunchedEffect(frame.lists.search) { quickQuery.sources.value = frame.lists.search }
    SideEffect {
        quickQuery.actions.value = DesktopQuickQueryActions(
            onOpen = { hit ->
                activateDesktopWindow(context.shell.window)
                context.router.openSearchHit(hit)
            },
            onSubmitPrompt = { text, ambientContext ->
                context.router.sendToSelectedConversation(quickQueryPrompt(text, ambientContext))
                activateDesktopWindow(context.shell.window)
                context.navigator.navigate(DesktopDestination.Conversations)
            },
        )
    }
}

@Composable
private fun DesktopShellFocusedAgentLoaders(context: DesktopShellContext, frame: DesktopShellFrame) {
    val selectedDestination = context.navigator.selectedDestination
    val selectedAgentId = frame.focus.selectedAgentId
    val skills = context.panels.skills
    // Load the skills registry + the focused agent's installed skills when the
    // Skills page is open (or the focused agent changes).
    LaunchedEffect(selectedDestination, skills, selectedAgentId) {
        if (selectedDestination == DesktopDestination.Agents) skills.reload(selectedAgentId)
    }
    val slashCommandApi = context.panels.httpApis.slashCommandApi
    val agentSlashCommands = context.panels.agentSlashCommands
    // Load the focused agent's server slash commands for the composer palette.
    LaunchedEffect(slashCommandApi, selectedAgentId) {
        agentSlashCommands.value = loadAgentSlashCommands(
            slashCommandApi,
            selectedAgentId?.let(::DesktopAgentId),
        )
    }
}

private data class WorkingAgentNameParams(
    val thinkingAgentId: String?,
    val thinkingConversationId: String?,
    val railAgents: List<Pair<String, String>>,
    val conversations: List<DesktopConversationSummary>,
    val fallback: String,
)

private fun workingAgentName(params: WorkingAgentNameParams): String {
    val byAgent = params.thinkingAgentId?.let { id ->
        params.railAgents.firstOrNull { it.first == id }?.second
    }
    if (byAgent != null) return byAgent
    val byConversation = params.thinkingConversationId?.let { tid ->
        params.conversations.firstOrNull { it.id == tid }?.agentName
    }
    return byConversation ?: params.fallback
}
