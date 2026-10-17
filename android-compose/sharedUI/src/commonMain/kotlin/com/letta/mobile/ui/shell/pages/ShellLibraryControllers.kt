package com.letta.mobile.ui.shell.pages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.channel.ChannelsPageController
import com.letta.mobile.data.home.HomePageController
import com.letta.mobile.data.home.SessionHomePageSource
import com.letta.mobile.data.home.SettingsStoreHomePinStore
import com.letta.mobile.data.memory.graph.MemoryPageController
import com.letta.mobile.data.memory.memfs.MemfsPageController
import com.letta.mobile.data.memory.memfs.MemfsSource
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.repository.iroh.IrohAdminRpcAgentDirectory
import com.letta.mobile.data.repository.iroh.IrohSkillsApi
import com.letta.mobile.data.schedules.ScheduleLibraryController
import com.letta.mobile.data.session.SessionRepositoryGraphProvider
import com.letta.mobile.data.session.graph.SharedSessionRepositoryGraph
import com.letta.mobile.data.session.ifSupported
import com.letta.mobile.data.shell.ShellHomeShortcuts
import com.letta.mobile.data.shell.ShellPageErrors
import com.letta.mobile.data.shell.ShellPageKind
import com.letta.mobile.data.skills.SkillsCatalogController
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.data.tools.ToolLibraryController
import kotlinx.coroutines.CoroutineScope

/** The session graph the shell's pages read: one provider per window (desktop) or tab (web). */
typealias ShellGraphProvider = SessionRepositoryGraphProvider<SharedSessionRepositoryGraph>

/**
 * What the library controllers read besides the session graph: the device's settings store (Home's
 * pins) and where the agent's MemFS files come from (an App Server session the host dials).
 */
class ShellLibrarySources(
    val settings: SecureSettingsStore,
    val memfs: MemfsSource,
)

/**
 * letta-mobile-ckmub.17.5: the controllers behind the shell's library pages (they were desktop's
 * `DesktopLibraryControllers`). The per-session ones are rebuilt with each session graph; Home and
 * MemFS follow the provider's current graph themselves.
 */
class ShellLibraryControllers(
    val memory: MemoryPageController,
    val schedules: ScheduleLibraryController,
    val channels: ChannelsPageController,
    val tools: ToolLibraryController,
    val skills: SkillsCatalogController,
    val home: HomePageController,
    val memfs: MemfsPageController,
)

/** The controllers over one graph generation; each is built from the shared controller classes. */
object ShellLibraryFactory {
    fun memory(provider: ShellGraphProvider, scope: CoroutineScope): MemoryPageController =
        MemoryPageController.forSession(provider, scope, ShellPageErrors::memory)

    /** Only an Iroh host relays schedules; the session's capabilities say so up front (letta-mobile-by4vm.13). */
    fun schedules(graph: SharedSessionRepositoryGraph, scope: CoroutineScope): ScheduleLibraryController =
        ScheduleLibraryController(
            agentRepository = graph.agentRepository,
            scheduleRepository = graph.scheduleRepository,
            scope = scope,
            errorMessageMapper = ShellPageErrors::message,
            scheduleAdminUnavailableMatcher = ShellPageErrors::isNotServed,
            nativeScheduleRoute = graph.capabilities.supports(SessionRoute.SCHEDULES),
        )

    fun channels(provider: ShellGraphProvider, scope: CoroutineScope): ChannelsPageController =
        ChannelsPageController.forSession(provider, scope)

    fun tools(graph: SharedSessionRepositoryGraph, scope: CoroutineScope): ToolLibraryController =
        ToolLibraryController(graph.toolRepository, graph.mcpServerRepository, scope, ShellPageErrors::tools)

    /** The skill registry rides an Iroh host's `admin_rpc`; elsewhere the catalog reports itself unavailable. */
    fun skills(graph: SharedSessionRepositoryGraph, scope: CoroutineScope): SkillsCatalogController {
        val api = graph.ifSupported(SessionRoute.SKILLS) { IrohSkillsApi(IrohAdminRpcAgentDirectory(channelTransport)) }
        return SkillsCatalogController(api, scope)
    }

    fun home(provider: ShellGraphProvider, settings: SecureSettingsStore, scope: CoroutineScope): HomePageController =
        HomePageController(
            source = SessionHomePageSource(sessionGraphProvider = provider, scope = scope),
            pins = SettingsStoreHomePinStore(settings, ShellHomeShortcuts.defaultPins, ShellHomeShortcuts.PIN_STORAGE_KEY),
            scope = scope,
            config = ShellHomeShortcuts.config,
        )
}

/**
 * The shell's library controllers over [provider], started while they are in composition and closed
 * when they leave it (a new graph replaces the per-session ones).
 */
@Composable
fun rememberShellLibraryControllers(
    provider: ShellGraphProvider,
    sources: ShellLibrarySources,
    scope: CoroutineScope,
): ShellLibraryControllers {
    val graph by provider.currentGraph.collectAsState()
    val generation = graph.id
    val controllers = ShellLibraryControllers(
        memory = remember(generation, scope) { ShellLibraryFactory.memory(provider, scope) },
        schedules = remember(generation, scope) { ShellLibraryFactory.schedules(graph, scope) },
        channels = remember(generation, scope) { ShellLibraryFactory.channels(provider, scope) },
        tools = remember(generation, scope) { ShellLibraryFactory.tools(graph, scope) },
        skills = remember(generation, scope) { ShellLibraryFactory.skills(graph, scope) },
        home = remember(provider, sources.settings, scope) { ShellLibraryFactory.home(provider, sources.settings, scope) },
        memfs = remember(sources.memfs, scope) { MemfsPageController(sources.memfs, scope) },
    )
    ShellLibraryLifecycles(controllers)
    return controllers
}

@Composable
private fun ShellLibraryLifecycles(controllers: ShellLibraryControllers) {
    StartedWhileComposed(controllers.memory) { it.start() }
    StartedWhileComposed(controllers.schedules) { it.start() }
    StartedWhileComposed(controllers.channels) { it.start() }
    StartedWhileComposed(controllers.tools) { it.start() }
    StartedWhileComposed(controllers.skills) {}
    StartedWhileComposed(controllers.home) { it.start() }
    StartedWhileComposed(controllers.memfs) { it.start() }
}

@Composable
private fun <T : AutoCloseable> StartedWhileComposed(controller: T, start: (T) -> Unit) {
    LaunchedEffect(controller) { start(controller) }
    DisposableEffect(controller) { onDispose { controller.close() } }
}

/**
 * The page on screen follows the focused [agent]: Memory and Schedules select it, and Skills loads
 * its installed set whenever the page opens or the agent changes.
 */
@Composable
fun ShellLibraryFocusEffects(controllers: ShellLibraryControllers, page: ShellPageKind, agent: AgentId?) {
    LaunchedEffect(controllers.memory, page, agent) {
        if (page == ShellPageKind.Memory && agent != null) controllers.memory.selectAgent(agent.value)
    }
    LaunchedEffect(controllers.schedules, page, agent) {
        if (page == ShellPageKind.Schedules && agent != null) controllers.schedules.selectAgent(agent.value)
    }
    LaunchedEffect(controllers.skills, page, agent) {
        if (page == ShellPageKind.Skills) controllers.skills.reload(agent?.value)
    }
}
