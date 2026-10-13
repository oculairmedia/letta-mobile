package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import com.letta.mobile.data.desktopshell.ConversationTabsState
import com.letta.mobile.data.dreaming.DreamNotifyPrefs
import com.letta.mobile.data.model.ConversationId
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
    tabs: MutableState<ConversationTabsState>,
) {
    DesktopShellChatEffects(context, frame, tabs)
    DesktopShellNucleusEffects(context, frame)
    DesktopShellQuickQueryBridge(context, frame)
    DesktopShellFocusedAgentLoaders(context, frame)
}

@Composable
private fun DesktopShellChatEffects(
    context: DesktopShellContext,
    frame: DesktopShellFrame,
    tabs: MutableState<ConversationTabsState>,
) {
    val chatController = context.core.chatController
    val chatState = frame.chatState
    val selectedDestination = context.navigator.selectedDestination
    // letta-mobile folder-settings #2: re-read the working directory whenever
    // the selected conversation changes (or its identity resolves for the
    // first time after a fresh connect).
    LaunchedEffect(chatState.selectedConversationId, chatState.selectedConversation?.agentId) {
        chatController.refreshSelectedConversationWorkingDirectory()
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
    SyncConversationTabs(chatState, selectedDestination, tabs.value) { tabs.value = it }
}

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
 * Publishes palette data + routing into the application-scoped quick-query window. Selecting an
 * item mirrors the in-app command palette; free text goes to the selected conversation and raises
 * the main window to show the streaming response.
 */
@Composable
private fun DesktopShellQuickQueryBridge(context: DesktopShellContext, frame: DesktopShellFrame) {
    val quickQuery = context.shell.quickQuery
    val window = context.shell.window
    val router = context.router
    val sources = frame.lists.search
    LaunchedEffect(sources) { quickQuery.sources.value = sources }
    SideEffect {
        quickQuery.actions.value = DesktopQuickQueryActions(
            onOpen = { hit ->
                activateDesktopWindow(window)
                router.openSearchHit(hit)
            },
            onSubmitPrompt = { text, ambientContext ->
                router.sendToSelectedConversation(quickQueryPrompt(text, ambientContext))
                activateDesktopWindow(window)
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
