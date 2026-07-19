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
import com.letta.mobile.data.commands.SlashCommandsApi
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

internal data class DesktopDestinationSelection(
    val selectedDestination: DesktopDestination,
    val selectedConversationAgentId: DesktopAgentId?,
)

internal data class DesktopControllerLifecycleParams(
    val chatController: DesktopChatController,
    val libraries: DesktopLibraryControllers,
    val selection: DesktopDestinationSelection,
    val cronPanel: DesktopCronPanelState,
)

/**
 * Start/stop lifecycles for the chat + library controllers, plus the
 * destination-driven agent selection and cron refresh.
 */
@Composable
internal fun DesktopControllerLifecycles(params: DesktopControllerLifecycleParams) {
    val libraries = params.libraries
    LibraryControllerLifecycles(chatController = params.chatController, libraries = libraries)
    DestinationAgentSelectionEffect(
        DestinationAgentSelectionParams(
            selection = params.selection,
            targetDestination = DesktopDestination.Memory,
            onSelectAgent = libraries.memory::selectAgent,
        ),
    )
    DestinationAgentSelectionEffect(
        DestinationAgentSelectionParams(
            selection = params.selection,
            targetDestination = DesktopDestination.Schedules,
            onSelectAgent = libraries.schedules::selectAgent,
        ),
    )
    DestinationRefreshEffect(
        DestinationRefreshParams(
            selectedDestination = params.selection.selectedDestination,
            targetDestination = DesktopDestination.Schedules,
            onRefresh = params.cronPanel::refresh,
        ),
    )
}

@Composable
private fun LibraryControllerLifecycles(
    chatController: DesktopChatController,
    libraries: DesktopLibraryControllers,
) {
    ControllerLifecycleEffect(
        controller = chatController,
        onStart = { start() },
        onClose = { close() },
    )
    ControllerLifecycleEffect(
        controller = libraries.memory,
        onStart = { start() },
        onClose = { close() },
    )
    ControllerLifecycleEffect(
        controller = libraries.schedules,
        onStart = { start() },
        onClose = { close() },
    )
    ControllerLifecycleEffect(
        controller = libraries.channels,
        onStart = { start() },
        onClose = { close() },
    )
    ControllerLifecycleEffect(
        controller = libraries.tools,
        onStart = { start() },
        onClose = { close() },
    )
}

@Composable
private inline fun <T> ControllerLifecycleEffect(
    controller: T,
    crossinline onStart: suspend T.() -> Unit,
    crossinline onClose: T.() -> Unit,
) {
    LaunchedEffect(controller) { controller.onStart() }
    DisposableEffect(controller) {
        onDispose { controller.onClose() }
    }
}

private data class DestinationAgentSelectionParams(
    val selection: DesktopDestinationSelection,
    val targetDestination: DesktopDestination,
    val onSelectAgent: (String) -> Unit,
)

@Composable
private fun DestinationAgentSelectionEffect(params: DestinationAgentSelectionParams) {
    val selection = params.selection
    LaunchedEffect(selection.selectedDestination, selection.selectedConversationAgentId) {
        if (selection.selectedDestination == params.targetDestination) {
            selection.selectedConversationAgentId?.value?.let(params.onSelectAgent)
        }
    }
}

private data class DestinationRefreshParams(
    val selectedDestination: DesktopDestination,
    val targetDestination: DesktopDestination,
    val onRefresh: suspend () -> Unit,
)

@Composable
private fun DestinationRefreshEffect(params: DestinationRefreshParams) {
    LaunchedEffect(params.selectedDestination) {
        if (params.selectedDestination == params.targetDestination) {
            params.onRefresh()
        }
    }
}

/**
 * Cmd/Ctrl-K opens the command palette (Penpot shows the ⌘K hint). A global
 * AWT key dispatcher fires regardless of which Compose field has focus.
 */
@Composable
internal fun CommandPaletteKeyDispatcherEffect(onOpenPalette: () -> Unit) {
    DisposableEffect(onOpenPalette) {
        val focusManager = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val dispatcher = commandPaletteKeyDispatcher(onOpenPalette)
        focusManager.addKeyEventDispatcher(dispatcher)
        onDispose { focusManager.removeKeyEventDispatcher(dispatcher) }
    }
}

