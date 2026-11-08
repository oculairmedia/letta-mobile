@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.pages

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.backend.SessionPlatform
import com.letta.mobile.data.model.AgentSummary
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.IScheduleRepository
import com.letta.mobile.data.schedules.ScheduleLibraryController
import com.letta.mobile.data.skills.Skill
import com.letta.mobile.data.skills.SkillsApi
import com.letta.mobile.data.skills.SkillsCatalogController
import com.letta.mobile.ui.shell.pages.skills.SkillsPageTags
import com.letta.mobile.data.shell.CurrentFocus
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.ProvideLettaHost
import com.letta.mobile.ui.shell.ShellPage
import com.letta.mobile.ui.shell.pages.schedules.SchedulesPageTags
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import com.letta.mobile.data.memory.memfs.AppServerMemfsSource
import com.letta.mobile.data.memory.memfs.MemfsPageController
import com.letta.mobile.data.memory.memfs.MemfsNavigation
import com.letta.mobile.data.memory.memfs.MemfsSource
import com.letta.mobile.data.memory.memfs.MemfsListing
import com.letta.mobile.data.memory.memfs.MemfsFile
import com.letta.mobile.data.memory.memfs.MemfsFileKind
import com.letta.mobile.data.memory.memfs.MemfsFileRef
import com.letta.mobile.data.memory.MemoryParitySource
import com.letta.mobile.data.memory.MemoryParityState
import com.letta.mobile.data.memory.MemoryParityControllerState
import com.letta.mobile.data.memory.MemoryParityAgentOption
import com.letta.mobile.data.memory.graph.MemoryPageController
import com.letta.mobile.data.memory.graph.MemoryPageState
import com.letta.mobile.data.memory.graph.MemoryPageActions
import com.letta.mobile.data.memory.memfs.MemfsPageState
import com.letta.mobile.data.memory.memfs.MemfsPageActions
import com.letta.mobile.data.memory.graph.MemoryBlockContentPort
import com.letta.mobile.ui.shell.pages.memfs.MemfsPageTags
import com.letta.mobile.ui.shell.pages.memory.MemoryWorkspaceTags
import com.letta.mobile.ui.shell.pages.memory.MemoryWorkspaceView
import com.letta.mobile.data.chat.tools.ToolPreviewTarget
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.modelcontrol.AdminRpcInvoker
import com.letta.mobile.data.repository.modelcontrol.ModelControlSession
import com.letta.mobile.data.session.graph.HostAppServerClients
import com.letta.mobile.data.session.graph.HostSessionBinding
import com.letta.mobile.data.session.graph.HostSessionGraphFactory
import com.letta.mobile.data.session.graph.HostSessionGraphProvider
import com.letta.mobile.data.shell.ShellChatPort
import com.letta.mobile.data.shell.ShellConversationRef
import com.letta.mobile.data.shell.ShellPageKind
import com.letta.mobile.data.shell.ShellWorkspaceNavigator
import com.letta.mobile.data.shell.ShellWorkspaceState
import com.letta.mobile.data.objectverbs.binding.withDevicePins
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import com.letta.mobile.ui.shell.ShellPageContent
import com.letta.mobile.ui.shell.pages.plugins.PluginsPageBinding
import com.letta.mobile.ui.shell.pages.plugins.shellPluginsPage
import com.letta.mobile.data.plugin.catalog.UnavailablePluginCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * letta-mobile-ckmub.17.5 acceptance 1: over an Iroh session, the desktop and the browser register the
 * same shell pages, because both build them with [shellPageDeps] over the shared session graph.
 */
class ShellPageDepsParityTest {
    private val iroh = LettaConfig(id = "iroh", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "iroh://node@relay")

    @Test
    fun desktopAndWebRegisterTheSamePagesForAnIrohSession() = runTest {
        val desktop = registeredFor(SessionPlatform.DESKTOP, backgroundScope)
        val web = registeredFor(SessionPlatform.WEB, backgroundScope)

        assertEquals(desktop, web)
        assertEquals(EVERY_PAGE, desktop)
    }

