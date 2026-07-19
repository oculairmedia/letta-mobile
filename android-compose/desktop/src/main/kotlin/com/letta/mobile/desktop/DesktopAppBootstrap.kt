package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.letta.mobile.data.commands.AgentSlashCommand
import com.letta.mobile.data.commands.SlashCommandApi
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.iroh.IrohAdminRpcAgentDirectory
import com.letta.mobile.data.schedules.CronApi
import com.letta.mobile.data.schedules.CronTask
import com.letta.mobile.data.skills.Skill
import com.letta.mobile.data.skills.SkillsApi
import com.letta.mobile.desktop.channels.DesktopChannelLibraryController
import com.letta.mobile.desktop.chat.DesktopChatController
import com.letta.mobile.desktop.data.DesktopDataBindings
import com.letta.mobile.desktop.data.DesktopFileSecureSettingsStore
import com.letta.mobile.desktop.data.DesktopLettaConfigStore
import com.letta.mobile.desktop.data.DesktopSessionGraphProvider
import com.letta.mobile.desktop.data.createDefaultDesktopDataBindings
import com.letta.mobile.desktop.memory.DesktopMemoryController
import com.letta.mobile.desktop.schedules.DesktopScheduleLibraryController
import com.letta.mobile.desktop.tools.DesktopToolLibraryController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Config store, session graph bindings, and bootstrap state for the desktop shell.
 * [irohAgentDirectorySlot] is updated from [LettaDesktopApp] via SideEffect once
 * the iroh transport-derived directory is available.
 */
internal data class DesktopConfigBootstrap(
    val secureSettingsStore: DesktopFileSecureSettingsStore,
    val dataBindings: DesktopDataBindings,
    val activeConfig: LettaConfig,
    val bootstrapState: DesktopBootstrapState,
    val applyConfig: (LettaConfig) -> Unit,
    val irohAgentDirectorySlot: MutableState<IrohAdminRpcAgentDirectory?>,
)

@Composable
internal fun rememberDesktopConfigBootstrap(): DesktopConfigBootstrap {
    val secureSettingsStore = remember { DesktopFileSecureSettingsStore() }
    val configStore = remember(secureSettingsStore) { DesktopLettaConfigStore(secureSettingsStore) }
    var activeConfig by remember { mutableStateOf(configStore.load()) }
    val irohAgentDirectorySlot = remember { mutableStateOf<IrohAdminRpcAgentDirectory?>(null) }
    val dataBindings = remember(configStore) {
        createDefaultDesktopDataBindings(
            secureSettingsStore = secureSettingsStore,
            configProvider = { activeConfig },
            irohAgentDirectoryProvider = { irohAgentDirectorySlot.value },
        )
    }
    var bootstrapState by remember(dataBindings) {
        mutableStateOf(defaultDesktopBootstrapState(dataBindings, activeConfig))
    }
    val applyConfig: (LettaConfig) -> Unit = remember(configStore, dataBindings) {
        { nextConfig ->
            configStore.save(nextConfig)
            activeConfig = configStore.load()
            dataBindings.sessionGraphProvider.rebuild()
            bootstrapState = defaultDesktopBootstrapState(dataBindings, activeConfig)
        }
    }
    return DesktopConfigBootstrap(
        secureSettingsStore = secureSettingsStore,
        dataBindings = dataBindings,
        activeConfig = activeConfig,
        bootstrapState = bootstrapState,
        applyConfig = applyConfig,
        irohAgentDirectorySlot = irohAgentDirectorySlot,
    )
}

/** The per-agent library controllers behind the sidebar destinations. */
internal class DesktopLibraryControllers(
    val memory: DesktopMemoryController,
    val schedules: DesktopScheduleLibraryController,
    val channels: DesktopChannelLibraryController,
    val tools: DesktopToolLibraryController,
)

@Composable
internal fun rememberDesktopLibraryControllers(
    sessionGraphId: Long,
    sessionGraphProvider: DesktopSessionGraphProvider,
    chatScope: CoroutineScope,
): DesktopLibraryControllers {
    val memory = remember(sessionGraphId, chatScope) {
        DesktopMemoryController(sessionGraphProvider = sessionGraphProvider, scope = chatScope)
    }
    val schedules = remember(sessionGraphId, chatScope) {
        DesktopScheduleLibraryController(sessionGraphProvider = sessionGraphProvider, scope = chatScope)
    }
    val channels = remember(sessionGraphId, chatScope) {
        DesktopChannelLibraryController(sessionGraphProvider = sessionGraphProvider, scope = chatScope)
    }
    val tools = remember(sessionGraphId, chatScope) {
        DesktopToolLibraryController(sessionGraphProvider = sessionGraphProvider, scope = chatScope)
    }
    return DesktopLibraryControllers(memory, schedules, channels, tools)
}

/**
 * Start/stop lifecycles for the chat + library controllers, plus the
 * destination-driven agent selection and cron refresh.
 */
