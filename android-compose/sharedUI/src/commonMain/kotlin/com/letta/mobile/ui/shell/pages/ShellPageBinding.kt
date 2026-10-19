package com.letta.mobile.ui.shell.pages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.repository.modelcontrol.ModelControlSession
import com.letta.mobile.data.schedules.ScheduleLibraryController
import com.letta.mobile.data.schedules.ScheduleLibraryState
import com.letta.mobile.data.session.graph.SharedSessionRepositoryGraph
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.ShellWorkspaceNavigator
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.ui.shell.EmptyShellPage
import com.letta.mobile.ui.shell.ShellPageContent
import com.letta.mobile.ui.shell.ShellPageDeps
import com.letta.mobile.ui.shell.pages.channels.ChannelsPageOptions
import com.letta.mobile.ui.shell.pages.channels.ChannelsPageSlots
import com.letta.mobile.ui.shell.pages.memory.MemoryWorkspaceBinding
import com.letta.mobile.ui.shell.pages.memory.rememberMemoryWorkspaceView
import com.letta.mobile.ui.shell.pages.schedules.SchedulesPageActions
import com.letta.mobile.ui.shell.pages.schedules.SchedulesPageState
import com.letta.mobile.ui.shell.pages.skills.SkillsPageActions
import com.letta.mobile.ui.shell.pages.skills.SkillsPageOptions
import com.letta.mobile.ui.shell.pages.skills.SkillsPageState
import com.letta.mobile.ui.shell.shellChannelsPage
import com.letta.mobile.ui.shell.shellMemoryPage
import com.letta.mobile.ui.shell.shellSchedulesPage
import com.letta.mobile.ui.shell.shellSkillsPage

/** The agent the pages are about, as the host's shell focuses it. */
class ShellPageFocus(
    val agentId: AgentId?,
    val agentName: String?,
)

/**
 * The pages a platform draws with its own container or services: its chat page (null while it has
 * none to show), its Settings services, its Plugins binding (the file picker), and its compact
 * search field and refresh for the pages' headers. [fallback] draws a destination with no page.
 */
class ShellHostPages(
    val chat: ShellPageContent<ShellDestination.Chat>?,
    val settings: ShellPageContent<ShellDestination.Settings>,
    val plugins: ShellPageContent<ShellDestination.Plugins>,
    val slots: ChannelsPageSlots = ChannelsPageSlots(),
    val fallback: ShellPageContent<ShellDestination> = EmptyShellPage,
)

/**
 * Everything the shell's pages take from a host besides the session graph
 * and the library controllers: its navigation, its focus, its own pages, Home's presentation, the
 * editor's extras, the provider session its model picker shares, and the device's settings store.
 */
class ShellPageHost(
    val navigator: ShellWorkspaceNavigator,
    val focus: ShellPageFocus,
    val pages: ShellHostPages,
    val home: ShellHomeHost,
    val agentEditor: ShellAgentEditorHost,
    val modelControl: ModelControlSession?,
    val settings: SecureSettingsStore,
)

/**
 *  (W5): the one set of shell pages every client binds - Home, Chat (and its
 * board), Memory with MemFS, Schedules, Channels, Skills/Tools, Settings, the agent editor, Providers,
 * Runs, Templates, Projects and Plugins - over [graph], [controllers] and [host]. The page on screen
 * follows the focused agent.
 */
@Composable
fun rememberShellPageDeps(
    graph: SharedSessionRepositoryGraph,
    controllers: ShellLibraryControllers,
    host: ShellPageHost,
): ShellPageDeps {
    ShellLibraryFocusEffects(controllers, host.navigator.state.page.kind, host.focus.agentId)
    return shellPageDeps(graph, controllers, host)
}

/** [rememberShellPageDeps]'s pages, without the focus effects. */
fun shellPageDeps(
    graph: SharedSessionRepositoryGraph,
    controllers: ShellLibraryControllers,
    host: ShellPageHost,
): ShellPageDeps {
    val chat = host.pages.chat
    return ShellPageDeps(
        home = shellHomeBinding(graph, controllers.home, host),
        chat = chat,
        // A board is a panel of its conversation's chat page.
        canvas = chat?.let { page -> ShellPageContent { _, modifier -> page.Content(ShellDestination.Chat(), modifier) } },
        memory = memoryPage(controllers),
        schedules = schedulesPage(graph, controllers.schedules, host.focus),
        channels = channelsPage(controllers, host.pages.slots),
        skills = skillsPage(controllers, host),
        settings = host.pages.settings,
        agentEditor = shellAgentEditorBinding(graph, host),
        providers = host.modelControl?.let(::shellProvidersPage),
        admin = shellAdminPages(graph, host),
        fallback = host.pages.fallback,
    )
}

