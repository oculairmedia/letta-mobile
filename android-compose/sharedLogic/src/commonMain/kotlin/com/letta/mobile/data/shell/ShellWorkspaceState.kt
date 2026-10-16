package com.letta.mobile.data.shell

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.letta.mobile.data.desktopshell.ConversationTabsReducer
import com.letta.mobile.data.desktopshell.ConversationTabsState
import com.letta.mobile.data.home.conversationRecency
import com.letta.mobile.data.lens.WorkPlayMode
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId

/**
 * letta-mobile-ckmub.17.3 (W3): what the shell shows, in one holder every client binds - the page,
 * the agent editor beside the chat, the focused agent and selected conversation, the conversation
 * tabs, the overlays and the route. It replaces the desktop's `DesktopShellNavigator` and the web's
 * `WebShellState`; hosts only translate their own route models (the desktop's `DesktopDestination`,
 * the browser's URL fragment) and report what their chat selected.
 *
 * [page] is the main pane's destination and is never an [ShellDestination.AgentEditor]: the editor is
 * a panel beside the chat ([agentEditor]). [destination] is the two together, as the shared
 * `ShellPage` and [route] see them.
 */
@Stable
class ShellWorkspaceState(initialPage: ShellDestination = ShellDestination.Home) {
    /** The main pane's destination. */
    var page: ShellDestination by mutableStateOf(initialPage.asPage())
        private set

    /** The agent editor open beside the chat, if any: an agent's, or the new-agent flow. */
    var agentEditor: ShellDestination.AgentEditor? by mutableStateOf(initialPage as? ShellDestination.AgentEditor)

    /** The agent whose editor is open; setting null closes any editor, the new-agent flow too. */
    var editAgentId: AgentId?
        get() = agentEditor?.agentId?.let(::AgentId)
        set(value) {
            agentEditor = value?.let { ShellDestination.AgentEditor(it.value) }
        }

    /** The agent the shell is about. A host whose chat knows better refines it ([focusFor]). */
    var focusedAgentId: AgentId? by mutableStateOf(initialPage.agentId?.let(::AgentId))

    /** The conversation the chat shows, as the host's chat reports it ([conversationShown]). */
    var selectedConversationId: ConversationId? by mutableStateOf(initialPage.conversationId?.let(::ConversationId))
        private set

    /** The conversation tab strip, in strip order. */
    var tabs: ConversationTabsState by mutableStateOf(ConversationTabsState())

    /** The library rail: icons only, or names and spaces. */
    var railExpanded: Boolean by mutableStateOf(false)

    /** Work | Play presentation lens over the same agents, memory and conversations. */
    var workPlayMode: WorkPlayMode by mutableStateOf(WorkPlayMode.Work)

    /** The background-tasks side pane. */
    var showBackgroundTasks: Boolean by mutableStateOf(false)

    /**
     * A scan-to-pair link opened from outside, waiting for the Settings pairing card. Memory only: it
     * carries a one-time secret, so it never rides [route].
     */
    var pendingPairLink: String? by mutableStateOf<String?>(null)

    /** The overlay stack's flags. */
    val overlays: ShellOverlays = ShellOverlays()

    /** Each agent's last shown conversation: where selecting the agent goes back to. */
    private var lastActiveByAgent: Map<AgentId, ConversationId> by mutableStateOf(emptyMap())

    /** The session the state belongs to ([sessionChanged]). */
    private var session: String? = null

    /** The page and the editor beside it, with the focus filled in: what the shared ShellPage draws. */
    val destination: ShellDestination
        get() = agentEditor ?: pageFor(focusedAgentId, selectedConversationId)

    /** The canonical [ShellRoute] of [destination]: the web's URL fragment, a desktop deep link's path. */
    val route: String get() = ShellRoute.encode(destination)

    /** [page] refined by the focus: the agent pages are the focused agent's, the chat its conversation. */
    fun pageFor(agent: AgentId?, conversation: ConversationId?): ShellDestination =
        page.refinedBy(agent?.value, conversation?.value)

    /** Leaves any editor and shows [target]; an agent editor opens beside the chat instead. */
    fun navigate(target: ShellDestination) {
        if (target is ShellDestination.AgentEditor) {
            agentEditor = target
            page = ShellDestination.Chat()
        } else {
            agentEditor = null
            page = target.asPage()
        }
        target.agentId?.let { focusedAgentId = AgentId(it) }
        target.conversationId?.let { selectedConversationId = ConversationId(it) }
    }

    /** Shows [target] in the main pane, leaving the editor as it is; the same kind of page stays as it is. */
    fun showPage(target: ShellDestination) {
        if (target.kind != page.kind) page = target.asPage()
    }

    /** Shows the chat (a board counts as the chat's page), leaving the editor as it is. */
    fun showChat() {
        if (!page.isChatPage) page = ShellDestination.Chat()
    }

