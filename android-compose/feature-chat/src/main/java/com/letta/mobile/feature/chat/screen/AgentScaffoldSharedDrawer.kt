package com.letta.mobile.feature.chat.screen

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.data.agents.RecentAgents
import com.letta.mobile.data.chat.routing.pickOtherAgentConversation
import com.letta.mobile.data.lens.LensDestination
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.ui.mascot.LocalMascotRegistry
import com.letta.mobile.ui.shell.ShellNavDrawer
import com.letta.mobile.ui.shell.ShellNavDrawerActions
import com.letta.mobile.ui.shell.ShellNavDrawerInput
import com.letta.mobile.ui.shell.ShellNavDrawerMapping
import com.letta.mobile.ui.shell.rail.ShellAgentRailActions
import com.letta.mobile.ui.shell.sidebar.ShellAgentPanelActions
import com.letta.mobile.ui.shell.sidebar.ShellPanelAgent
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Sections Android has no page for yet: their rows stay out of the drawer until it does.
 * Channels is desktop-only today.
 */
internal val AndroidHiddenDrawerSections: Set<LensDestination> = setOf(LensDestination.Channels)

/**
 * The hamburger's drawer when the shared navigation drawer is on (letta-mobile-c3np7.5.5): the
 * desktop's agent rail and agent panel ([ShellNavDrawer]) over Android's agents, the agent's
 * conversations and the canvas library, navigating through the chat's existing callbacks.
 */
@Composable
internal fun AgentScaffoldSharedDrawerSheet(state: AgentScaffoldRuntimeState, drawer: SharedNavDrawerViewModel) {
    val canvases by drawer.canvases.collectAsStateWithLifecycle()
    val archiveFilter by drawer.archiveFilter.collectAsStateWithLifecycle()
    val pinnedAgentIds by drawer.pinnedAgentIds.collectAsStateWithLifecycle()
    val agentActivity by drawer.agentActivity.collectAsStateWithLifecycle()
    val identities = LocalMascotRegistry.current.identities
    val open = state.drawerState.isOpen
    LaunchedEffect(open) {
        if (open) {
            drawer.refreshCanvases()
            drawer.refreshAgentActivity()
        }
    }
    val roster = remember(state.switchableAgents, state.favoriteAgentId, pinnedAgentIds, agentActivity) {
        SharedDrawerRoster(
            agents = state.switchableAgents,
            favoriteAgentId = state.favoriteAgentId,
            pinnedAgentIds = pinnedAgentIds,
            conversationActivity = agentActivity,
        )
    }
    val input = ShellNavDrawerInput(
        agent = ShellPanelAgent(name = state.agentName.ifBlank { "Agent" }, agentId = state.agentIdValue),
        identities = identities.toMap(),
        conversations = state.drawerConversations,
        openConversationId = state.conversationId,
        archiveFilter = archiveFilter,
        canvases = canvases,
        hiddenSections = AndroidHiddenDrawerSections,
    ).withRoster(roster)
    // Relative times are taken when the drawer opens; they do not tick while it is open.
    val now = remember(open) { Clock.System.now() }
    val drawerState = remember(input, now) { ShellNavDrawerMapping.state(input, now) }
    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
        ShellNavDrawer(
            state = drawerState,
            actions = rememberSharedDrawerActions(state, drawer),
            modifier = Modifier.testTag(AgentScaffoldTestTags.DRAWER_CONTENT),
        )
    }
}

/** Android's agents as the drawer's rail sees them: the roster, its pins, its favourite and their activity. */
internal data class SharedDrawerRoster(
    val agents: List<Agent>,
    val favoriteAgentId: String? = null,
    val pinnedAgentIds: Set<String> = emptySet(),
    /** Each agent's newest conversation across the fleet ([SharedNavDrawerViewModel.agentActivity]). */
    val conversationActivity: Map<String, Instant> = emptyMap(),
)

/**
 * The whole roster goes in; [ShellNavDrawerMapping] cuts it to the desktop rail's recents strip
 * ([RecentAgents.cut]). An agent's activity is the newest of its conversations and its own record's
 * last run / update; agents with none still fill the strip to its cap, in roster order.
 */
internal fun ShellNavDrawerInput.withRoster(roster: SharedDrawerRoster): ShellNavDrawerInput = copy(
    agents = roster.agents.map { it.id.value to it.name },
    agentLastActiveAt = RecentAgents.merge(roster.conversationActivity, RecentAgents.lastActiveAt(roster.agents)),
    favoriteAgentId = roster.favoriteAgentId,
    pinnedAgentIds = roster.pinnedAgentIds,
)

