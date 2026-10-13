package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.letta.mobile.data.channel.ChannelsPageController
import com.letta.mobile.data.commands.AgentSlashCommand
import com.letta.mobile.data.commands.SlashCommandsApi
import com.letta.mobile.desktop.data.defaultDesktopStateDirectory
import com.letta.mobile.desktop.security.DesktopSecretVaults
import com.letta.mobile.desktop.security.EncryptingSecureSettingsStore
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.iroh.IrohAdminRpcAgentDirectory
import com.letta.mobile.data.schedules.CronApi
import com.letta.mobile.data.schedules.CronTask
import com.letta.mobile.data.schedules.ScheduleLibraryController
import com.letta.mobile.desktop.chat.DesktopChatController
import com.letta.mobile.desktop.home.DesktopHome
import com.letta.mobile.data.home.HomePageController
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.desktop.data.DesktopDataBindings
import com.letta.mobile.desktop.data.DesktopFileSecureSettingsStore
import com.letta.mobile.desktop.data.DesktopLettaConfigStore
import com.letta.mobile.desktop.data.DesktopSessionGraphProvider
import com.letta.mobile.desktop.data.createDefaultDesktopDataBindings
import com.letta.mobile.data.memory.graph.MemoryPageController
import com.letta.mobile.data.memory.memfs.MemfsPageController
import com.letta.mobile.desktop.workspace.DesktopWorkspaceSources
import com.letta.mobile.desktop.workspace.memfs
import com.letta.mobile.desktop.data.DesktopRepositoryUnavailableException
import com.letta.mobile.desktop.runtime.DesktopLocalBackendDirectorySettings
import com.letta.mobile.desktop.runtime.DesktopRuntimeLaunchSettings
import com.letta.mobile.desktop.tools.DesktopToolLibraryController
import com.letta.mobile.data.transport.api.IChannelTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Config store, session graph bindings, and bootstrap state for the desktop shell.
 * [irohAgentDirectorySlot] / [channelTransportSlot] are updated from
 * [LettaDesktopApp] once the live Iroh/WS transport is available; a transport
 * change rebuilds the session graph so Cron/SelfTodo/Subagent and Iroh admin
 * reads bind to the real channel.
 */
internal data class DesktopConfigBootstrap(
    val secureSettingsStore: com.letta.mobile.data.storage.SecureSettingsStore,
    val dataBindings: DesktopDataBindings,
    val activeConfig: LettaConfig,
    val bootstrapState: DesktopBootstrapState,
    val applyConfig: (LettaConfig) -> Unit,
    val irohAgentDirectorySlot: MutableState<IrohAdminRpcAgentDirectory?>,
    val channelTransportSlot: MutableState<IChannelTransport?>,
    val publishChannelTransport: (IChannelTransport?) -> Unit,
)

