package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.templates.AgentTemplatesController
import com.letta.mobile.desktop.data.DesktopRepositoryUnavailableException
import com.letta.mobile.desktop.data.DesktopSessionGraph
import com.letta.mobile.ui.shell.ShellAdminPages
import com.letta.mobile.ui.shell.ShellPageContent
import com.letta.mobile.ui.shell.pages.projects.ProjectDetailActions
import com.letta.mobile.ui.shell.pages.projects.ProjectEditorActions
import com.letta.mobile.ui.shell.pages.projects.ProjectsListActions
import com.letta.mobile.ui.shell.pages.projects.ProjectsPageActions
import com.letta.mobile.ui.shell.pages.runs.RunsPageActions
import com.letta.mobile.ui.shell.pages.runs.RunsPageState
import com.letta.mobile.ui.shell.pages.templates.TemplatesPageActions
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
 * projects (only a live Iroh host relays them), and where project pins are kept.
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
    return DesktopAdminBinding(
        graph = core.sessionGraph.value,
        projectsAvailable = core.iroh.transport != null,
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
    val controller = remember(graph) { RunMonitorController(graph.runRepository, scope, ::desktopAdminErrorMessage) }
    LaunchedEffect(controller) { controller.start() }
    val monitor by controller.state.collectAsState()
    val agentNames = rememberAgentNames(graph)
    val actions = remember(controller, binding.navigation) { runsPageActions(controller, binding.navigation) }
    val state = RunsPageState(monitor = monitor, agentNames = agentNames, canManageRuns = controller.canManageRuns)
    shellRunsPage(state, actions, DesktopChannelsPageSlots).Content(destination, modifier)
}

private fun runsPageActions(controller: RunMonitorController, navigation: DesktopAdminNavigation) = RunsPageActions(
    onRefresh = controller::refresh,
    onQueryChange = controller::setQuery,
    onActiveOnlyChange = controller::setActiveOnly,
    onOpenRun = controller::open,
    onCloseRun = controller::closeDetail,
    onCancelRun = controller::cancel,
    onDeleteRun = controller::delete,
    onDismissError = controller::clearOperationError,
    onOpenConversation = navigation.openConversation,
)

@Composable
private fun rememberAgentNames(graph: DesktopSessionGraph): Map<String, String> {
    val agents by graph.agentRepository.agents.collectAsState()
    return remember(agents) { agents.associate { it.id.value to it.name.ifBlank { it.id.value } } }
}

private fun desktopTemplatesPage(binding: DesktopAdminBinding) = ShellPageContent<ShellDestination.Templates> { destination, modifier ->
    val graph = binding.graph
    val scope = rememberCoroutineScope()
    val controller = remember(graph) {
        AgentTemplatesController(agentRepository = graph.agentRepository, scope = scope, errorMessageMapper = ::desktopAdminErrorMessage)
    }
    val state by controller.state.collectAsState()
    val openAgent = binding.navigation.openAgent
    val actions = remember(controller, openAgent) {
        TemplatesPageActions(onCreate = { id -> controller.create(id, openAgent) }, onDismissError = controller::clearError)
    }
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
    val actions = remember(controller, binding.navigation) { projectsPageActions(controller, binding.navigation) }
    shellProjectsPage(state, actions, DesktopChannelsPageSlots).Content(destination, modifier)
}

private fun projectsPageActions(controller: ProjectsPageController, navigation: DesktopAdminNavigation) = ProjectsPageActions(
    list = ProjectsListActions(
        onRefresh = controller::refresh,
        onQueryChange = controller::setQuery,
        onSelect = controller::select,
        onTogglePinned = controller::togglePinned,
        onStartCreate = controller.editor::startCreate,
        onDismissNotice = controller::dismissNotice,
        // The desktop has no project-scoped chat yet: a project opens its agent's chat.
        onOpenChat = { project, _ ->
            controller.select(null)
            project.agentIdOrNull()?.let(navigation.openAgent)
        },
    ),
    detail = ProjectDetailActions(
        onStartEdit = controller.editor::startEdit,
        onSync = controller::sync,
        onProvisionBeads = controller::provisionBeadsRemote,
        onArchive = controller::archive,
        onDelete = controller::delete,
    ),
    editor = ProjectEditorActions(
        onDraftChange = controller.editor::updateDraft,
        onSubmit = controller.editor::submit,
        onClose = controller.editor::close,
    ),
)

private fun ProjectSummary.agentIdOrNull(): String? = lettaAgentId?.value?.takeIf { it.isNotBlank() }

private fun desktopAdminErrorMessage(throwable: Throwable, fallback: String): String = when (throwable) {
    is DesktopRepositoryUnavailableException -> "This backend does not serve this page yet."
    else -> throwable.message ?: fallback
}