@Composable
private fun rememberSharedDrawerActions(
    state: AgentScaffoldRuntimeState,
    drawer: SharedNavDrawerViewModel,
): ShellNavDrawerActions = remember(state, drawer) {
    ShellNavDrawerActions(rail = sharedDrawerRailActions(state, drawer), panel = sharedDrawerPanelActions(state, drawer))
}

internal fun sharedDrawerRailActions(state: AgentScaffoldRuntimeState, drawer: SharedNavDrawerViewModel): ShellAgentRailActions {
    val navigation = state.params.navigation
    val openAgentSwitcher = { closeDrawerAndRun(state) { state.params.sheetVisibility.onShowAgentSwitcherChange(true) } }
    return ShellAgentRailActions(
        // An agent opens on its most recent conversation, as the desktop rail does.
        onAgentSelected = { agentId ->
            val name = state.switchableAgents.firstOrNull { it.id.value == agentId }?.name
            closeDrawerAndRunSuspend(state) {
                val conversationId = pickOtherAgentConversation(repo = state.drawerConversationRepo, agentId = AgentId(agentId))
                navigation.onSwitchConversation?.invoke(agentId, conversationId, name)
            }
        },
        onHome = { closeDrawerAndRun(state) { navigation.onNavigateToAdmin?.invoke() } },
        onNewSession = openAgentSwitcher,
        // The rail is a recents strip; "All agents" opens the agent switcher, which lists every agent.
        onShowAllAgents = openAgentSwitcher,
        // The orb's long-press menu: pin to Home, and that agent's settings.
        onAgentPinnedChange = drawer::setAgentPinned,
        onAgentSettings = { agentId -> closeDrawerAndRun(state) { navigation.onNavigateToSettings(agentId) } },
    )
}

internal fun sharedDrawerPanelActions(
    state: AgentScaffoldRuntimeState,
    drawer: SharedNavDrawerViewModel,
): ShellAgentPanelActions {
    val navigation = state.params.navigation
    val agentId = state.agentIdValue
    val agentName = state.agentName.takeIf { it.isNotBlank() }
    return ShellAgentPanelActions(
        onOpenSection = { section -> closeDrawerAndRun(state) { openSection(navigation, agentId, section) } },
        onOpenSettings = {
            closeDrawerAndRun(state) { (navigation.onNavigateToAppSettings ?: navigation.onNavigateToAdmin)?.invoke() }
        },
        onNewChat = { closeDrawerAndRun(state) { navigation.onSwitchConversation?.invoke(agentId, null, agentName) } },
        onEditAgent = { closeDrawerAndRun(state) { navigation.onNavigateToSettings(agentId) } },
        onArchiveFilterChange = drawer::setArchiveFilter,
        onConversationSelected = { id ->
            closeDrawerAndRun(state) { navigation.onSwitchConversation?.invoke(agentId, id, agentName) }
        },
        onArchiveConversation = { id, archived -> drawer.setConversationArchived(id, agentId, archived) },
        onDeleteConversation = { id -> drawer.deleteConversation(id, agentId) },
        onOpenCanvas = { canvasId -> closeDrawerAndRun(state) { navigation.onOpenCanvas?.invoke(canvasId.value) } },
        // onArchiveCanvas stays null: Android keeps no canvas archive yet (letta-mobile-c3np7.5.7),
        // so canvas rows offer no archive, by hover or by long-press.
    )
}

/** Where a panel section goes on Android. */
internal fun openSection(navigation: AgentScaffoldNavigationCallbacks, agentId: String, section: LensDestination) {
    when (section) {
        LensDestination.Memory -> navigation.onNavigateToMemory?.invoke(agentId)
        LensDestination.Schedules -> navigation.onNavigateToSchedules?.invoke(agentId)
        LensDestination.Skills -> navigation.onNavigateToTools?.invoke()
        LensDestination.Conversations -> navigation.onNavigateToConversationList?.invoke()
        LensDestination.Channels -> Unit
    }
}

private fun closeDrawerAndRunSuspend(state: AgentScaffoldRuntimeState, action: suspend () -> Unit) {
    state.scope.launch {
        state.drawerState.close()
        action()
    }
}
