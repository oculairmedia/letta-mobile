package com.letta.mobile.web

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.letta.mobile.data.agents.RecentAgentsPolicy
import com.letta.mobile.data.lens.LensDestination
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.ShellRoute
import com.letta.mobile.ui.shell.ShellNavDrawerActions
import com.letta.mobile.ui.shell.ShellNavDrawerInput
import com.letta.mobile.ui.shell.ShellNavDrawerMapping
import com.letta.mobile.ui.shell.ShellNavDrawerState
import com.letta.mobile.ui.shell.rail.ShellAgentRailActions
import com.letta.mobile.ui.shell.sidebar.ShellAgentPanelActions
import com.letta.mobile.ui.shell.sidebar.ShellPanelAgent
import kotlin.time.Instant

/**
 * letta-mobile-ckmub.8: the web container's navigation state. Everything drawn from it is the shared
 * shell ([com.letta.mobile.ui.shell.ShellNavDrawer] and the shared ShellPage); this only holds what
 * is selected, the roster the App Server returned and the saved backend.
 *
 * letta-mobile-ckmub.3.4: the web's route model is the shared [ShellDestination] itself, serialized
 * as the canonical [ShellRoute] in the page's URL fragment ([route] / [openRoute]).
 */
@Stable
internal class WebShellState(initialConfig: LettaConfig) {
    var config by mutableStateOf(initialConfig)
    var destination by mutableStateOf<ShellDestination>(ShellDestination.Chat())
    var agents by mutableStateOf<List<Agent>>(emptyList())
        private set
    var selectedAgentId by mutableStateOf<String?>(null)
        private set

    /** Bumped to fetch the roster again (Retry). */
    var rosterRequest by mutableIntStateOf(0)
        private set

    val selectedAgent: Agent? get() = agents.firstOrNull { it.id.value == selectedAgentId }

    /** The canonical route of [destination]; the chat route carries the selected agent. */
    val route: String
        get() = ShellRoute.encode((destination as? ShellDestination.Chat)?.copy(agentId = selectedAgentId) ?: destination)

    fun selectAgent(agentId: String) {
        selectedAgentId = agentId
        destination = ShellDestination.Chat(agentId)
    }

    /** Settings' back: the chat with the selected agent. */
    fun closeSettings() {
        destination = ShellDestination.Chat(selectedAgentId)
    }

    /** Opens a canonical route (the URL fragment, kept in step by LettaWebApp); false when it names no destination. */
    fun openRoute(route: String): Boolean {
        val target = ShellRoute.decode(route) ?: return false
        target.agentId?.takeIf { target is ShellDestination.Chat }?.let { selectedAgentId = it }
        destination = target
        return true
    }

    /** A fresh roster keeps the selection when the agent is still there, else opens the first agent. */
    fun rosterLoaded(fetched: List<Agent>) {
        agents = fetched
        selectedAgentId = selectedAgentId?.takeIf { id -> fetched.any { it.id.value == id } } ?: fetched.firstOrNull()?.id?.value
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
            agentId = selected?.id?.value,
        ),
        agents = agents.map { it.id.value to it.name },
        recentAgentsPolicy = RecentAgentsPolicy(maxAgents = agents.size.coerceAtLeast(1), fillToCap = true),
        hiddenSections = WebHiddenSections,
    )
}

/** The drawer's state for [WebShellState.destination]: Home and Settings draw selected when open. */
internal fun WebShellState.navDrawerState(now: Instant): ShellNavDrawerState {
    val base = ShellNavDrawerMapping.state(navDrawerInput(), now)
    val home = destination == ShellDestination.Home
    return base.copy(
        rail = base.rail.copy(homeSelected = home),
        panel = base.panel.copy(home = home, settingsSelected = destination is ShellDestination.Settings),
    )
}

/** Where the drawer's controls go; [afterNavigate] closes the modal drawer on a narrow window. */
internal fun WebShellState.navDrawerActions(afterNavigate: () -> Unit): ShellNavDrawerActions {
    fun go(destination: ShellDestination) {
        this.destination = destination
        afterNavigate()
    }
    return ShellNavDrawerActions(
        rail = ShellAgentRailActions(
            onAgentSelected = { agentId ->
                selectAgent(agentId)
                afterNavigate()
            },
            onHome = { go(ShellDestination.Home) },
            onNewSession = { go(ShellDestination.Chat(selectedAgentId)) },
        ),
        panel = ShellAgentPanelActions(
            onOpenSettings = { go(ShellDestination.Settings()) },
            onNewChat = { go(ShellDestination.Chat(selectedAgentId)) },
        ),
    )
}

/** Sections without a web page: Memory, Schedules, Channels, Skills and the conversation list. */
internal val WebHiddenSections: Set<LensDestination> = LensDestination.entries.toSet()
