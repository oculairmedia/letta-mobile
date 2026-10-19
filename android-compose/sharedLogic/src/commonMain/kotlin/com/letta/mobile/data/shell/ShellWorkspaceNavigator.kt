package com.letta.mobile.data.shell

import com.letta.mobile.data.desktopshell.ConversationTabsReducer
import com.letta.mobile.data.home.conversationRecency
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId

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

/** A prompt typed into Home's chatbox, and the agents it can go to. */
data class ShellHomePrompt(
    val text: String,
    /** The focused agent, whose newest conversation takes the prompt first. */
    val focusedAgent: AgentId?,
    /** With nothing focused, the roster's first agent gets a new conversation. */
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
    /** Shows a destination; see [ShellWorkspaceState.navigate]. */
    fun navigate(destination: ShellDestination) = state.navigate(destination)

    /** Leaves any editor and shows [conversation] in the chat. */
    fun openConversation(conversation: ConversationId) {
        state.editAgentId = null
        chat.selectConversation(conversation)
        state.showChat()
    }

    /**
     * "Open this agent" from any surface: its last active conversation, else its newest, else - for
     * a roster-only agent with none - its first chat ([ShellWorkspaceState.resolveAgentChat]).
     */
    fun openAgent(agent: AgentId) {
        state.editAgentId = null
        state.focusedAgentId = agent
        val existing = state.resolveAgentChat(agent, chat.conversations())
        if (existing != null) chat.selectConversation(existing) else chat.createConversationForAgent(agent)
        state.showChat()
    }

    /** A new chat with [agent], or with the chat's own default agent when there is none. */
    fun openNewChat(agent: AgentId?) {
        state.editAgentId = null
        agent?.let { state.focusedAgentId = it }
        state.showChat()
        chat.startNewChat(agent)
    }

    /**
     * Home's chatbox reuses the chat rather than owning a second one: the focused agent's newest
     * conversation takes the prompt, else the fleet's newest; with none at all, a new conversation
     * for the focused (or first) agent, else the composer holds the text. The chat then shows.
     */
    fun submitHomePrompt(prompt: ShellHomePrompt) {
        val text = prompt.text.trim()
        if (text.isEmpty()) return
        state.editAgentId = null
        val conversations = chat.conversations()
        val focused = prompt.focusedAgent?.let { agent -> newest(conversations.filter { it.agent == agent }) }
        val target = focused ?: newest(conversations)
        if (target != null) {
            chat.send(target, text)
        } else {
            startConversation(text, prompt.focusedAgent ?: prompt.rosterAgent)
        }
        state.showChat()
    }

    /** Sends [prompt] to the selected conversation; with none, the composer holds it. */
    fun sendToSelectedConversation(prompt: String) {
        val target = chat.selectedConversation
        if (target != null) chat.send(target, prompt) else chat.stageComposerText(prompt)
    }

    /** Closes [conversation]'s tab; closing the active tab shows the tab beside it, or Home when none is left. */
    fun closeConversationTab(conversation: ConversationId) {
        val result = ConversationTabsReducer.close(state.tabs, conversation.value)
        val closingActiveTab = conversation == chat.selectedConversation
        state.tabs = result.state
        if (!closingActiveTab) return
        val fallback = result.fallbackConversationId
        if (fallback != null) openConversation(ConversationId(fallback)) else state.showPage(ShellDestination.Home)
    }

    /** Moves [conversation]'s tab to [targetIndex]. */
    fun reorderConversationTab(conversation: ConversationId, targetIndex: Int) {
        state.tabs = ConversationTabsReducer.reorder(state.tabs, conversation.value, targetIndex)
    }

    private fun startConversation(text: String, agent: AgentId?) {
        if (agent != null) {
            chat.createConversationForAgent(agent) { created -> chat.send(created, text) }
        } else {
            chat.stageComposerText(text)
        }
    }

    private fun newest(conversations: List<ShellConversationRef>): ConversationId? =
        conversations.maxByOrNull { conversationRecency(it.updatedAtLabel) }?.id
}