    @Test
    fun schedulesPickerDispatchesTheWorkspaceFocus() = runComposeUiTest {
        val binding = HostSessionBinding(
            platform = SessionPlatform.DESKTOP, config = iroh, appServerClients = HostAppServerClients { null },
        )
        val provider = HostSessionGraphProvider(HostSessionGraphFactory { binding }, SessionPlatform.DESKTOP)
        val graph = provider.current
        val settings = InMemorySecureSettingsStore().withDevicePins()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val agentRepository = Proxy.newProxyInstance(
            IAgentRepository::class.java.classLoader, arrayOf(IAgentRepository::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "listAgentSummaries" -> listOf(AgentSummary(AgentId("a1"), "One"), AgentSummary(AgentId("a2"), "Two"))
                else -> error("unexpected agent call: ${method.name}")
            }
        } as IAgentRepository
        val scheduleRepository = Proxy.newProxyInstance(
            IScheduleRepository::class.java.classLoader, arrayOf(IScheduleRepository::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "refreshSchedules" -> Unit
                "getSchedules" -> flowOf(emptyList<com.letta.mobile.data.model.ScheduledMessage>())
                else -> error("unexpected schedule call: ${method.name}")
            }
        } as IScheduleRepository
        val schedules = ScheduleLibraryController(agentRepository, scheduleRepository, scope)
        schedules.start()
        val controllers = ShellLibraryControllers(
            memory = ShellLibraryFactory.memory(provider, scope), schedules = schedules,
            channels = ShellLibraryFactory.channels(provider, scope), tools = ShellLibraryFactory.tools(graph, scope),
            skills = ShellLibraryFactory.skills(graph, scope), home = ShellLibraryFactory.home(provider, settings, scope),
            memfs = MemfsPageController(AppServerMemfsSource({ error("no session") }, emptyFlow(), { it }), scope),
        )
        val workspace = ShellWorkspaceState()
        workspace.selectFocus(CurrentFocus(pageRoute = "schedules"))
        val shellHost = host(settings, workspace, ShellPageFocus(AgentId("a1"), "One"))
        val deps = shellPageDeps(graph, controllers, shellHost)
        setContent {
            MaterialTheme { ProvideLettaHost(LettaHost.Pointer) { ShellPage(ShellDestination.Schedules(), deps) } }
        }
        onNodeWithTag(SchedulesPageTags.agent("a2")).performClick()
        assertEquals(AgentId("a2"), workspace.currentFocus.agentId)
        scope.cancel()
        graph.close()
    }

    @Test
    fun memoryPickerHoldsFocusUntilDirtyFileIsDiscarded() = runComposeUiTest {
        val binding = HostSessionBinding(
            platform = SessionPlatform.DESKTOP, config = iroh, appServerClients = HostAppServerClients { null },
        )
        val provider = HostSessionGraphProvider(HostSessionGraphFactory { binding }, SessionPlatform.DESKTOP)
        val graph = provider.current
        val settings = InMemorySecureSettingsStore().withDevicePins()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val source = object : MemoryParitySource {
            override val state = MutableStateFlow(MemoryParityControllerState(
                memory = MemoryParityState(selectedAgentId = "a1", selectedAgentName = "Ada"),
                agents = listOf(MemoryParityAgentOption("a1", "Ada"), MemoryParityAgentOption("a2", "Bo")),
            ))
            override fun start() = Unit
            override fun reload() = Unit
            override fun selectAgent(agentId: String) {
                state.value = state.value.copy(memory = state.value.memory.copy(selectedAgentId = agentId))
            }
            override fun close() = Unit
        }
        val memory = MemoryPageController(source, memoryBlocks(), scope, layoutDispatcher = Dispatchers.Unconfined)
        memory.start()
        val files = memfsSource()
        val memfs = MemfsPageController(files, scope)
        memfs.selectAgent("a1")
        memfs.openFile("system/persona.md")
        memfs.editDraft("Unsaved")
        val controllers = ShellLibraryControllers(
            memory = memory, schedules = ShellLibraryFactory.schedules(graph, scope),
            channels = ShellLibraryFactory.channels(provider, scope), tools = ShellLibraryFactory.tools(graph, scope),
            skills = ShellLibraryFactory.skills(graph, scope), home = ShellLibraryFactory.home(provider, settings, scope), memfs = memfs,
        )
        val workspace = ShellWorkspaceState().apply { selectFocus(CurrentFocus(pageRoute = "memory", agentId = AgentId("a1"))) }
        val deps = shellPageDeps(graph, controllers, host(settings, workspace, ShellPageFocus(AgentId("a1"), "Ada")))
        setContent { MaterialTheme { ProvideLettaHost(LettaHost.Pointer) { ShellPage(ShellDestination.Memory(), deps) } } }
        onNodeWithText("Ada").performClick()
        onNodeWithText("Bo").performClick()
        waitForIdle()
        assertEquals(AgentId("a1"), workspace.currentFocus.agentId)
        assertEquals("a1", source.state.value.memory.selectedAgentId)
        assertEquals("a1", memfs.state.value.agentId)
        assertEquals(MemfsNavigation.SwitchAgent("a2"), memfs.state.value.pendingNavigation)
        onNodeWithTag(MemfsPageTags.DISCARD_CANCEL).performClick()
        assertEquals(AgentId("a1"), workspace.currentFocus.agentId)
        assertEquals("Unsaved", memfs.state.value.editor?.draft)
        onNodeWithTag(MemoryWorkspaceTags.view(MemoryWorkspaceView.Blocks)).performClick()
        onNodeWithText("Ada").performClick()
        onNodeWithText("Bo").performClick()
        onNodeWithTag(MemfsPageTags.DISCARD_CONFIRM).performClick()
        assertEquals(AgentId("a2"), workspace.currentFocus.agentId)
        onNodeWithTag(MemoryWorkspaceTags.view(MemoryWorkspaceView.Blocks)).performClick()
        onNodeWithText("Ada").assertDoesNotExist()
        scope.cancel()
        graph.close()
    }

