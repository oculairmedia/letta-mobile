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
import com.letta.mobile.data.chat.routing.pickOtherAgentConversation
import com.letta.mobile.data.lens.LensDestination
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

/**
 * Sections the host has no page for: their rows stay out of the drawer. Channels shows once the
 * host wires the shared Channels page (letta-mobile-c3np7.5.7).
 */
internal fun androidHiddenDrawerSections(navigation: AgentScaffoldNavigationCallbacks): Set<LensDestination> =
    if (navigation.onNavigateToChannels == null) setOf(LensDestination.Channels) else emptySet()

/**
 * The hamburger's drawer when the shared navigation drawer is on (letta-mobile-c3np7.5.5): the
 * desktop's agent rail and agent panel ([ShellNavDrawer]) over Android's agents, the agent's
 * conversations and the canvas library, navigating through the chat's existing callbacks.
 */
@Composable
internal fun AgentScaffoldSharedDrawerSheet(state: AgentScaffoldRuntimeState, drawer: SharedNavDrawerViewModel) {
    val canvases by drawer.canvases.collectAsStateWithLifecycle()
    val archiveFilter by drawer.archiveFilter.collectAsStateWithLifecycle()
    val identities = LocalMascotRegistry.current.identities
    val open = state.drawerState.isOpen
    LaunchedEffect(open) { if (open) drawer.refreshCanvases() }
    val input = ShellNavDrawerInput(
        agent = ShellPanelAgent(name = state.agentName.ifBlank { "Agent" }, agentId = state.agentIdValue),
        agents = state.switchableAgents.map { it.id.value to it.name },
        identities = identities.toMap(),
        conversations = state.drawerConversations,
        openConversationId = state.conversationId,
        archiveFilter = archiveFilter,
        canvases = canvases,
        hiddenSections = androidHiddenDrawerSections(state.params.navigation),
    )
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

@Composable
private fun rememberSharedDrawerActions(
    state: AgentScaffoldRuntimeState,
    drawer: SharedNavDrawerViewModel,
): ShellNavDrawerActions = remember(state, drawer) {
    ShellNavDrawerActions(rail = sharedDrawerRailActions(state), panel = sharedDrawerPanelActions(state, drawer))
}

internal fun sharedDrawerRailActions(state: AgentScaffoldRuntimeState): ShellAgentRailActions {
    val navigation = state.params.navigation
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
        onNewSession = { closeDrawerAndRun(state) { state.params.sheetVisibility.onShowAgentSwitcherChange(true) } },
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
    )
}

/** Where a panel section goes on Android. */
internal fun openSection(navigation: AgentScaffoldNavigationCallbacks, agentId: String, section: LensDestination) {
    when (section) {
        LensDestination.Memory -> navigation.onNavigateToMemory?.invoke(agentId)
        LensDestination.Schedules -> navigation.onNavigateToSchedules?.invoke(agentId)
        LensDestination.Skills -> navigation.onNavigateToTools?.invoke()
        LensDestination.Conversations -> navigation.onNavigateToConversationList?.invoke()
        LensDestination.Channels -> navigation.onNavigateToChannels?.invoke()
    }
}

private fun closeDrawerAndRunSuspend(state: AgentScaffoldRuntimeState, action: suspend () -> Unit) {
    state.scope.launch {
        state.drawerState.close()
        action()
    }
}