/** The shared Memory page over the memory graph and MemFS controllers. */
private fun memoryPage(controllers: ShellLibraryControllers) = ShellPageContent<ShellDestination.Memory> { destination, modifier ->
    val memory by controllers.memory.state.collectAsState()
    val files by controllers.memfs.state.collectAsState()
    var view by rememberMemoryWorkspaceView()
    val binding = MemoryWorkspaceBinding(
        memory = memory,
        memoryActions = controllers.memory,
        files = files,
        filesActions = controllers.memfs,
        onFilesAgentChange = controllers.memfs::selectAgent,
        view = view,
        onViewChange = { view = it },
    )
    shellMemoryPage(binding).Content(destination, modifier)
}

/** The shared Schedules page; a schedule can be made where the session serves them and an agent is chosen. */
private fun schedulesPage(
    graph: SharedSessionRepositoryGraph,
    schedules: ScheduleLibraryController,
    focus: ShellPageFocus,
) = ShellPageContent<ShellDestination.Schedules> { destination, modifier ->
    val library by schedules.state.collectAsState()
    val focused = focus.agentId?.value
    val agentChosen = library.selectedAgentId != null || focused != null
    val state = SchedulesPageState(
        library = library,
        focusedAgentId = focused,
        canCreate = graph.capabilities.supports(SessionRoute.SCHEDULES) && agentChosen,
    )
    shellSchedulesPage(state, shellScheduleActions(schedules, library, focus.agentId)).Content(destination, modifier)
}

/**
 * The Schedules page's actions over the shared schedule library. The controller deletes under its
 * selected agent, so a delete for any other agent's row is dropped; a new
 * schedule is for the request's agent, else the focused one.
 */
internal fun shellScheduleActions(
    schedules: ScheduleLibraryController,
    library: ScheduleLibraryState,
    focusedAgent: AgentId?,
) = SchedulesPageActions(
    onRefresh = schedules::loadData,
    onSelectAgent = schedules::selectAgent,
    onDeleteSchedule = { ref -> if (ref.agentId == library.selectedAgentId) schedules.deleteSchedule(ref.scheduleId) },
    onCreate = { request ->
        val agent = request.agentId ?: focusedAgent?.value
        if (agent != null) schedules.createRecurringSchedule(request.copy(agentId = agent))
    },
)

private fun channelsPage(controllers: ShellLibraryControllers, slots: ChannelsPageSlots) =
    ShellPageContent<ShellDestination.Channels> { destination, modifier ->
        val state by controllers.channels.state.collectAsState()
        shellChannelsPage(state, controllers.channels, ChannelsPageOptions(slots = slots)).Content(destination, modifier)
    }

/** The shared Skills page (skills, then the agent's tools). */
private fun skillsPage(controllers: ShellLibraryControllers, host: ShellPageHost) =
    ShellPageContent<ShellDestination.Skills> { destination, modifier ->
        val catalog by controllers.skills.state.collectAsState()
        val tools by controllers.tools.state.collectAsState()
        val skills = controllers.skills
        val toolLibrary = controllers.tools
        val state = SkillsPageState(
            skills = catalog.skills,
            installedSkillNames = catalog.installedSkillNames,
            skillsLoading = catalog.isLoading,
            skillsError = catalog.errorMessage,
            skillActionError = catalog.actionError,
            canManageSkills = catalog.canManage,
            focusedAgentName = host.focus.agentName,
            tools = tools,
        )
        val agent = host.focus.agentId?.value
        val actions = SkillsPageActions(
            onRefreshSkills = { skills.reload(agent) },
            onInstallSkill = skills::install,
            onUninstallSkill = skills::uninstall,
            onDismissSkillActionError = skills::dismissActionError,
            onRefreshTools = toolLibrary::loadTools,
            onToolTagToggled = toolLibrary::toggleTag,
            onClearToolTags = toolLibrary::clearTags,
            onLoadMoreTools = toolLibrary::loadMoreTools,
        )
        shellSkillsPage(state, actions, SkillsPageOptions(slots = host.pages.slots)).Content(destination, modifier)
    }