    @Test
    fun memoryPreviewDoesNotUseAnOldAgentWhenFocusIsEmpty() {
        val calls = mutableListOf<String>()
        val memoryActions = Proxy.newProxyInstance(
            MemoryPageActions::class.java.classLoader, arrayOf(MemoryPageActions::class.java),
        ) { _, method, _ ->
            calls += method.name
            null
        } as MemoryPageActions
        val fileActions = Proxy.newProxyInstance(
            MemfsPageActions::class.java.classLoader, arrayOf(MemfsPageActions::class.java),
        ) { _, method, _ ->
            calls += method.name
            null
        } as MemfsPageActions
        val sources = ShellMemorySources(
            memory = MutableStateFlow(MemoryPageState(
                parity = MemoryParityControllerState(memory = MemoryParityState(selectedAgentId = "a1")),
            )),
            memoryActions = memoryActions,
            files = MutableStateFlow(MemfsPageState(agentId = "a1")),
            filesActions = fileActions,
            selectFilesAgent = { calls += "selectFilesAgent" },
        )
        val preview = ShellMemoryPreview(sources) { null }
        val step = UiToolCall(name = "memory", arguments = "{}", result = "OK", status = "success", toolCallId = "call")
        preview.open(ToolPreviewTarget.Memory("system/persona.md"), step)
        val emptyFocusTarget = preview.openTarget
        val emptyFocusCalls = calls.toList()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val files = MemfsPageController(memfsSource(), scope)
        files.selectAgent("a1")
        files.openFile("system/persona.md")
        files.editDraft("Unsaved")
        val guarded = ShellMemoryPreview(ShellMemorySources(
            memory = sources.memory,
            memoryActions = memoryActions,
            files = files.state,
            filesActions = files,
            selectFilesAgent = { files.selectAgent(it?.value) },
        )) { AgentId("a2") }
        guarded.open(ToolPreviewTarget.Memory("system/human.md"), step)
        val pending = files.state.value.pendingNavigation
        scope.cancel()
        assertEquals(
            listOf(null, emptyList<String>(), MemfsNavigation.SwitchAgent("a2")),
            listOf(emptyFocusTarget, emptyFocusCalls, pending),
        )
    }

    @Test
    fun skillsForThePreviousAgentCannotBeChangedAfterFocusMoves() = runComposeUiTest {
        val binding = HostSessionBinding(
            platform = SessionPlatform.DESKTOP, config = iroh, appServerClients = HostAppServerClients { null },
        )
        val provider = HostSessionGraphProvider(HostSessionGraphFactory { binding }, SessionPlatform.DESKTOP)
        val graph = provider.current
        val settings = InMemorySecureSettingsStore().withDevicePins()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val api = object : SkillsApi {
            override suspend fun listSkills() = listOf(Skill("github", controllable = true))
            override suspend fun listAgentSkills(agentId: String): List<Skill> {
                check(agentId.isNotBlank())
                return listOf(Skill("github", controllable = true))
            }
            override suspend fun installSkill(agentId: String, skillName: String) = error("unexpected install")
            override suspend fun uninstallSkill(agentId: String, skillName: String) = error("unexpected uninstall")
        }
        val skills = SkillsCatalogController(api, scope).apply { reload("a1") }
        val controllers = ShellLibraryControllers(
            memory = ShellLibraryFactory.memory(provider, scope), schedules = ShellLibraryFactory.schedules(graph, scope),
            channels = ShellLibraryFactory.channels(provider, scope), tools = ShellLibraryFactory.tools(graph, scope),
            skills = skills, home = ShellLibraryFactory.home(provider, settings, scope),
            memfs = MemfsPageController(AppServerMemfsSource({ error("no session") }, emptyFlow(), { it }), scope),
        )
        val workspace = ShellWorkspaceState().apply { selectFocus(CurrentFocus(pageRoute = "skills", agentId = AgentId("a2"))) }
        val deps = shellPageDeps(graph, controllers, host(settings, workspace, ShellPageFocus(AgentId("a2"), "Bo")))
        setContent { MaterialTheme { ProvideLettaHost(LettaHost.Pointer) { ShellPage(ShellDestination.Skills(), deps) } } }
        onNodeWithTag(SkillsPageTags.skillToggle("github")).assertIsNotEnabled()
        scope.cancel()
        graph.close()
    }