    /** Opens the new-agent flow beside the chat. */
    fun openNewAgent() {
        agentEditor = ShellDestination.AgentEditor()
        page = ShellDestination.Chat()
    }

    /** Opens a canonical route (the URL fragment, a deep link's path); false when it names no destination. */
    fun openRoute(route: String): Boolean {
        val target = ShellRoute.decode(route) ?: return false
        navigate(target)
        return true
    }

    /**
     * The host's chat now shows [conversation] (of [agent]): remember it as the agent's last active
     * one and give it a tab while the chat is the page.
     */
    fun conversationShown(conversation: ConversationId?, agent: AgentId?) {
        selectedConversationId = conversation
        if (conversation == null) return
        if (agent != null) lastActiveByAgent = lastActiveByAgent + (agent to conversation)
        if (page.isChatPage && conversation.value !in tabs.openConversationIds) {
            tabs = ConversationTabsReducer.select(tabs, conversation.value)
        }
    }

    /** The backend's conversation list is authoritative again: drop tabs and memories of gone conversations. */
    fun conversationsReported(available: Set<ConversationId>) {
        val availableValues = available.mapTo(mutableSetOf()) { it.value }
        tabs = ConversationTabsReducer.retainAvailable(tabs, availableValues, selectedConversationId?.value)
        lastActiveByAgent = lastActiveByAgent.filterValues { it in available }
    }

    /** A fresh roster keeps the focused agent while it is still there, else focuses the first agent. */
    fun rosterChanged(agents: List<AgentId>) {
        focusedAgentId = focusedAgentId?.takeIf { it in agents } ?: agents.firstOrNull()
    }

    /**
     * The focused agent for a host whose chat has a selection: the selected conversation's agent,
     * else [focusedAgentId] while it is on [roster], else the roster's head.
     */
    fun focusFor(selectedConversationAgent: AgentId?, roster: List<AgentId>): AgentId? =
        selectedConversationAgent
            ?: focusedAgentId?.takeIf { it in roster }
            ?: roster.firstOrNull()

    /**
     * Where selecting [agent] goes: its last active conversation while it still exists, else its
     * most recent one in [conversations]; null means the agent has none, so a new one is made.
     */
    fun resolveAgentChat(agent: AgentId, conversations: List<ShellConversationRef>): ConversationId? {
        val own = conversations.filter { it.agent == agent }
        val lastActive = lastActiveByAgent[agent]?.takeIf { id -> own.any { it.id == id } }
        return lastActive ?: own.maxByOrNull { conversationRecency(it.updatedAtLabel) }?.id
    }

    /**
     * The shell now runs over the session [key] names (a backend switch): the focus, the selection,
     * the tabs and the editor belonged to the old one, so they go; the page stays, without its ids.
     * The first key only records itself.
     */
    fun sessionChanged(key: String) {
        val previous = session
        session = key
        if (previous == null || previous == key) return
        focusedAgentId = null
        selectedConversationId = null
        tabs = ConversationTabsState()
        lastActiveByAgent = emptyMap()
        agentEditor = null
        page = page.refinedBy(agent = null, conversation = null)
    }
}

/** The overlay stack's flags: each is open or not. */
@Stable
class ShellOverlays {
    var commandPalette: Boolean by mutableStateOf(false)
    var modelPicker: Boolean by mutableStateOf(false)

    /** The "Models" exposure editor behind the picker's "Edit Models…". */
    var modelsEditor: Boolean by mutableStateOf(false)
    var newConversation: Boolean by mutableStateOf(false)

    /** `/rename`'s dialog for the open chat. */
    var renameChat: Boolean by mutableStateOf(false)
    var irohResetConfirm: Boolean by mutableStateOf(false)
}

/** What the shell knows of a conversation to route to it: whose it is and how recent. */
data class ShellConversationRef(
    val id: ConversationId,
    val agent: AgentId?,
    /** ISO-8601 update time, or a sentinel label ("Queued") for local rows. */
    val updatedAtLabel: String,
)

/** The chat and a board are the same page; the board is a panel of it. */
private val ShellDestination.isChatPage: Boolean
    get() = this is ShellDestination.Chat || this is ShellDestination.Canvas

/** A destination as the main pane's page: an agent editor is a panel beside the chat. */
private fun ShellDestination.asPage(): ShellDestination =
    if (this is ShellDestination.AgentEditor) ShellDestination.Chat() else this

/** This page about the agent and conversation (route values): the board's page is its chat. */
private fun ShellDestination.refinedBy(agent: String?, conversation: String?): ShellDestination = when (this) {
    is ShellDestination.Chat, is ShellDestination.Canvas -> ShellDestination.Chat(agent, conversation)
    is ShellDestination.Memory -> ShellDestination.Memory(agent)
    is ShellDestination.Schedules -> ShellDestination.Schedules(agent)
    is ShellDestination.Skills -> copy(agentId = agent)
    else -> this
}
