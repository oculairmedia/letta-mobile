package com.letta.mobile.ui.shell.pages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.memory.MemoryParityControllerState
import com.letta.mobile.data.memory.graph.MemoryPageState
import com.letta.mobile.data.memory.memfs.MemfsNavigation
import com.letta.mobile.data.memory.memfs.MemfsPageActions
import com.letta.mobile.data.repository.modelcontrol.ModelControlSession
import com.letta.mobile.data.schedules.ScheduleLibraryController
import com.letta.mobile.data.session.SessionRepositoryGraph
import com.letta.mobile.data.shell.CurrentFocusChatMode
import com.letta.mobile.data.shell.ShellWorkspaceState
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.ShellWorkspaceNavigator
import com.letta.mobile.data.skills.SkillsCatalogController
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.ui.shell.EmptyShellPage
import com.letta.mobile.ui.chat.tools.ProvideToolStepExpansions
import com.letta.mobile.ui.chat.voice.LocalVoiceSetup
import com.letta.mobile.ui.chat.voice.LocalVoiceSetupPane
import com.letta.mobile.ui.chat.voice.VoiceSetupAccess
import com.letta.mobile.ui.chat.voice.rememberVoiceSetupAccess
import com.letta.mobile.ui.settings.VoiceSetupPane
import com.letta.mobile.ui.shell.ShellPageContent
import com.letta.mobile.ui.shell.ShellPageDeps
import com.letta.mobile.ui.shell.pages.channels.ChannelsPageOptions
import com.letta.mobile.ui.shell.pages.channels.ChannelsPageSlots
import com.letta.mobile.ui.shell.pages.memory.MemoryWorkspaceBinding
import com.letta.mobile.ui.shell.pages.memory.MemoryWorkspaceView
import com.letta.mobile.ui.chat.surface.timeline.rows.LocalMemoryPreview
import com.letta.mobile.ui.shell.pages.memory.rememberMemoryWorkspaceView
import com.letta.mobile.ui.shell.pages.schedules.SchedulesPageActions
import com.letta.mobile.ui.shell.pages.schedules.SchedulesPageState
import com.letta.mobile.ui.shell.pages.skills.SkillsPageActions
import com.letta.mobile.ui.shell.pages.skills.SkillsPageOptions
import com.letta.mobile.ui.shell.pages.skills.SkillsPageState
import com.letta.mobile.ui.shell.shellChannelsPage
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
    graph: SessionRepositoryGraph,
    controllers: ShellLibraryControllers,
    host: ShellPageHost,
): ShellPageDeps {
    ShellLibraryFocusEffects(controllers, host.navigator.state.page.kind, host.navigator.state.currentFocus.agentId)
    return shellPageDeps(graph, controllers, host, rememberVoiceSetupAccess(host.settings))
}

/**
 * [rememberShellPageDeps]'s pages, without the focus effects. [voice] is voice mode's setup, shared by
 * the chat and the Settings page (one store, so a save on either is seen by both); null where voice
 * mode is not offered.
 */
fun shellPageDeps(
    graph: SessionRepositoryGraph,
    controllers: ShellLibraryControllers,
    host: ShellPageHost,
    voice: VoiceSetupAccess? = null,
): ShellPageDeps {
    // The chat remembers which tool steps the person opened, across reloads, in the device's settings,
    // and its memory steps open the shell's own Memory view.
    val chat = host.pages.chat?.let { page ->
        ShellPageContent<ShellDestination.Chat> { destination, modifier ->
            ProvideToolStepExpansions(host.settings) {
                ProvideVoiceSetup(voice) {
                    ProvideShellMemoryPreview(controllers, host.navigator.state) { page.Content(destination, modifier) }
                }
            }
        }
    }
    val settings = host.pages.settings.let { page ->
        ShellPageContent<ShellDestination.Settings> { destination, modifier ->
            ProvideVoiceSetup(voice) { page.Content(destination, modifier) }
        }
    }
    return ShellPageDeps(
        home = shellHomeBinding(graph, controllers.home, host),
        chat = chat,
        // A board is a panel of its conversation's chat page.
        canvas = chat?.let { page -> ShellPageContent { _, modifier -> page.Content(ShellDestination.Chat(), modifier) } },
        memory = memoryPage(controllers, host),
        schedules = schedulesPage(graph, controllers.schedules, host),
        channels = channelsPage(controllers, host.pages.slots),
        skills = skillsPage(controllers, host),
        settings = settings,
        agentEditor = shellAgentEditorBinding(graph, host),
        providers = host.modelControl?.let(::shellProvidersPage),
        admin = shellAdminPages(graph, host),
        fallback = host.pages.fallback,
    )
}

