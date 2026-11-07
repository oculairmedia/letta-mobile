package com.letta.mobile.data.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.letta.mobile.data.desktopshell.ConversationTabsReducer
import com.letta.mobile.data.home.conversationRecency
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.lanes.ConversationPurpose
import com.letta.mobile.data.shell.focus.CurrentFocus
import com.letta.mobile.data.shell.focus.CurrentFocusChatMode

/**
 * What the shell's navigation needs from a host's chat. Read when a verb runs, never captured: the
 * conversations and selection are the chat's current ones.
 */
interface ShellChatPort {
    /** Every conversation the chat knows. */
    fun conversations(): List<ShellConversationRef>

    val selectedConversation: ConversationId?

    fun selectConversation(conversation: ConversationId)

    /** Makes [agent]'s first (or next) conversation; [onCreated] gets it once it exists. */
    fun createConversationForAgent(agent: AgentId, onCreated: (ConversationId) -> Unit = {})

    /** A new chat started from the shell's "new chat" controls: with [agent], or the chat's own default. */
    fun startNewChat(agent: AgentId?)

    /** Sends [text] into [conversation], selecting it. */
    fun send(conversation: ConversationId, text: String)

    /** Puts [text] in the composer for the person to send. */
    fun stageComposerText(text: String)
}

/**
 * The chat of a host whose conversation owner is rebuilt with each backend session (the web's):
 * the navigator outlives a connection, so it talks to this, which follows the current [owner];
 * with none (no session) the verbs do nothing.
 */
class SessionShellChatPort : ShellChatPort {
    var owner: ShellChatPort? by mutableStateOf(null)

    override fun conversations(): List<ShellConversationRef> = owner?.conversations().orEmpty()

    override val selectedConversation: ConversationId? get() = owner?.selectedConversation

    override fun selectConversation(conversation: ConversationId) {
        owner?.selectConversation(conversation)
    }

    override fun createConversationForAgent(agent: AgentId, onCreated: (ConversationId) -> Unit) {
        owner?.createConversationForAgent(agent, onCreated)
    }

    override fun startNewChat(agent: AgentId?) {
        owner?.startNewChat(agent)
    }

    override fun send(conversation: ConversationId, text: String) {
        owner?.send(conversation, text)
    }

    override fun stageComposerText(text: String) {
        owner?.stageComposerText(text)
    }
}

/** A prompt typed into Home's chatbox, and the agents it can go to. */
data class ShellHomePrompt(
    val text: String,
    /** The focused agent, whose newest conversation takes the prompt first. */
    val focusedAgent: AgentId?,
    /** Legacy roster suggestion, retained for compatibility; it cannot choose the current agent. */
    val rosterAgent: AgentId?,
)

/**
 *  (W3): the shell's navigation verbs. Every surface (rail, sidebar, palette,
 * tabs, Home, deep links) on every client opens conversations and agents through here, so "leave the
 * editor, select, show the chat" stays one rule over one [state].
 */
