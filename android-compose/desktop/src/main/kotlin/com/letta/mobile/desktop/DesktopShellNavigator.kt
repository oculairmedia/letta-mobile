package com.letta.mobile.desktop

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.data.desktopshell.ConversationTabsReducer
import com.letta.mobile.data.desktopshell.ConversationTabsState
import com.letta.mobile.data.lens.WorkPlayMode
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.search.PaletteItem
import com.letta.mobile.data.search.PaletteItemKind
import com.letta.mobile.desktop.chat.DesktopChatController
import com.letta.mobile.desktop.chat.DesktopChatSurfaceState
import com.letta.mobile.data.home.preferredComposerConversationId
import com.letta.mobile.desktop.home.toFleetConversation

/**
 * letta-mobile-bglj6.1.10: what the desktop shell shows - the destination, the rail, the agent
 * editor, the background-tasks pane and the Work | Play lens - in one holder, so
 * every piece of [LettaDesktopApp] reads and changes the same state.
 *
 * [destinationState] and [railExpandedState] come from `rememberSaveable` in [LettaDesktopApp].
 */
@Stable
internal class DesktopShellNavigator(
    destinationState: MutableState<DesktopDestination>,
    railExpandedState: MutableState<Boolean>,
) {
    var selectedDestination: DesktopDestination by destinationState

    /** Spotify-style library toggle: icon rail or expanded names-and-spaces list. */
    var railExpanded: Boolean by railExpandedState

    /** The agent whose editor is open beside the chat, if any. */
    var editAgentId: String? by mutableStateOf<String?>(null)

    /**
     * Avatar styles chosen via the editor this session, applied immediately to the orbs
     * regardless of whether the backend round-trips agent metadata.
     */
    var avatarOverrides: Map<String, MascotIdentity> by mutableStateOf(emptyMap<String, MascotIdentity>())

    var showBackgroundTasks: Boolean by mutableStateOf(false)

    /** Work | Play presentation lens over the same agents/memory/conversations. */
    var workPlayMode: WorkPlayMode by mutableStateOf(WorkPlayMode.Work)

    /** Leaves any agent editor, then shows [destination]. */
    fun navigate(destination: DesktopDestination) {
        editAgentId = null
        selectedDestination = destination
    }

    /** The editor saved [identity] for the agent it had open: show it at once, and close the editor. */
    fun recordSavedIdentity(identity: MascotIdentity) {
        avatarOverrides = avatarOverrides + (editAgentId.orEmpty() to identity)
        editAgentId = null
    }
}

/** A prompt typed into Home's chatbox, and the agents it can go to. */
internal data class DesktopHomePrompt(
    val text: String,
    /** The focused agent, whose newest conversation takes the prompt first. */
    val focusedAgentId: String?,
    /** With nothing focused, the roster's first agent gets a new conversation. */
    val rosterAgentId: String?,
)

/**
 * The shell's navigation verbs: every surface (rail, sidebar, palette, tabs, Home, deep links)
 * opens conversations and agents through here, so "leave the editor, select, show the chat"
 * stays one rule. [chatState] is read when a verb runs, never captured.
 */
internal class DesktopShellRouter(
    private val navigator: DesktopShellNavigator,
    private val chatController: DesktopChatController,
    private val chatState: State<DesktopChatSurfaceState>,
) {
    /** Leaves any editor and shows [conversationId] in the chat pane. */
    fun openConversation(conversationId: ConversationId) {
        navigator.editAgentId = null
        chatController.selectConversation(conversationId.value)
        navigator.selectedDestination = DesktopDestination.Conversations
    }

    /**
     * Single entry point for "open this agent" from any surface (rail, command palette): select
     * its most-recent loaded conversation, or - for a roster-only agent with none loaded (e.g.
     * bulk-imported) - create its first chat. createConversationForAgent serializes rapid opens.
     */
    fun openAgent(agentId: AgentId) {
        navigator.editAgentId = null
        val existing = chatState.value.conversations
            .filter { it.agentId == agentId.value }
            .maxByOrNull { conversationRecency(it.updatedAtLabel) }
        if (existing != null) {
            chatController.selectConversation(existing.id)
        } else {
            chatController.createConversationForAgent(agentId.value)
        }
        navigator.selectedDestination = DesktopDestination.Conversations
    }

    /** A new chat with [agentId], or with the conversation-derived agent when there is none. */
    fun openNewChat(agentId: AgentId?) {
        navigator.editAgentId = null
        navigator.selectedDestination = DesktopDestination.Conversations
        // Target the focused agent explicitly - for a roster-only agent,
        // createConversation()'s conversation-derived agent id would miss it.
        agentId?.value
            ?.let(chatController::createConversationForAgent)
            ?: chatController.createConversation()
    }

    /**
     * Home's chatbox reuses the shell's chat pipeline rather than owning a second one: pick the
     * conversation the prompt belongs to (focused agent's newest, else the fleet's newest), hand
     * the text to the controller's select-then-send path, and follow it to the chat pane. With no
     * conversation at all the text is staged in the real composer instead of being dropped.
     */
    fun submitHomePrompt(prompt: DesktopHomePrompt) {
        val text = prompt.text.trim()
        if (text.isEmpty()) return
        navigator.editAgentId = null
        val conversations = chatState.value.conversations.map { it.toFleetConversation() }
        val target = preferredComposerConversationId(conversations, prompt.focusedAgentId)
        if (target != null) {
            chatController.replyFromNotification(target, text)
        } else {
            val agentId = prompt.focusedAgentId ?: prompt.rosterAgentId
            startConversation(text, agentId?.let(::AgentId))
        }
        navigator.selectedDestination = DesktopDestination.Conversations
    }

    private fun startConversation(text: String, agentId: AgentId?) {
        if (agentId != null) {
            chatController.createConversationForAgent(agentId.value) { conversationId ->
                chatController.replyFromNotification(conversationId, text)
            }
        } else {
            chatController.updateComposerText(text)
        }
    }

    /** Opens a quick-query pick the way the in-app command palette does. */
    fun openPaletteItem(item: PaletteItem) {
        when (item.kind) {
            PaletteItemKind.Conversation -> openConversation(ConversationId(item.id))
            PaletteItemKind.Agent -> openAgent(AgentId(item.id))
            PaletteItemKind.Destination ->
                DesktopDestination.entries.firstOrNull { it.name == item.id }?.let(navigator::navigate)
        }
    }

    /**
     * Sends [prompt] to the selected conversation. No conversation yet: never drop the typed
     * prompt - stage it in the composer for the user to send.
     */
    fun sendToSelectedConversation(prompt: String) {
        val target = chatState.value.selectedConversationId
        if (target != null) {
            chatController.replyFromNotification(target, prompt)
        } else {
            chatController.updateComposerText(prompt)
        }
    }

    /**
     * Closes [conversationId]'s tab in [tabs]. Closing the active tab shows the tab the reducer
     * falls back to, or Home when none is left.
     */
    fun closeConversationTab(tabs: MutableState<ConversationTabsState>, conversationId: ConversationId) {
        val result = ConversationTabsReducer.close(tabs.value, conversationId.value)
        val closingActiveTab = conversationId.value == chatState.value.selectedConversationId
        tabs.value = result.state
        if (!closingActiveTab) return
        val fallbackId = result.fallbackConversationId
        if (fallbackId != null) {
            openConversation(ConversationId(fallbackId))
        } else {
            navigator.selectedDestination = DesktopDestination.Home
        }
    }
}