private fun commandPaletteKeyDispatcher(onOpenPalette: () -> Unit): java.awt.KeyEventDispatcher =
    java.awt.KeyEventDispatcher { event ->
        if (isCommandPaletteKey(event)) {
            onOpenPalette()
            true
        } else {
            false
        }
    }

private fun isCommandPaletteKey(event: java.awt.event.KeyEvent): Boolean =
    event.id == java.awt.event.KeyEvent.KEY_PRESSED &&
        event.keyCode == java.awt.event.KeyEvent.VK_K &&
        (event.isControlDown || event.isMetaDown)

/** Typed agent id for desktop library/panel operations. */
@JvmInline
internal value class DesktopAgentId(val value: String)

/** Typed skill name for install/uninstall panel actions. */
@JvmInline
internal value class DesktopSkillName(val value: String)

/** A cron creation request from the schedules surface. */
internal data class CronDraft(
    val agentId: DesktopAgentId,
    val name: String,
    val prompt: String,
    val cron: String,
    val recurring: Boolean,
    val timezone: String,
)

/** Typed cron task id for panel delete actions. */
@JvmInline
internal value class DesktopCronTaskId(val value: String)

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

    fun delete(id: DesktopCronTaskId) {
        val api = cronApi ?: return
        scope.launch {
            runCatching { api.deleteCron(id.value) }
            refresh()
        }
    }

    fun create(draft: CronDraft) {
        val api = cronApi ?: return
        scope.launch {
            runCatching { api.createCronFromDraft(draft) }
            refresh()
        }
    }
}

private suspend fun CronApi.createCronFromDraft(draft: CronDraft) {
    createCron(
        agentId = draft.agentId.value,
        name = draft.name,
        description = draft.name,
        prompt = draft.prompt,
        cron = draft.cron,
        timezone = draft.timezone,
        recurring = draft.recurring,
    )
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
    suspend fun reload(agentId: DesktopAgentId?) {
        val api = skillApi ?: return
        loading = true
        error = null
        runCatching { loadSkillsSnapshot(api, agentId) }
            .onSuccess { (registry, installed) ->
                all = registry
                installedNames = installed
            }
            .onFailure { error = it.message ?: "Could not load skills." }
        loading = false
    }

    fun install(agentId: DesktopAgentId?, name: DesktopSkillName) {
        mutateSkill(
            SkillMutationParams(agentId, name, "Could not install skill.") { api, agent, skill ->
                api.installSkill(agent.value, skill.value)
            },
        )
    }

    fun uninstall(agentId: DesktopAgentId?, name: DesktopSkillName) {
        mutateSkill(
            SkillMutationParams(agentId, name, "Could not remove skill.") { api, agent, skill ->
                api.uninstallSkill(agent.value, skill.value)
            },
        )
    }

    private fun mutateSkill(params: SkillMutationParams) {
        val api = skillApi ?: return
        val agent = params.agentId ?: return
        scope.launch {
            runCatching { params.action(api, agent, params.name) }
                .onFailure { error = it.message ?: params.failureMessage }
            reload(agent)
        }
    }
}

private data class SkillMutationParams(
    val agentId: DesktopAgentId?,
    val name: DesktopSkillName,
    val failureMessage: String,
    val action: suspend (SkillsApi, DesktopAgentId, DesktopSkillName) -> Unit,
)

private suspend fun loadSkillsSnapshot(
    api: SkillsApi,
    agentId: DesktopAgentId?,
): Pair<List<Skill>, Set<String>> {
    val registry = api.listSkills()
    val installed = agentId?.let { id ->
        api.listAgentSkills(id.value).map { skill -> skill.name }.toSet()
    } ?: emptySet()
    return registry to installed
}

/** The focused agent's server slash commands for the composer palette. */
internal suspend fun loadAgentSlashCommands(
    api: SlashCommandsApi?,
    agentId: DesktopAgentId?,
): List<AgentSlashCommand> = if (api != null && agentId != null) {
    runCatching { api.listAgentSlashCommands(agentId.value) }.getOrDefault(emptyList())
} else {
    emptyList()
}
