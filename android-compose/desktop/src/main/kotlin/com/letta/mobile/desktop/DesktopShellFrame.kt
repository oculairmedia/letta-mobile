package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.data.channel.ChannelsPageState
import com.letta.mobile.data.composer.Mentionable
import com.letta.mobile.data.lens.WorkPlayLens
import com.letta.mobile.data.memory.graph.MemoryPageState
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentStatus
import com.letta.mobile.data.presence.presenceByAgent
import com.letta.mobile.data.search.PaletteItem
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.desktop.agent.agentAvatarStyleKey
import com.letta.mobile.desktop.chat.ConversationArchiveFilter
import com.letta.mobile.desktop.chat.DesktopChatSurfaceState
import com.letta.mobile.desktop.chat.DesktopConversationSummary
import com.letta.mobile.data.home.FleetOverviewParams
import com.letta.mobile.data.home.buildFleetOverview
import com.letta.mobile.desktop.home.DesktopHomeInputs
import com.letta.mobile.desktop.home.toFleetConversation
import com.letta.mobile.desktop.schedules.DesktopScheduleLibraryState
import com.letta.mobile.desktop.tools.DesktopToolLibraryState
import com.letta.mobile.ui.mascot.LocalMascotRegistry
import com.letta.mobile.ui.mascot.resolveMascotIdentity
import com.letta.mobile.ui.shell.pages.home.HomePageOptions
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.delay

/** The agent the shell is focused on, and every agent's name and identity around it. */
internal data class DesktopAgentFocus(
    val rosterAgents: List<Agent>,
    /** Same-named agents are stacked in the rail (see [buildRailAgents]). */
    val railAgents: List<Pair<String, String>>,
    val railActivityByAgentId: Map<String, RailAgentActivity>,
    val selectedAgentId: String?,
    val selectedAgentName: String,
    val selectedAgentOrbIndex: Int,
    val identityByAgentId: Map<String, MascotIdentity>,
    /** The gradient orbs (until the rollout's P3 replaces them) keep taking a slot index. */
    val avatarStyleByAgentId: Map<String, Int>,
)

/** Who is working right now: thinking, streaming, or running as a subagent. */
internal data class DesktopShellActivity(
    val thinkingConversationId: String?,
    val thinkingAgentId: String?,
    val isThinkingSelected: Boolean,
    /** A reply is streaming for the selected conversation; outlives "thinking". */
    val isStreamingReplySelected: Boolean,
    val runningAgentIds: Set<String>,
)

/** The library controllers' current states, behind the sidebar destinations. */
internal data class DesktopLibraryStates(
    val memory: MemoryPageState,
    val schedules: DesktopScheduleLibraryState,
    val channels: ChannelsPageState,
    val tools: DesktopToolLibraryState,
)

/** The lists the sidebar, composer, palette and pickers draw from. */
internal data class DesktopShellLists(
    val activeSubagents: List<SubagentEntry>,
    val archiveFilter: ConversationArchiveFilter,
    /** Every conversation across the selected stack, newest first (see [filterStackConversations]). */
    val agentConversations: List<DesktopConversationSummary>,
    val mentionables: List<Mentionable>,
    val paletteItems: List<PaletteItem>,
    val modelOptions: List<Pair<String, String>>,
)

/** letta-mobile-bglj6.1.10: the state the shell derives on each composition, for its pieces to draw. */
internal data class DesktopShellFrame(
    val chatState: DesktopChatSurfaceState,
    val focus: DesktopAgentFocus,
    val activity: DesktopShellActivity,
    val libraries: DesktopLibraryStates,
    val lists: DesktopShellLists,
)

@Composable
internal fun rememberDesktopShellFrame(context: DesktopShellContext, chatState: DesktopChatSurfaceState): DesktopShellFrame {
    val focus = rememberDesktopAgentFocus(context, chatState)
    val activity = rememberDesktopShellActivity(context, chatState)
    val libraries = collectLibraryStates(context.panels.libraries)
    return DesktopShellFrame(
        chatState = chatState,
        focus = focus,
        activity = activity,
        libraries = libraries,
        lists = rememberDesktopShellLists(context, chatState, focus, libraries.memory),
    )
}