    private fun memoryBlocks(): MemoryBlockContentPort = Proxy.newProxyInstance(
        MemoryBlockContentPort::class.java.classLoader, arrayOf(MemoryBlockContentPort::class.java),
    ) { _, method, _ ->
        if (method.name == "canWrite") false else error("unexpected block call: ${method.name}")
    } as MemoryBlockContentPort

    private fun memfsSource(): MemfsSource = Proxy.newProxyInstance(
        MemfsSource::class.java.classLoader, arrayOf(MemfsSource::class.java),
    ) { _, method, args ->
        when (method.name) {
            "list" -> {
                check(args[0] == "a1" || args[0] == "a2")
                MemfsListing(true, listOf(MemfsFile("system/persona.md", true, null, 8, MemfsFileKind.Markdown)))
            }
            "read" -> {
                check(args[0] == MemfsFileRef("a1", "system/persona.md"))
                "Original"
            }
            "getUpdates" -> emptyFlow<com.letta.mobile.data.memory.memfs.MemfsUpdate>()
            else -> error("unexpected file call: ${method.name}")
        }
    } as MemfsSource

    private fun registeredFor(platform: SessionPlatform, scope: CoroutineScope): Set<ShellPageKind> {
        val binding = HostSessionBinding(platform = platform, config = iroh, appServerClients = HostAppServerClients { null })
        val provider = HostSessionGraphProvider(HostSessionGraphFactory { binding }, platform)
        val graph = provider.current
        val settings = InMemorySecureSettingsStore().withDevicePins()
        val controllers = ShellLibraryControllers(
            memory = ShellLibraryFactory.memory(provider, scope),
            schedules = ShellLibraryFactory.schedules(graph, scope),
            channels = ShellLibraryFactory.channels(provider, scope),
            tools = ShellLibraryFactory.tools(graph, scope),
            skills = ShellLibraryFactory.skills(graph, scope),
            home = ShellLibraryFactory.home(provider, settings, scope),
            memfs = MemfsPageController(AppServerMemfsSource({ error("no session") }, emptyFlow(), { it }), scope),
        )
        val registered = shellPageDeps(graph, controllers, host(settings)).registered
        graph.close()
        return registered
    }

    private fun host(
        settings: SecureSettingsStore,
        workspace: ShellWorkspaceState = ShellWorkspaceState(),
        focus: ShellPageFocus = ShellPageFocus(agentId = null, agentName = null),
    ) = ShellPageHost(
        navigator = ShellWorkspaceNavigator(workspace, NoChat),
        focus = focus,
        pages = ShellHostPages(
            chat = ShellPageContent { _, _ -> },
            settings = ShellPageContent { _, _ -> },
            plugins = shellPluginsPage(PluginsPageBinding(UnavailablePluginCatalog())),
        ),
        home = ShellHomeHost(),
        agentEditor = ShellAgentEditorHost(),
        modelControl = ModelControlSession(AdminRpcInvoker { _, _ -> null }, oauthHttp = null),
        settings = settings,
    )

    private object NoChat : ShellChatPort {
        override fun conversations(): List<ShellConversationRef> = emptyList()

        override val selectedConversation: ConversationId? = null

        override fun selectConversation(conversation: ConversationId) = Unit

        override fun createConversationForAgent(agent: AgentId, onCreated: (ConversationId) -> Unit) = Unit

        override fun startNewChat(agent: AgentId?) = Unit

        override fun send(conversation: ConversationId, text: String) = Unit

        override fun stageComposerText(text: String) = Unit
    }

    private companion object {
        val EVERY_PAGE = setOf(
            ShellPageKind.Home,
            ShellPageKind.Chat,
            ShellPageKind.Canvas,
            ShellPageKind.Memory,
            ShellPageKind.Schedules,
            ShellPageKind.Channels,
            ShellPageKind.Skills,
            ShellPageKind.Settings,
            ShellPageKind.Overview,
            ShellPageKind.AgentEditor,
            ShellPageKind.Providers,
            ShellPageKind.Runs,
            ShellPageKind.Templates,
            ShellPageKind.Projects,
            ShellPageKind.Plugins,
        )
    }
}