/** [content] with voice mode's setup, and the Voice settings pane a chat press opens when it is incomplete. */
@Composable
private fun ProvideVoiceSetup(voice: VoiceSetupAccess?, content: @Composable () -> Unit) {
    if (voice == null) return content()
    val pane = remember(voice) { voiceSetupPane(voice) }
    CompositionLocalProvider(LocalVoiceSetup provides voice, LocalVoiceSetupPane provides pane, content = content)
}

private fun voiceSetupPane(voice: VoiceSetupAccess): @Composable (onDone: (Boolean) -> Unit) -> Unit =
    { onDone -> VoiceSetupPane(voice, onDone) }

/** [content] whose memory tool steps open the Memory view over [controllers], for the focused agent. */
@Composable
private fun ProvideShellMemoryPreview(
    controllers: ShellLibraryControllers,
    workspace: ShellWorkspaceState,
    content: @Composable () -> Unit,
) {
    val preview = remember(controllers.memory, controllers.memfs, workspace) {
        val sources = ShellMemorySources(
            memory = controllers.memory.state,
            memoryActions = controllers.memory,
            files = controllers.memfs.state,
            filesActions = guardedMemfsActions(controllers, workspace),
            selectFilesAgent = { agent ->
                if (agent == null) controllers.memfs.selectAgent(null)
                else requestMemoryAgent(controllers, workspace, agent) {}
            },
        )
        ShellMemoryPreview(sources) { workspace.currentFocus.agentId }
    }
    CompositionLocalProvider(LocalMemoryPreview provides preview, content = content)
}

/** The shared Memory page over the memory graph and MemFS controllers. */
private fun memoryPage(controllers: ShellLibraryControllers, host: ShellPageHost) =
    ShellPageContent<ShellDestination.Memory> { _, modifier ->
        val memory by controllers.memory.state.collectAsState()
        val files by controllers.memfs.state.collectAsState()
        var view by rememberMemoryWorkspaceView()
        val workspace = host.navigator.state
        val filesActions = guardedMemfsActions(controllers, workspace)
        val binding = MemoryWorkspaceBinding(
            memory = memoryForFocus(memory, workspace.currentFocus.agentId),
            memoryActions = controllers.memory,
            files = files,
            filesActions = filesActions,
            onFilesAgentChange = { controllers.memfs.selectAgent(workspace.currentFocus.agentId?.value) },
            view = view,
            onViewChange = { view = it },
        )
        FocusMemoryWorkspacePage(binding, onSelectAgent = { agent ->
            requestMemoryAgent(controllers, workspace, agent) { view = MemoryWorkspaceView.Files }
        }, modifier = modifier)
    }

private fun requestMemoryAgent(
    controllers: ShellLibraryControllers,
    workspace: ShellWorkspaceState,
    agent: AgentId,
    onHeld: () -> Unit,
) {
    val files = controllers.memfs.state.value
    if (files.agentId != agent.value && files.editor?.dirty == true) {
        controllers.memfs.selectAgent(agent.value)
        onHeld()
    } else if (selectPageAgent(workspace, agent)) {
        controllers.memfs.selectAgent(agent.value)
    }
}

private fun guardedMemfsActions(controllers: ShellLibraryControllers, workspace: ShellWorkspaceState): MemfsPageActions =
    object : MemfsPageActions by controllers.memfs {
        override fun confirmDiscard() {
            val files = controllers.memfs.state.value
            val pendingAgent = (files.pendingNavigation as? MemfsNavigation.SwitchAgent)?.agentId
            if (pendingAgent != null && workspace.currentFocus.agentId?.value != pendingAgent) {
                if (workspace.currentFocus.agentId?.value != files.agentId) return
                if (!selectPageAgent(workspace, AgentId(pendingAgent))) return
            }
            controllers.memfs.confirmDiscard()
        }
    }