@Composable
private fun rememberDesktopAgentFocus(context: DesktopShellContext, chatState: DesktopChatSurfaceState): DesktopAgentFocus {
    val sessionGraph = context.core.sessionGraph.value
    val rosterAgents by sessionGraph.agentRepository.agents.collectAsState()
    LaunchedEffect(sessionGraph, chatState.connectionState) {
        runCatching {
            sessionGraph.agentRepository.refreshAgentsIfStale(
                maxAgeMs = DESKTOP_AGENT_NAME_REFRESH_MAX_AGE_MS,
            )
        }
    }
    val conversations = chatState.conversations
    val railAgents = remember(conversations, rosterAgents) { buildRailAgents(conversations, rosterAgents) }
    val selectedAgentId = chatState.selectedConversation?.agentId ?: railAgents.firstOrNull()?.first
    // Session overrides win over the cached/backend value so a just-saved icon shows instantly.
    val identityByAgentId = rememberCachedIdentities(railAgents, rosterAgents, context.core.bootstrap.secureSettingsStore) +
        context.navigator.avatarOverrides
    val avatarStyleByAgentId = identityByAgentId.mapValues { it.value.legacyOrbIndex() }
    // Every AgentOrb in the app reads identities from the registry; keep it current.
    val mascotRegistry = LocalMascotRegistry.current
    SideEffect { mascotRegistry.update(identityByAgentId) }
    return DesktopAgentFocus(
        rosterAgents = rosterAgents,
        railAgents = railAgents,
        railActivityByAgentId = rememberRailActivityByAgentId(conversations),
        selectedAgentId = selectedAgentId,
        selectedAgentName = selectedAgentName(railAgents, selectedAgentId, chatState),
        selectedAgentOrbIndex = avatarStyleByAgentId[selectedAgentId]
            ?: railAgents.indexOfFirst { it.first == selectedAgentId }.coerceAtLeast(0),
        identityByAgentId = identityByAgentId,
        avatarStyleByAgentId = avatarStyleByAgentId,
    )
}

/**
 * Every known agent's identity - the rail's and the whole roster's, so the palette and quick
 * query draw roster-only agents too: the one chosen in the editor (agent metadata, then this
 * machine's cache), else the one generated from the agent id. Re-derived whenever the roster
 * changes - which includes the post-save reload - so a freshly-saved icon shows on the orbs.
 */
@Composable
private fun rememberCachedIdentities(
    railAgents: List<Pair<String, String>>,
    rosterAgents: List<Agent>,
    store: SecureSettingsStore,
): Map<String, MascotIdentity> {
    return remember(railAgents, rosterAgents) {
        val rosterById = rosterAgents.associateBy { it.id.value }
        (railAgents.map { it.first } + rosterById.keys).distinct().associateWith { id ->
            resolveMascotIdentity(id, rosterById[id], store.getString(agentAvatarStyleKey(id)))
        }
    }
}

private fun selectedAgentName(
    railAgents: List<Pair<String, String>>,
    selectedAgentId: String?,
    chatState: DesktopChatSurfaceState,
): String {
    val railName = railAgents.firstOrNull { it.first == selectedAgentId }?.second
    return railName ?: chatState.selectedConversation?.agentName ?: "Letta"
}

@Composable
private fun rememberDesktopShellActivity(context: DesktopShellContext, chatState: DesktopChatSurfaceState): DesktopShellActivity {
    val chatController = context.core.chatController
    val activeSubagents = context.panels.subagents.activeSubagents.value
    // A conversation is "thinking" from the moment a prompt is sent until the
    // agent's reply starts landing (tracked by the controller - `isSending`
    // alone clears too early, while the reply streams over a separate channel).
    val thinkingConversationId by chatController.thinkingConversationId.collectAsState()
    // Reply is actively streaming for the selected conversation - outlives
    // "thinking" (which clears at the first token), so it gates the streamed-
    // text smoother in the message list. Derived by the shared
    // ChatStreamingPresencePolicy (the same rules Android uses).
    val replyPresence by chatController.replyPresence.collectAsState()
    // Presence -> the mascots' directors, read from the window's run registry exactly as Android's
    // ProvideMascotShell reads the app-wide one: the phase the turn is actually in, attributed to
    // the conversation it happened in (letta-mobile-8a3bz, -s4krx).
    val runs by chatController.runs.collectAsState()
    val mascotPresence = remember(runs) { runs.presenceByAgent() }
    val mascotRegistry = LocalMascotRegistry.current
    SideEffect { mascotRegistry.updatePresence(mascotPresence) }
    val thinkingAgentId = thinkingConversationId?.let { tid ->
        chatState.conversations.firstOrNull { it.id == tid }?.agentId
    }
    val streamingAgentId = if (replyPresence.isStreaming) {
        chatState.conversations.firstOrNull { it.id == chatState.selectedConversationId }?.agentId
    } else {
        null
    }
    val runningAgentIds = remember(thinkingAgentId, streamingAgentId, activeSubagents) {
        runningAgentIds(listOfNotNull(thinkingAgentId, streamingAgentId), activeSubagents)
    }
    return DesktopShellActivity(
        thinkingConversationId = thinkingConversationId,
        thinkingAgentId = thinkingAgentId,
        isThinkingSelected = thinkingConversationId != null &&
            thinkingConversationId == chatState.selectedConversationId,
        isStreamingReplySelected = replyPresence.isStreaming,
        runningAgentIds = runningAgentIds,
    )
}