@Composable
internal fun rememberDesktopConfigBootstrap(): DesktopConfigBootstrap {
    // d6e8g.4: sensitive settings (access token) are vault-encrypted at rest —
    // DPAPI on Windows, keyfile-AES-GCM elsewhere. Legacy plaintext tokens
    // migrate transparently on first read.
    val secureSettingsStore = remember {
        EncryptingSecureSettingsStore(
            delegate = DesktopFileSecureSettingsStore(),
            vault = DesktopSecretVaults.forCurrentOs(defaultDesktopStateDirectory()),
        ).also { store ->
            // Must run before the first local-runtime spawn so a saved
            // directory override is honored from the app's very first
            // connection, not just after a later Settings visit.
            DesktopLocalBackendDirectorySettings.applyStoredOverride(store)
            // F05: stable instance id and the default working directory for the child.
            DesktopRuntimeLaunchSettings.applyStored(store)
        }
    }
    val configStore = remember(secureSettingsStore) { DesktopLettaConfigStore(secureSettingsStore) }
    var activeConfig by remember { mutableStateOf(configStore.load()) }
    val irohAgentDirectorySlot = remember { mutableStateOf<IrohAdminRpcAgentDirectory?>(null) }
    val channelTransportSlot = remember { mutableStateOf<IChannelTransport?>(null) }
    val dataBindings = remember(configStore) {
        createDefaultDesktopDataBindings(
            secureSettingsStore = secureSettingsStore,
            configProvider = { activeConfig },
            irohAgentDirectoryProvider = { irohAgentDirectorySlot.value },
            channelTransportProvider = { channelTransportSlot.value },
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
    val publishChannelTransport: (IChannelTransport?) -> Unit = remember(dataBindings, activeConfig) {
        { transport ->
            if (channelTransportSlot.value !== transport) {
                channelTransportSlot.value = transport
                dataBindings.sessionGraphProvider.rebuild()
                bootstrapState = defaultDesktopBootstrapState(dataBindings, activeConfig)
            }
        }
    }
    return DesktopConfigBootstrap(
        secureSettingsStore = secureSettingsStore,
        dataBindings = dataBindings,
        activeConfig = activeConfig,
        bootstrapState = bootstrapState,
        applyConfig = applyConfig,
        irohAgentDirectorySlot = irohAgentDirectorySlot,
        channelTransportSlot = channelTransportSlot,
        publishChannelTransport = publishChannelTransport,
    )
}

/** The per-agent library controllers behind the sidebar destinations. */
internal class DesktopLibraryControllers(
    val memory: MemoryPageController,
    val schedules: ScheduleLibraryController,
    val channels: ChannelsPageController,
    val tools: DesktopToolLibraryController,
    val home: HomePageController,
    /** letta-mobile-bzvro.24: the MemFS browser beside the memory graph. */
    val memfs: MemfsPageController,
)

@Composable
internal fun rememberDesktopLibraryControllers(
    sessionGraphId: Long,
    sessionGraphProvider: DesktopSessionGraphProvider,
    chatScope: CoroutineScope,
    settingsStore: SecureSettingsStore,
): DesktopLibraryControllers {
    val memory = remember(sessionGraphId, chatScope) {
        MemoryPageController.forSession(
            sessionGraphProvider = sessionGraphProvider,
            scope = chatScope,
            errorMessageMapper = ::desktopMemoryErrorMessage,
        )
    }
    val schedules = remember(sessionGraphId, chatScope) {
        desktopScheduleLibraryController(sessionGraphProvider = sessionGraphProvider, scope = chatScope)
    }
    val channels = remember(sessionGraphId, chatScope) {
        ChannelsPageController.forSession(sessionGraphProvider = sessionGraphProvider, scope = chatScope)
    }
    val tools = remember(sessionGraphId, chatScope) {
        DesktopToolLibraryController(sessionGraphProvider = sessionGraphProvider, scope = chatScope)
    }
    // The Home source follows the provider's current graph itself, so a backend switch reloads it in place.
    val home = remember(sessionGraphProvider, settingsStore, chatScope) {
        DesktopHome.controller(sessionGraphProvider = sessionGraphProvider, settingsStore = settingsStore, scope = chatScope)
    }
    // The direct App Server session it reads through is process-wide, not per session graph.
    val memfs = remember(chatScope) { MemfsPageController(DesktopWorkspaceSources().memfs(), chatScope) }
    return DesktopLibraryControllers(memory, schedules, channels, tools, home, memfs)
}

/**
 * The shared schedule library over the session graph. letta-mobile-ckmub.4: only an Iroh host relays
 * schedules today, so the unavailable repository bound for other backends means "not served here".
 */
private fun desktopScheduleLibraryController(
    sessionGraphProvider: DesktopSessionGraphProvider,
    scope: CoroutineScope,
): ScheduleLibraryController {
    val graph = sessionGraphProvider.current
    return ScheduleLibraryController(
        agentRepository = graph.agentRepository,
        scheduleRepository = graph.scheduleRepository,
        scope = scope,
        errorMessageMapper = { throwable, fallback ->
            when (throwable) {
                is DesktopRepositoryUnavailableException -> "Desktop schedule repositories are not available for this backend yet."
                else -> throwable.message ?: throwable::class.simpleName ?: fallback
            }
        },
        scheduleAdminUnavailableMatcher = { throwable -> throwable is DesktopRepositoryUnavailableException },
    )
}

private fun desktopMemoryErrorMessage(throwable: Throwable): String =
    when (throwable) {
        is DesktopRepositoryUnavailableException -> "Desktop memory repositories are not available for this backend yet."
        else -> throwable.message ?: throwable::class.simpleName ?: "Memory data could not be loaded."
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
    ControllerLifecycleEffect(
        controller = libraries.home,
        onStart = { start() },
        onClose = { close() },
    )
    ControllerLifecycleEffect(
        controller = libraries.memfs,
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

internal fun commandPaletteKeyDispatcher(onOpenPalette: () -> Unit): java.awt.KeyEventDispatcher =
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

    fun pause(id: DesktopCronTaskId) {
        val api = cronApi ?: return
        scope.launch {
            runCatching { api.pauseCron(id.value) }
            refresh()
        }
    }

    fun resume(id: DesktopCronTaskId) {
        val api = cronApi ?: return
        scope.launch {
            runCatching { api.resumeCron(id.value) }
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

/** The focused agent's server slash commands for the composer palette. */
internal suspend fun loadAgentSlashCommands(
    api: SlashCommandsApi?,
    agentId: DesktopAgentId?,
): List<AgentSlashCommand> = if (api != null && agentId != null) {
    runCatching { api.listAgentSlashCommands(agentId.value) }.getOrDefault(emptyList())
} else {
    emptyList()
}
