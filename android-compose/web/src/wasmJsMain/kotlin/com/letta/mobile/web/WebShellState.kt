package com.letta.mobile.web

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.letta.mobile.data.agents.RecentAgentsPolicy
import com.letta.mobile.data.lens.LensDestination
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.ui.shell.ShellNavDrawerActions
import com.letta.mobile.ui.shell.ShellNavDrawerInput
import com.letta.mobile.ui.shell.ShellNavDrawerMapping
import com.letta.mobile.ui.shell.ShellNavDrawerState
import com.letta.mobile.ui.shell.rail.ShellAgentRailActions
import com.letta.mobile.ui.shell.sidebar.ShellAgentPanelActions
import com.letta.mobile.ui.shell.sidebar.ShellPanelAgent
import com.letta.mobile.web.data.AgentItemState
import kotlin.time.Instant

/** The page the web shell shows beside the shared navigation. */
internal enum class WebDestination { HOME, CHAT, SETTINGS }

/**
 * letta-mobile-ckmub.8: the web container's navigation state. Everything drawn from it is the shared
 * shell ([com.letta.mobile.ui.shell.ShellNavDrawer], the shared chat page, the shared Settings page);
 * this only holds what is selected, the roster the App Server returned and the saved backend.
 */
@Stable
internal class WebShellState(initialConfig: LettaConfig) {
    var config by mutableStateOf(initialConfig)
    var destination by mutableStateOf(WebDestination.CHAT)
    var agents by mutableStateOf<List<AgentItemState>>(emptyList())
        private set
    var selectedAgentId by mutableStateOf<String?>(null)
        private set

    /** Bumped to fetch the roster again (Retry). */
    var rosterRequest by mutableIntStateOf(0)
        private set

    val selectedAgent: AgentItemState? get() = agents.firstOrNull { it.id == selectedAgentId }

    fun selectAgent(agentId: String) {
        selectedAgentId = agentId
        destination = WebDestination.CHAT
    }

    /** A fresh roster keeps the selection when the agent is still there, else opens the first agent. */
    fun rosterLoaded(fetched: List<AgentItemState>) {
        agents = fetched
        selectedAgentId = selectedAgentId?.takeIf { id -> fetched.any { it.id == id } } ?: fetched.firstOrNull()?.id
    }

    fun rosterLost() {
        agents = emptyList()
        selectedAgentId = null
    }

    fun retry() {
        rosterRequest += 1
    }
}

/**
 * The shared navigation drawer's input for the web: every agent on the rail (the web has no activity
 * signal to cut a recents strip by, so the whole roster shows, scrolling), and none of the per-agent
 * sections, which have no web page yet.
 */
internal fun WebShellState.navDrawerInput(): ShellNavDrawerInput {
    val selected = selectedAgent
    return ShellNavDrawerInput(
        agent = ShellPanelAgent(
            name = selected?.name ?: "Letta",
            orbIndex = agents.indexOf(selected).coerceAtLeast(0),
            agentId = selected?.id,
        ),
        agents = agents.map { it.id to it.name },
        recentAgentsPolicy = RecentAgentsPolicy(maxAgents = agents.size.coerceAtLeast(1), fillToCap = true),
        hiddenSections = WebHiddenSections,
    )
}

/** The drawer's state for [WebShellState.destination]: Home and Settings draw selected when open. */
internal fun WebShellState.navDrawerState(now: Instant): ShellNavDrawerState {
    val base = ShellNavDrawerMapping.state(navDrawerInput(), now)
    val home = destination == WebDestination.HOME
    return base.copy(
        rail = base.rail.copy(homeSelected = home),
        panel = base.panel.copy(home = home, settingsSelected = destination == WebDestination.SETTINGS),
    )
}

/** Where the drawer's controls go; [afterNavigate] closes the modal drawer on a narrow window. */
internal fun WebShellState.navDrawerActions(afterNavigate: () -> Unit): ShellNavDrawerActions {
    fun go(destination: WebDestination) {
        this.destination = destination
        afterNavigate()
    }
    return ShellNavDrawerActions(
        rail = ShellAgentRailActions(
            onAgentSelected = { agentId ->
                selectAgent(agentId)
                afterNavigate()
            },
            onHome = { go(WebDestination.HOME) },
            onNewSession = { go(WebDestination.CHAT) },
        ),
        panel = ShellAgentPanelActions(
            onOpenSettings = { go(WebDestination.SETTINGS) },
            onNewChat = { go(WebDestination.CHAT) },
        ),
    )
}

/** Sections without a web page: Memory, Schedules, Channels, Skills and the conversation list. */
internal val WebHiddenSections: Set<LensDestination> = LensDestination.entries.toSet()
