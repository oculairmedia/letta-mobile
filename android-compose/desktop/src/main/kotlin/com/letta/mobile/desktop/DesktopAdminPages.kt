package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.letta.mobile.data.backend.AppServerRouteUnavailable
import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.ProjectSummary
import com.letta.mobile.data.projects.ProjectFailureWords
import com.letta.mobile.data.projects.ProjectPinStore
import com.letta.mobile.data.projects.ProjectsPageController
import com.letta.mobile.data.projects.ProjectsPageSources
import com.letta.mobile.data.projects.RepositoryProjectsBackend
import com.letta.mobile.data.projects.SettingsStoreProjectPinStore
import com.letta.mobile.data.runs.RunMonitorController
import com.letta.mobile.data.session.ifSupported
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.templates.AgentTemplatesController
import com.letta.mobile.desktop.data.DesktopSessionGraph
import com.letta.mobile.ui.shell.ShellAdminPages
import com.letta.mobile.ui.shell.ShellPageContent
import com.letta.mobile.ui.shell.pages.projects.projectsPageActions
import com.letta.mobile.ui.shell.pages.runs.RunsPageState
import com.letta.mobile.ui.shell.pages.runs.runsPageActions
import com.letta.mobile.ui.shell.pages.templates.templatesPageActions
import com.letta.mobile.ui.shell.shellProjectsPage
import com.letta.mobile.ui.shell.shellProjectsUnavailablePage
import com.letta.mobile.ui.shell.shellRunsPage
import com.letta.mobile.ui.shell.shellTemplatesPage

/** Where the admin pages send the user: a run's conversation, a new or a project's agent, Settings. */
internal data class DesktopAdminNavigation(
    val openConversation: (agentId: String, conversationId: String) -> Unit,
    val openAgent: (agentId: String) -> Unit,
    val openSettings: () -> Unit,
)

/**
 * What the desktop's admin pages are bound to: the session's repositories, whether the backend has
 * projects (only an Iroh host relays them; letta-mobile-by4vm.13: read from the session's
 * capabilities), and where project pins are kept.
 */
internal data class DesktopAdminBinding(
    val graph: DesktopSessionGraph,
    val projectsAvailable: Boolean,
    val projectPins: ProjectPinStore,
    val navigation: DesktopAdminNavigation,
)

/** letta-mobile-ckmub.2.6: the admin pages over the current session; projects only through a live Iroh host. */
@Composable
internal fun rememberDesktopAdminBinding(context: DesktopShellContext): DesktopAdminBinding {
    val core = context.core
    val settingsStore = core.bootstrap.secureSettingsStore
    val pins = remember(settingsStore) { SettingsStoreProjectPinStore(settingsStore, PROJECT_PINS_KEY) }
    val router = context.router
    val graph = core.sessionGraph.value
    return DesktopAdminBinding(
        graph = graph,
        projectsAvailable = graph.capabilities.supports(SessionRoute.PROJECTS),
        projectPins = pins,
        navigation = DesktopAdminNavigation(
            openConversation = { _, conversationId -> router.openConversation(ConversationId(conversationId)) },
            openAgent = { router.openAgent(AgentId(it)) },
            openSettings = { context.navigator.navigate(DesktopDestination.Settings) },
        ),
    )
}

private const val PROJECT_PINS_KEY = "desktop.projects.pinned"

/**
 * letta-mobile-ckmub.2.6: the shared Runs, Templates and Projects pages on the desktop, each over its
 * sharedLogic controller. A controller lives as long as its page is on screen, and is rebuilt when the
 * session graph is (a backend switch).
 */
internal fun desktopAdminPages(binding: DesktopAdminBinding): ShellAdminPages = ShellAdminPages(
    runs = desktopRunsPage(binding),
    templates = desktopTemplatesPage(binding),
    projects = if (binding.projectsAvailable) desktopProjectsPage(binding) else shellProjectsUnavailablePage(binding.navigation.openSettings),
)

private fun desktopRunsPage(binding: DesktopAdminBinding) = ShellPageContent<ShellDestination.Runs> { destination, modifier ->
    val graph = binding.graph
    val scope = rememberCoroutineScope()
    // letta-mobile-by4vm.13: a session that serves no runs gets no repository; the page says why.
    val controller = remember(graph) {
        RunMonitorController(graph.ifSupported(SessionRoute.RUNS) { runRepository }, scope, ::desktopAdminErrorMessage)
    }
    LaunchedEffect(controller) { controller.start() }
    val monitor by controller.state.collectAsState()
    val agentNames = rememberAgentNames(graph)
    val actions = remember(controller, binding.navigation) { controller.runsPageActions(binding.navigation.openConversation) }
    val state = RunsPageState(monitor = monitor, agentNames = agentNames, canManageRuns = controller.canManageRuns)
    shellRunsPage(state, actions, DesktopChannelsPageSlots).Content(destination, modifier)
}

@Composable
private fun rememberAgentNames(graph: DesktopSessionGraph): Map<String, String> {
    val roster = graph.ifSupported(SessionRoute.AGENTS) { agentRepository.agents } ?: return emptyMap()
    val agents by roster.collectAsState()
    return remember(agents) { agents.associate { it.id.value to it.name.ifBlank { it.id.value } } }
}

private fun desktopTemplatesPage(binding: DesktopAdminBinding) = ShellPageContent<ShellDestination.Templates> { destination, modifier ->
    val graph = binding.graph
    val scope = rememberCoroutineScope()
    val controller = remember(graph) {
        AgentTemplatesController(
            agentRepository = graph.ifSupported(SessionRoute.AGENTS) { agentRepository },
            scope = scope,
            errorMessageMapper = ::desktopAdminErrorMessage,
        )
    }
    val state by controller.state.collectAsState()
    val openAgent = binding.navigation.openAgent
    val actions = remember(controller, openAgent) { controller.templatesPageActions(openAgent) }
    shellTemplatesPage(state, actions).Content(destination, modifier)
}

private fun desktopProjectsPage(binding: DesktopAdminBinding) = ShellPageContent<ShellDestination.Projects> { destination, modifier ->
    val graph = binding.graph
    val scope = rememberCoroutineScope()
    val controller = remember(graph, binding.projectPins) {
        ProjectsPageController(
            sources = ProjectsPageSources(backend = RepositoryProjectsBackend(graph.projectRepository), pins = binding.projectPins),
            scope = scope,
            words = ProjectFailureWords.of(::desktopAdminErrorMessage),
        )
    }
    LaunchedEffect(controller) { controller.start() }
    val state by controller.state.collectAsState()
    val openAgent = binding.navigation.openAgent
    // The desktop has no project-scoped chat yet: a project opens its agent's chat.
    val actions = remember(controller, openAgent) { controller.projectsPageActions { project, _ -> project.agentIdOrNull()?.let(openAgent) } }
    shellProjectsPage(state, actions, DesktopChannelsPageSlots).Content(destination, modifier)
}

private fun ProjectSummary.agentIdOrNull(): String? = lettaAgentId?.value?.takeIf { it.isNotBlank() }

internal fun desktopAdminErrorMessage(throwable: Throwable, fallback: String): String = when (throwable) {
    is AppServerRouteUnavailable -> "This backend does not serve this page yet."
    else -> throwable.message ?: fallback
}
