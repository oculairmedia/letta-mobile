package com.letta.mobile.ui.shell.pages

import com.letta.mobile.data.backend.SessionPlatform
import com.letta.mobile.data.memory.memfs.AppServerMemfsSource
import com.letta.mobile.data.memory.memfs.MemfsPageController
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

    private fun registeredFor(platform: SessionPlatform, scope: CoroutineScope): Set<ShellPageKind> {
        val binding = HostSessionBinding(platform = platform, config = iroh, appServerClients = HostAppServerClients { null })
        val provider = HostSessionGraphProvider(HostSessionGraphFactory { binding }, platform)
        val graph = provider.current
        val settings = InMemorySecureSettingsStore()
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

    private fun host(settings: InMemorySecureSettingsStore) = ShellPageHost(
        navigator = ShellWorkspaceNavigator(ShellWorkspaceState(), NoChat),
        focus = ShellPageFocus(agentId = null, agentName = null),
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