/** Do not render another agent's graph or expose its actions while the focused target loads. */
internal fun memoryForFocus(memory: MemoryPageState, agent: AgentId?): MemoryPageState =
    if (memory.parity.memory.selectedAgentId == agent?.value) {
        memory
    } else {
        MemoryPageState(parity = MemoryParityControllerState(
            isLoading = memory.parity.isLoading,
            errorMessage = memory.parity.errorMessage,
        ))
    }

/** Publish the chosen agent together with a cleared conversation, before starting its queries. */
private fun selectPageAgent(workspace: ShellWorkspaceState, agent: AgentId): Boolean {
    if (workspace.currentFocus.agentId == agent) return true
    return workspace.selectFocus(workspace.currentFocus.copy(
        agentId = agent, conversationId = null, chatMode = CurrentFocusChatMode.Resume,
    ))
}

/** The shared Schedules page; a schedule can be made where the session serves them and an agent is chosen. */
private fun schedulesPage(
    graph: SessionRepositoryGraph,
    schedules: ScheduleLibraryController,
    host: ShellPageHost,
) = ShellPageContent<ShellDestination.Schedules> { destination, modifier ->
    val library by schedules.state.collectAsState()
    val focused = host.navigator.state.currentFocus.agentId?.value
    val agentChosen = focused != null
    val state = SchedulesPageState(
        library = library,
        focusedAgentId = focused,
        canCreate = graph.capabilities.supports(SessionRoute.SCHEDULES) && agentChosen,
    )
    shellSchedulesPage(state, shellScheduleActions(schedules, host.navigator.state)).Content(destination, modifier)
}

/**
 * The Schedules page's actions over the shared library. A row or form can mutate only while its
 * agent still matches both the workspace focus and the library's current query target.
 */
internal fun shellScheduleActions(
    schedules: ScheduleLibraryController,
    workspace: ShellWorkspaceState,
) = SchedulesPageActions(
    onRefresh = {
        if (workspace.currentFocus.agentId?.value == schedules.state.value.selectedAgentId) schedules.loadData()
    },
    onSelectAgent = { id -> selectPageAgent(workspace, AgentId(id)) },
    onDeleteSchedule = { ref ->
        if (workspace.currentFocus.agentId?.value == ref.agentId && schedules.state.value.selectedAgentId == ref.agentId) {
            schedules.deleteSchedule(ref.scheduleId)
        }
    },
    onCreate = { request ->
        val agent = workspace.currentFocus.agentId?.value
        if (agent != null && schedules.state.value.selectedAgentId == agent && (request.agentId == null || request.agentId == agent)) {
            schedules.createRecurringSchedule(request.copy(agentId = agent))
        }
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
        val focused = host.navigator.state.currentFocus.agentId
        val matchesFocus = focused != null && catalog.agentId == focused.value && !catalog.isLoading
        val state = SkillsPageState(
            skills = catalog.skills,
            installedSkillNames = catalog.installedSkillNames.takeIf { matchesFocus }.orEmpty(),
            skillsLoading = catalog.isLoading,
            skillsError = catalog.errorMessage,
            skillActionError = catalog.actionError,
            canManageSkills = catalog.canManage && matchesFocus,
            focusedAgentName = host.focus.agentName.takeIf { host.focus.agentId == focused },
            tools = tools,
        )
        val actions = SkillsPageActions(
            onRefreshSkills = { skills.reload(host.navigator.state.currentFocus.agentId?.value) },
            onInstallSkill = { name -> if (skillsMatchFocus(skills, host.navigator.state)) skills.install(name) },
            onUninstallSkill = { name -> if (skillsMatchFocus(skills, host.navigator.state)) skills.uninstall(name) },
            onDismissSkillActionError = skills::dismissActionError,
            onRefreshTools = toolLibrary::loadTools,
            onToolTagToggled = toolLibrary::toggleTag,
            onClearToolTags = toolLibrary::clearTags,
            onLoadMoreTools = toolLibrary::loadMoreTools,
        )
        shellSkillsPage(state, actions, SkillsPageOptions(slots = host.pages.slots)).Content(destination, modifier)
    }

private fun skillsMatchFocus(skills: SkillsCatalogController, workspace: ShellWorkspaceState): Boolean {
    val agent = workspace.currentFocus.agentId ?: return false
    val catalog = skills.state.value
    return catalog.agentId == agent.value && !catalog.isLoading
}
