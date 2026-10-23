package com.letta.mobile.ui.shell.pages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.home.FleetConversation
import com.letta.mobile.data.home.FleetOverviewParams
import com.letta.mobile.data.home.HomePageController
import com.letta.mobile.data.home.buildFleetOverview
import com.letta.mobile.data.home.toFleetConversation
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.presence.runningAgentIds
import com.letta.mobile.data.session.agentRosterOrEmpty
import com.letta.mobile.data.session.SessionRepositoryGraph
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.ShellHomePrompt
import com.letta.mobile.data.shell.ShellHomeShortcuts
import com.letta.mobile.data.shell.ShellSkillsSection
import com.letta.mobile.ui.mascot.LocalMascotRegistry
import com.letta.mobile.ui.shell.ShellPageContent
import com.letta.mobile.ui.shell.pages.home.HomeConnectionPanel
import com.letta.mobile.ui.shell.pages.home.HomeConnectionState
import com.letta.mobile.ui.shell.pages.home.HomePageCallbacks
import com.letta.mobile.ui.shell.pages.home.HomePageNavigation
import com.letta.mobile.ui.shell.pages.home.HomePageOptions
import com.letta.mobile.ui.shell.shellHomePage
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The connection hero Home waits behind until the backend is there. */
class ShellHomeConnection(
    val state: HomeConnectionState,
    val onRetry: () -> Unit,
    val wordmark: String,
)

/** The host's presentation of Home, the hero it waits behind (null once connected), and where card actions go. */
class ShellHomeHost(
    val options: HomePageOptions = HomePageOptions(),
    val connection: ShellHomeConnection? = null,
    val onA2uiAction: (A2uiAction) -> Unit = {},
)

/** What the fleet dashboard folds: the chat's conversations, the roster, and who is mid-run. */
class ShellHomeFleet(
    val conversations: List<FleetConversation>,
    val roster: List<Agent>,
    val runningAgentIds: Set<String> = emptySet(),
    /** The roster fetch was cut short; counts then read as lower bounds. */
    val rosterTruncated: Boolean = false,
)

/**
 * The fleet from backend [conversations] (hosts that list them straight from the backend: Android,
 * the browser): hidden ones dropped, and a conversation without an agent name named from [roster].
 */
fun shellHomeFleet(
    conversations: List<Conversation>,
    roster: List<Agent>,
    runningAgentIds: Set<String> = emptySet(),
): ShellHomeFleet {
    val names = roster.associate { it.id.value to it.name }
    val fleet = conversations.mapNotNull { conversation ->
        conversation.toFleetConversation()?.let { row ->
            if (row.agentName.isNotBlank()) row else row.copy(agentName = names[row.agentId].orEmpty())
        }
    }
    return ShellHomeFleet(conversations = fleet, roster = roster, runningAgentIds = runningAgentIds)
}

/**
 * [ShellHomeFleetEffect] over backend [conversations] and the [roster] ([shellHomeFleet]), with who is
 * mid-run read from the mascot registry's run presence (Android, the browser).
 */
@Composable
fun ShellHomeBackendFleetEffect(controller: HomePageController, conversations: List<Conversation>, roster: List<Agent>) {
    val running = LocalMascotRegistry.current.presence.toMap().runningAgentIds()
    ShellHomeFleetEffect(controller, remember(conversations, roster, running) { shellHomeFleet(conversations, roster, running) })
}

/** Feeds [fleet] to Home's controller, refolded once a minute so its relative times stay current. */
@Composable
fun ShellHomeFleetEffect(controller: HomePageController, fleet: ShellHomeFleet) {
    val now = rememberFleetClock()
    val overview = remember(fleet.conversations, fleet.roster, fleet.runningAgentIds, fleet.rosterTruncated, now) {
        buildFleetOverview(
            FleetOverviewParams(
                conversations = fleet.conversations,
                rosterAgents = fleet.roster,
                runningAgentIds = fleet.runningAgentIds,
                now = now,
                rosterTruncated = fleet.rosterTruncated,
            ),
        )
    }
    LaunchedEffect(controller, overview) { controller.updateFleet(overview) }
}

@Composable
private fun rememberFleetClock(): Instant {
    var now by remember { mutableStateOf(Clock.System.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60.seconds)
            now = Clock.System.now()
        }
    }
    return now
}

/** The shared Home page over its controller, behind the connection hero while there is one. */
internal fun shellHomeBinding(
    graph: SessionRepositoryGraph,
    controller: HomePageController,
    host: ShellPageHost,
) = ShellPageContent<ShellDestination.Home> { destination, modifier ->
    val connection = host.home.connection
    if (connection != null) {
        HomeConnectionPanel(connection.state, connection.onRetry, modifier, wordmark = connection.wordmark)
    } else {
        val state by controller.state.collectAsState()
        val callbacks = HomePageCallbacks(actions = controller, navigation = homeNavigation(graph, host))
        shellHomePage(state, callbacks, host.home.options).Content(destination, modifier)
    }
}

private fun homeNavigation(graph: SessionRepositoryGraph, host: ShellPageHost): HomePageNavigation {
    val router = host.navigator
    val workspace = router.state
    val openConversation = { id: String -> router.openConversation(ConversationId(id)) }
    return HomePageNavigation(
        onSubmitPrompt = { text ->
            val rosterAgent = graph.agentRosterOrEmpty().value.firstOrNull()?.id
            router.submitHomePrompt(ShellHomePrompt(text, host.focus.agentId, rosterAgent))
        },
        onOpenConversation = { openConversation(it.conversationId) },
        onOpenAgent = { router.openAgent(AgentId(it)) },
        onOpenShortcut = { shortcut -> ShellHomeShortcuts.destinationFor(shortcut)?.let(workspace::navigate) },
        onConfigureAgent = { workspace.editAgentId = AgentId(it) },
        onOpenMessage = { message -> message.conversationId?.let(openConversation) },
        onOpenTool = { workspace.navigate(ShellDestination.Skills(section = ShellSkillsSection.Tools)) },
        onOpenBlock = { workspace.navigate(ShellDestination.Memory()) },
        onA2uiAction = host.home.onA2uiAction,
    )
}