class ShellWorkspaceNavigator(
    val state: ShellWorkspaceState,
    private val chat: ShellChatPort,
) {
    /** Dispatch a destination to the workspace's atomic transition owner. */
    fun navigate(destination: ShellDestination) = state.navigate(destination)

    /** Commit exact identity before asking a port to load it. An unknown owner stays unresolved. */
    fun openConversation(conversation: ConversationId) {
        val references = chat.conversations().filter { it.id == conversation }
        val owners = references.mapNotNull { it.agent }.distinct()
        val agent = owners.singleOrNull()
        if (!state.selectFocus(CurrentFocus("chat", agent, conversation, CurrentFocusChatMode.Exact))) return
        if (agent != null) {
            state.completeFocus(state.requestToken(), conversation, agent, references.singleOrNull())
            chat.selectConversation(conversation)
        }
    }

    /** Resume this agent only. No eligible conversation means unresolved/empty, never implicit creation. */
    fun openAgent(agent: AgentId) {
        if (!state.selectFocus(CurrentFocus("chat", agent))) return
        val token = state.requestToken()
        val conversations = chat.conversations()
        val existing = state.resolveAgentChat(agent, conversations) ?: return
        val reference = conversations.singleOrNull { it.id == existing && it.agent == agent }
        if (state.completeFocus(token, existing, agent, reference)) chat.selectConversation(existing)
    }

    /** Explicit New keeps null distinct from Resume; ports must return token-bound completions. */
    fun openNewChat(agent: AgentId?) {
        val target = agent ?: state.currentFocus.agentId
        if (!state.selectFocus(CurrentFocus("chat", target, chatMode = CurrentFocusChatMode.New))) return
        if (target != null && state.agentAvailability != FocusAvailability.Unavailable) chat.startNewChat(target)
    }

    /** Home send is an explicit operation. Capture creation identity before starting its effect. */
    fun submitHomePrompt(prompt: ShellHomePrompt) {
        val text = prompt.text.trim()
        if (text.isEmpty()) return
        if (prompt.focusedAgent == state.currentFocus.agentId && state.agentAvailability == FocusAvailability.Unavailable) {
            chat.stageComposerText(text)
            return
        }
        val conversations = chat.conversations().filter {
            it.agent != null && it.lane.canTarget && it.lane.purpose != ConversationPurpose.INBOX
        }
        val focused = prompt.focusedAgent?.let { agent -> newest(conversations.filter { it.agent == agent }) }
        val target = if (prompt.focusedAgent == null) newest(conversations) else focused
        if (target != null) {
            openConversation(target)
            if (state.selectedConversationId == target) chat.send(target, text)
        } else {
            startConversation(text, prompt.focusedAgent)
        }
    }

    /** Read the committed owner, never a port's independently selected conversation. */
    fun sendToSelectedConversation(prompt: String) {
        val focus = state.currentFocus
        val target = focus.conversationId
        if (target == null) {
            chat.stageComposerText(prompt)
        } else {
            val available = state.agentAvailability != FocusAvailability.Unavailable &&
                state.conversationAvailability != FocusAvailability.Unavailable
            val ownedTarget = chat.conversations().any { it.id == target && it.agent == focus.agentId && it.lane.canTarget }
            if (available && ownedTarget) chat.send(target, prompt)
        }
    }

    /** Adjacent close is one owner transition; a port cannot supply a different active tab. */
    fun closeConversationTab(conversation: ConversationId) {
        val active = state.selectedConversationId == conversation
        val knownOwners = chat.conversations().mapNotNull { ref -> ref.agent?.let { ref.id to it } }.toMap()
        state.conversationsReported(knownOwners, emptySet())
        state.closeConversationTab(conversation)
        val next = state.currentFocus
        if (active && next.agentId != null) next.conversationId?.let(chat::selectConversation)
    }

    /** Keep the preview until explicitly unpinned or closed. */
    fun pinConversationTab(conversation: ConversationId) {
        state.tabs = ConversationTabsReducer.pin(state.tabs, state.tabs.openConversationIds.indexOf(conversation.value))
    }

    /** Unpin the owner-selected tab to preview, or close an inactive pinned tab. */
    fun unpinConversationTab(conversation: ConversationId) {
        state.tabs = ConversationTabsReducer.unpin(
            state.tabs, state.tabs.pinnedConversationIds.indexOf(conversation.value), conversation == state.selectedConversationId,
        )
    }

    /** Reorder within the pinned group; a preview keeps its trailing position. */
    fun reorderConversationTab(conversation: ConversationId, targetIndex: Int) {
        state.tabs = ConversationTabsReducer.reorder(state.tabs, conversation.value, targetIndex)
    }

    private fun startConversation(text: String, agent: AgentId?) {
        if (!state.selectFocus(CurrentFocus("chat", agent, chatMode = CurrentFocusChatMode.New))) return
        val token = state.requestToken()
        if (agent != null) {
            chat.createConversationForAgent(agent) { created ->
                if (state.completeFocus(token, created, agent)) chat.send(created, text)
            }
        } else {
            chat.stageComposerText(text)
        }
    }

    private fun newest(conversations: List<ShellConversationRef>): ConversationId? =
        (conversations.filter { it.lane.purpose == ConversationPurpose.HOME }
            .ifEmpty { conversations }).maxByOrNull { conversationRecency(it.updatedAtLabel) }?.id
}
