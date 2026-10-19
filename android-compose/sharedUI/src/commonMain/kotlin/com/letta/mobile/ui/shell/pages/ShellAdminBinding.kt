package com.letta.mobile.ui.shell.pages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.letta.mobile.data.repository.modelcontrol.ModelControlSession
import com.letta.mobile.data.runs.RunMonitorController
import com.letta.mobile.data.session.ifSupported
import com.letta.mobile.data.session.graph.SharedSessionRepositoryGraph
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.ShellPageErrors
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.data.templates.AgentTemplatesController
import com.letta.mobile.ui.modelcontrol.ProviderSettingsActions
import com.letta.mobile.ui.modelcontrol.ProviderSettingsPane
import com.letta.mobile.ui.shell.ShellAdminPages
import com.letta.mobile.ui.shell.ShellPageContent
import com.letta.mobile.ui.shell.pages.channels.ChannelsPageSlots
import com.letta.mobile.ui.shell.pages.projects.projectsPageActions
import com.letta.mobile.ui.shell.pages.runs.RunsPageState
import com.letta.mobile.ui.shell.pages.runs.runsPageActions
import com.letta.mobile.ui.shell.pages.templates.templatesPageActions
import com.letta.mobile.ui.shell.shellProjectsPage
import com.letta.mobile.ui.shell.shellProjectsUnavailablePage
import com.letta.mobile.ui.shell.shellRunsPage
import com.letta.mobile.ui.shell.shellTemplatesPage

/** Where project pins are kept in the host's settings store (the key desktop has always used). */
private const val PROJECT_PINS_KEY = "desktop.projects.pinned"

/**
 * The shared Runs, Templates and Projects pages (they were desktop's
 * `DesktopAdminPages`), each over its sharedLogic controller, which lives while its page is on screen and
 * is rebuilt with the session graph. Projects ride an Iroh host's relay; elsewhere the page says why.
 */
internal fun shellAdminPages(graph: SharedSessionRepositoryGraph, host: ShellPageHost): ShellAdminPages {
    val slots = host.pages.slots
    val openSettings = { host.navigator.navigate(ShellDestination.Settings()) }
    return ShellAdminPages(
        runs = runsPage(graph, host, slots),
        templates = templatesPage(graph, host),
        projects = if (graph.capabilities.supports(SessionRoute.PROJECTS)) {
            projectsPage(graph, host, slots)
        } else {
            shellProjectsUnavailablePage(openSettings)
        },
        plugins = host.pages.plugins,
    )
}

private fun runsPage(graph: SharedSessionRepositoryGraph, host: ShellPageHost, slots: ChannelsPageSlots) =
    ShellPageContent<ShellDestination.Runs> { destination, modifier ->
        val scope = rememberCoroutineScope()
        // letta-mobile-by4vm.13: a session that serves no runs gets no repository; the page says why.
        val controller = remember(graph) {
            RunMonitorController(graph.ifSupported(SessionRoute.RUNS) { runRepository }, scope, ShellPageErrors::message)
        }
        LaunchedEffect(controller) { controller.start() }
        val monitor by controller.state.collectAsState()
        val agentNames = rememberAgentNames(graph)
        val router = host.navigator
        val actions = remember(controller, router) {
            controller.runsPageActions { _, conversationId -> router.openConversation(ConversationId(conversationId)) }
        }
        val state = RunsPageState(monitor = monitor, agentNames = agentNames, canManageRuns = controller.canManageRuns)
        shellRunsPage(state, actions, slots).Content(destination, modifier)
    }

@Composable
private fun rememberAgentNames(graph: SharedSessionRepositoryGraph): Map<String, String> {
    val roster = graph.ifSupported(SessionRoute.AGENTS) { agentRepository.agents } ?: return emptyMap()
    val agents by roster.collectAsState()
    return remember(agents) { agents.associate { it.id.value to it.name.ifBlank { it.id.value } } }
}

private fun templatesPage(graph: SharedSessionRepositoryGraph, host: ShellPageHost) =
    ShellPageContent<ShellDestination.Templates> { destination, modifier ->
        val scope = rememberCoroutineScope()
        val controller = remember(graph) {
            AgentTemplatesController(
                agentRepository = graph.ifSupported(SessionRoute.AGENTS) { agentRepository },
                scope = scope,
                errorMessageMapper = ShellPageErrors::message,
            )
        }
        val state by controller.state.collectAsState()
        val router = host.navigator
        val actions = remember(controller, router) { controller.templatesPageActions { router.openAgent(AgentId(it)) } }
        shellTemplatesPage(state, actions).Content(destination, modifier)
    }

private fun projectsPage(graph: SharedSessionRepositoryGraph, host: ShellPageHost, slots: ChannelsPageSlots) =
    ShellPageContent<ShellDestination.Projects> { destination, modifier ->
        val scope = rememberCoroutineScope()
        val pins = rememberProjectPins(host.settings)
        val controller = remember(graph, pins) {
            ProjectsPageController(
                sources = ProjectsPageSources(backend = RepositoryProjectsBackend(graph.projectRepository), pins = pins),
                scope = scope,
                words = ProjectFailureWords.of(ShellPageErrors::message),
            )
        }
        LaunchedEffect(controller) { controller.start() }
        val state by controller.state.collectAsState()
        val router = host.navigator
        // No project-scoped chat yet: a project opens its agent's chat.
        val actions = remember(controller, router) {
            controller.projectsPageActions { project, _ -> project.agentIdOrNull()?.let(router::openAgent) }
        }
        shellProjectsPage(state, actions, slots).Content(destination, modifier)
    }

@Composable
private fun rememberProjectPins(settings: SecureSettingsStore): ProjectPinStore =
    remember(settings) { SettingsStoreProjectPinStore(settings, PROJECT_PINS_KEY) }

private fun ProjectSummary.agentIdOrNull(): AgentId? = lettaAgentId?.value?.takeIf { it.isNotBlank() }?.let(::AgentId)

/**
 * Providers: the shared settings pane over the host's [session], the
 * one its model picker and Models sheet read, so a change here shows there at once.
 */
fun shellProvidersPage(session: ModelControlSession) = ShellPageContent<ShellDestination.Providers> { _, modifier ->
    val scope = rememberCoroutineScope()
    val controller = remember(session) { session.managementController(scope) }
    LaunchedEffect(controller) { controller.refresh() }
    val state by controller.state.collectAsState()
    val actions = remember(controller) { ProviderSettingsActions.bind(controller) }
    ProviderSettingsPane(state = state, actions = actions, modifier = modifier)
}