@Composable
internal fun DesktopControllerLifecycles(
    chatController: DesktopChatController,
    libraries: DesktopLibraryControllers,
    selectedDestination: DesktopDestination,
    selectedConversationAgentId: String?,
    cronPanel: DesktopCronPanelState,
) {
    LaunchedEffect(chatController) {
        chatController.start()
    }
    DisposableEffect(chatController) {
        onDispose { chatController.close() }
    }
    LaunchedEffect(libraries.memory) {
        libraries.memory.start()
    }
    LaunchedEffect(libraries.schedules) {
        libraries.schedules.start()
    }
    LaunchedEffect(libraries.channels) {
        libraries.channels.start()
    }
    LaunchedEffect(libraries.tools) {
        libraries.tools.start()
    }
    LaunchedEffect(selectedDestination, selectedConversationAgentId, libraries.memory) {
        if (selectedDestination == DesktopDestination.Memory) {
            selectedConversationAgentId?.let(libraries.memory::selectAgent)
        }
    }
    LaunchedEffect(selectedDestination, selectedConversationAgentId, libraries.schedules) {
        if (selectedDestination == DesktopDestination.Schedules) {
            selectedConversationAgentId?.let(libraries.schedules::selectAgent)
        }
    }
    LaunchedEffect(selectedDestination, cronPanel) {
        if (selectedDestination == DesktopDestination.Schedules) {
            cronPanel.refresh()
        }
    }
    DisposableEffect(libraries.memory) {
        onDispose { libraries.memory.close() }
    }
    DisposableEffect(libraries.schedules) {
        onDispose { libraries.schedules.close() }
    }
    DisposableEffect(libraries.channels) {
        onDispose { libraries.channels.close() }
    }
    DisposableEffect(libraries.tools) {
        onDispose { libraries.tools.close() }
    }
}

/**
 * Cmd/Ctrl-K opens the command palette (Penpot shows the ⌘K hint). A global
 * AWT key dispatcher fires regardless of which Compose field has focus.
 */
@Composable
internal fun CommandPaletteKeyDispatcherEffect(onOpenPalette: () -> Unit) {
    DisposableEffect(Unit) {
        val focusManager = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val dispatcher = java.awt.KeyEventDispatcher { event ->
            if (event.id == java.awt.event.KeyEvent.KEY_PRESSED &&
                event.keyCode == java.awt.event.KeyEvent.VK_K &&
                (event.isControlDown || event.isMetaDown)
            ) {
                onOpenPalette()
                true
            } else {
                false
            }
        }
        focusManager.addKeyEventDispatcher(dispatcher)
        onDispose { focusManager.removeKeyEventDispatcher(dispatcher) }
    }
}

/** A cron creation request from the schedules surface. */
internal data class CronDraft(
    val agentId: String,
    val name: String,
    val prompt: String,
    val cron: String,
    val recurring: Boolean,
    val timezone: String,
)

/**
 * Snapshot-backed state + actions for the cron panel. Every mutation reloads
 * the list from the API so the panel reflects the server's view; all actions
 * no-op when the backend has no HTTP cron API (iroh mode).
 */
internal class DesktopCronPanelState(
    private val cronApi: CronApi?,
    private val scope: CoroutineScope,
) {
    var crons by mutableStateOf<List<CronTask>>(emptyList())
        private set

    val available: Boolean get() = cronApi != null

    suspend fun refresh() {
        val api = cronApi ?: return
        crons = runCatching { api.listCrons() }.getOrDefault(emptyList())
    }

    fun delete(id: String) {
        val api = cronApi ?: return
        scope.launch {
            runCatching { api.deleteCron(id) }
            refresh()
        }
    }

    fun create(draft: CronDraft) {
        val api = cronApi ?: return
        scope.launch {
            runCatching {
                api.createCron(
                    agentId = draft.agentId,
                    name = draft.name,
                    description = draft.name,
                    prompt = draft.prompt,
                    cron = draft.cron,
                    timezone = draft.timezone,
                    recurring = draft.recurring,
                )
            }
            refresh()
        }
    }
}

/**
 * Snapshot-backed state + actions for the Skills page: the registry list,
 * the focused agent's installed set, and install/remove with reload. All
 * actions no-op when the active backend exposes no skill API.
 */
internal class DesktopSkillsPanelState(
    private val skillApi: SkillsApi?,
    private val scope: CoroutineScope,
) {
    var all by mutableStateOf<List<Skill>>(emptyList())
        private set
    var installedNames by mutableStateOf<Set<String>>(emptySet())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    val available: Boolean get() = skillApi != null

    /** Load the registry + [agentId]'s installed skills (null agent -> none). */
    suspend fun reload(agentId: String?) {
        val api = skillApi ?: return
        loading = true
        error = null
        runCatching {
            all = api.listSkills()
            installedNames = agentId?.let { api.listAgentSkills(it).map { s -> s.name }.toSet() }
                ?: emptySet()
        }.onFailure { error = it.message ?: "Could not load skills." }
        loading = false
    }

    fun install(agentId: String?, name: String) {
        val api = skillApi ?: return
        val agent = agentId ?: return
        scope.launch {
            runCatching { api.installSkill(agent, name) }
                .onFailure { error = it.message ?: "Could not install skill." }
            reload(agent)
        }
    }

    fun uninstall(agentId: String?, name: String) {
        val api = skillApi ?: return
        val agent = agentId ?: return
        scope.launch {
            runCatching { api.uninstallSkill(agent, name) }
                .onFailure { error = it.message ?: "Could not remove skill." }
            reload(agent)
        }
    }
}

/** The focused agent's server slash commands for the composer palette. */
internal suspend fun loadAgentSlashCommands(
    api: SlashCommandApi?,
    agentId: String?,
): List<AgentSlashCommand> = if (api != null && agentId != null) {
    runCatching { api.listAgentSlashCommands(agentId) }.getOrDefault(emptyList())
} else {
    emptyList()
}