/** [foreground] agents (thinking, streaming), then every running subagent. */
private fun runningAgentIds(foreground: List<String>, activeSubagents: List<SubagentEntry>): Set<String> {
    return buildSet {
        addAll(foreground)
        activeSubagents
            .filter { it.status == SubagentStatus.RUNNING }
            .forEach { entry -> entry.subagentAgentId?.let(::add) }
    }
}

@Composable
private fun collectLibraryStates(libraries: DesktopLibraryControllers): DesktopLibraryStates {
    val memory by libraries.memory.state.collectAsState()
    val schedules by libraries.schedules.state.collectAsState()
    val channels by libraries.channels.state.collectAsState()
    val tools by libraries.tools.state.collectAsState()
    return DesktopLibraryStates(memory = memory, schedules = schedules, channels = channels, tools = tools)
}

@Composable
private fun rememberDesktopShellLists(
    context: DesktopShellContext,
    chatState: DesktopChatSurfaceState,
    focus: DesktopAgentFocus,
    memoryState: MemoryPageState,
): DesktopShellLists {
    val chatController = context.core.chatController
    val activeSubagents = context.panels.subagents.activeSubagents.value
    val archiveFilter by chatController.archiveFilter.collectAsState()
    val availableModels by chatController.availableModels.collectAsState()
    val workPlayMode = context.navigator.workPlayMode
    val conversations = chatState.conversations
    val selectedConversationId = chatState.selectedConversationId
    // For a "Letta Code" subagent stack this is its same-PROVENANCE spawns (grouped by
    // authoritative parent identity via the shared model, so unrelated same-name agents are NOT
    // merged); for a normal agent it is its display-name convs, unchanged.
    val agentConversations = remember(
        conversations,
        activeSubagents,
        focus.selectedAgentName,
        focus.selectedAgentId,
        selectedConversationId,
        archiveFilter,
    ) {
        filterStackConversations(
            FilterStackConversationsParams(
                conversations = conversations,
                activeSubagents = activeSubagents,
                selectedAgentName = focus.selectedAgentName,
                selectedAgentId = focus.selectedAgentId,
                selectedConversationId = selectedConversationId,
                archiveFilter = archiveFilter,
            ),
        )
    }
    return DesktopShellLists(
        activeSubagents = activeSubagents,
        archiveFilter = archiveFilter,
        agentConversations = agentConversations,
        mentionables = remember(focus.railAgents, memoryState) {
            buildMentionables(BuildMentionablesParams(focus.railAgents, memoryState.parity))
        },
        paletteItems = remember(conversations, focus.railAgents, workPlayMode) {
            buildPaletteItems(conversations, focus.railAgents, workPlayMode)
        },
        modelOptions = remember(availableModels) { buildModelOptions(availableModels) },
    )
}

/**
 * Home page inputs. The fleet half is folded from state the shell already holds (conversations +
 * roster + who is mid-run) and handed to the shared controller; the rest of the page (pins, stats,
 * search) is the controller's own.
 */
@Composable
internal fun rememberDesktopHomeInputs(context: DesktopShellContext, frame: DesktopShellFrame): DesktopHomeInputs {
    val focus = frame.focus
    val conversations = frame.chatState.conversations
    val runningAgentIds = frame.activity.runningAgentIds
    val agentDirectory = context.core.iroh.agentDirectory
    val fleetClock = rememberFleetClock()
    val fleetOverview = remember(conversations, focus.rosterAgents, runningAgentIds, fleetClock) {
        buildFleetOverview(
            FleetOverviewParams(
                conversations = conversations.map { it.toFleetConversation() },
                rosterAgents = focus.rosterAgents,
                runningAgentIds = runningAgentIds,
                now = fleetClock,
                // Broken backend pagination truncates the roster; the summary
                // then renders counts as "N+" lower bounds, not exact figures.
                rosterTruncated = agentDirectory?.lastAgentListTruncated == true,
            ),
        )
    }
    val controller = context.panels.libraries.home
    LaunchedEffect(controller, fleetOverview) { controller.updateFleet(fleetOverview) }
    val state by controller.state.collectAsState()
    val homeOrbIndexes = remember(focus.railAgents, focus.avatarStyleByAgentId) {
        focus.railAgents
            .mapIndexed { index, (id, _) -> id to (focus.avatarStyleByAgentId[id] ?: index) }
            .toMap()
    }
    val navigator = context.navigator
    return DesktopHomeInputs(
        state = state,
        options = HomePageOptions(
            orbIndexByAgentId = homeOrbIndexes,
            composerPlaceholder = WorkPlayLens.composerPlaceholder(navigator.workPlayMode, focus.selectedAgentName),
        ),
    )
}

/** Now, advanced once a minute so the fleet dashboard's relative times stay current. */
@Composable
private fun rememberFleetClock(): Instant {
    var fleetClock by remember { mutableStateOf(Clock.System.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60.seconds)
            fleetClock = Clock.System.now()
        }
    }
    return fleetClock
}
