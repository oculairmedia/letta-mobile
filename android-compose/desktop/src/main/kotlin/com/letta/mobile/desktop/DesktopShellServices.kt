package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.FocusRequester
import com.letta.mobile.data.commands.AgentSlashCommand
import com.letta.mobile.data.desktopshell.ShellLayoutEvent
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.skills.SkillsCatalogController
import com.letta.mobile.data.notify.AttentionPrefsRepository
import com.letta.mobile.data.notify.StoredAttentionPrefs
import com.letta.mobile.data.repository.SubagentRepository
import com.letta.mobile.data.repository.iroh.IrohAdminRpcAgentDirectory
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import com.letta.mobile.desktop.canvas.DesktopCanvasShell
import com.letta.mobile.desktop.canvas.rememberDesktopCanvasShell
import com.letta.mobile.desktop.chat.DesktopChatController
import com.letta.mobile.desktop.chat.DesktopChatSurfaceState
import com.letta.mobile.desktop.chat.DesktopImageAttachmentLoader
import com.letta.mobile.desktop.data.DesktopChatDockGeometryStore
import com.letta.mobile.data.session.graph.SharedSessionRepositoryGraph
import com.letta.mobile.desktop.data.DesktopShellLayoutStore
import com.letta.mobile.desktop.phone.LocalDesktopPhone
import com.letta.mobile.desktop.phone.reducedMotionOr
import com.letta.mobile.ui.chat.session.ChatDockGeometry
import com.letta.mobile.ui.chat.surface.rememberChatDockGeometry
import kotlinx.coroutines.CoroutineScope

/** The shell's sidebar layout: its controller, the reduced-motion preference, and the toggle's focus. */
internal data class DesktopShellLayout(
    val controller: DesktopShellLayoutController,
    val reducedMotion: Boolean,
    val sidebarToggleFocusRequester: FocusRequester,
)

/** The live Iroh transport and its admin-RPC agent directory; both null off Iroh. */
internal data class DesktopIrohLink(
    val transport: IrohChannelTransport?,
    val agentDirectory: IrohAdminRpcAgentDirectory?,
)

/** The desktop shell's long-lived services: config, session graph, transports and controllers. */
internal data class DesktopShellCore(
    val bootstrap: DesktopConfigBootstrap,
    /** Follows transport publishes too, which rebuild the graph without going through applyConfig. */
    val sessionGraph: State<SharedSessionRepositoryGraph>,
    val layout: DesktopShellLayout,
    val chatScope: CoroutineScope,
    val railPrefs: DesktopRailPrefs,
    val canvasShell: DesktopCanvasShell,
    val nucleusController: DesktopNucleusController,
    val iroh: DesktopIrohLink,
    val chatController: DesktopChatController,
    val localConfig: DesktopLocalConfigState,
    /** letta-mobile-bzvro.27: per-agent notification mutes, kept in the settings store. */
    val attentionPrefs: AttentionPrefsRepository,
)

/** The per-backend panels and libraries behind the shell's destinations. */
internal data class DesktopShellPanels(
    val httpApis: DesktopHttpApis,
    val cronPanel: DesktopCronPanelState,
    /** letta-mobile-zxk2n.33: the shared skills catalog for the Skills page. */
    val skills: SkillsCatalogController,
    /** The focused agent's server slash commands, for the composer palette. */
    val agentSlashCommands: MutableState<List<AgentSlashCommand>>,
    val subagents: DesktopSubagentRegistry,
    /** letta-mobile-bglj6.1: the docked chat's saved placement, read once per app session. */
    val chatDockGeometry: MutableState<ChatDockGeometry?>,
    val libraries: DesktopLibraryControllers,
)

/**
 * Images into the composer from the window: drag-and-drop (and whether a drag is over it). The
 * shared composer's Attach button opens its own picker.
 */
internal data class DesktopImageIntake(
    val isDragActive: State<Boolean>,
)

@Composable
internal fun rememberDesktopShellCore(): DesktopShellCore {
    val bootstrap = rememberDesktopConfigBootstrap()
    // Transport publish rebuilds the graph without going through applyConfig;
    // observe currentGraph so libraries / subagent registry track the live id.
    val sessionGraph = bootstrap.dataBindings.sessionGraphProvider.currentGraph.collectAsState()
    val layout = rememberDesktopShellLayout(bootstrap.activeConfig.id)
    val chatScope = rememberCoroutineScope()
    val railPrefs = rememberDesktopRailPrefs(bootstrap.secureSettingsStore)
    val attentionPrefs = remember(bootstrap.secureSettingsStore) { StoredAttentionPrefs(bootstrap.secureSettingsStore) }
    val canvasShell = rememberDesktopCanvasShell(chatScope)
    val nucleusController = rememberDesktopNucleusController(chatScope)
    val iroh = rememberDesktopIrohLink(bootstrap, chatScope)
    val chatController = rememberShellChatController(bootstrap, chatScope, iroh)
    val localConfig = rememberDesktopLocalConfigState(
        scope = chatScope,
        secureSettingsStore = bootstrap.secureSettingsStore,
        isLocalMode = bootstrap.activeConfig.mode == LettaConfig.Mode.LOCAL,
        onRestartRequested = chatController::retryConnection,
    )
    DesktopLocalRuntimeLifecycleEffect(
        chatController = chatController,
        isLocalMode = bootstrap.activeConfig.mode == LettaConfig.Mode.LOCAL,
    )
    return DesktopShellCore(
        bootstrap = bootstrap,
        sessionGraph = sessionGraph,
        layout = layout,
        chatScope = chatScope,
        railPrefs = railPrefs,
        canvasShell = canvasShell,
        nucleusController = nucleusController,
        iroh = iroh,
        chatController = chatController,
        localConfig = localConfig,
        attentionPrefs = attentionPrefs,
    )
}

/**
 * Collapsible capability/history sidebar (Memory/Schedules/Channels/Skills/New chat/conversation
 * history) - letta-mobile-o5m90. Distinct from the rail's expanded mode: that toggles the far-left
 * agent rail between icon and expanded modes, this hides the middle sidebar entirely. Fenced by
 * backend config id so switching backends never restores another backend's layout
 * (rememberSaveable does not survive a desktop process restart, so this goes through a real
 * persisted store).
 */
@Composable
private fun rememberDesktopShellLayout(backendConfigId: String): DesktopShellLayout {
    val store = remember { DesktopShellLayoutStore() }
    val controller = rememberDesktopShellLayoutController(backendConfigId = backendConfigId, store = store)
    // The phone preview brings its own reduced-motion preset; the desktop follows the OS.
    val reducedMotion = LocalDesktopPhone.current.reducedMotionOr(remember { desktopPrefersReducedMotion() })
    SidebarToggleKeyDispatcherEffect(
        onToggle = { controller.dispatch(ShellLayoutEvent.ToggleSidebar) },
    )
    val focusRequester = remember { FocusRequester() }
    // Move focus onto the surviving toggle button whenever the sidebar
    // leaves composition, so a keyboard user never loses focus into a
    // removed subtree (AC #7 focus restoration).
    val sidebarVisible = controller.state.isSidebarVisible
    LaunchedEffect(sidebarVisible) {
        if (!sidebarVisible) {
            runCatching { focusRequester.requestFocus() }
        }
    }
    return DesktopShellLayout(controller, reducedMotion, focusRequester)
}

@Composable
private fun rememberDesktopIrohLink(bootstrap: DesktopConfigBootstrap, chatScope: CoroutineScope): DesktopIrohLink {
    val transport = rememberIrohTransport(bootstrap.activeConfig, chatScope)
    val agentDirectory = remember(transport) {
        transport?.let { IrohAdminRpcAgentDirectory(it) }
    }
    SideEffect {
        bootstrap.irohAgentDirectorySlot.value = agentDirectory
        // letta-mobile-bzvro.37: MemFS, secrets and workspace files go through the host's relay.
        com.letta.mobile.desktop.workspace.DesktopIrohWorkspaceRelay.transport = transport
    }
    rememberAndPublishGraphChannelTransport(
        irohTransport = transport,
        publish = bootstrap.publishChannelTransport,
    )
    return DesktopIrohLink(transport, agentDirectory)
}

@Composable
private fun rememberShellChatController(
    bootstrap: DesktopConfigBootstrap,
    chatScope: CoroutineScope,
    iroh: DesktopIrohLink,
): DesktopChatController {
    val runtime = DesktopChatRuntime(
        bootstrapState = bootstrap.bootstrapState,
        chatScope = chatScope,
        dataBindings = bootstrap.dataBindings,
    )
    return rememberDesktopChatController(
        DesktopChatControllerBindings(
            runtime = runtime,
            irohTransport = iroh.transport,
            irohAgentDirectory = iroh.agentDirectory,
            secureSettingsStore = bootstrap.secureSettingsStore,
        ),
    )
}

@Composable
internal fun rememberDesktopShellPanels(core: DesktopShellCore, chatState: DesktopChatSurfaceState): DesktopShellPanels {
    val chatScope = core.chatScope
    val sessionGraph = core.sessionGraph.value
    val httpApis = rememberDesktopHttpApis(core.iroh.transport != null, core.iroh.agentDirectory)
    val cronPanel = remember(httpApis.cronApi) { DesktopCronPanelState(httpApis.cronApi, chatScope) }
    val skills = remember(httpApis.skillApi) { SkillsCatalogController(httpApis.skillApi, chatScope) }
    DisposableEffect(skills) { onDispose { skills.close() } }
    val agentSlashCommands = remember(httpApis.slashCommandApi) {
        mutableStateOf<List<AgentSlashCommand>>(emptyList())
    }
    val subagents = rememberSubagentRegistry(
        request = SubagentRegistryRequest(
            parentScope = subagentParentScope(chatState.selectedConversation?.agentId, chatState.selectedConversationId),
            irohTransport = core.iroh.transport,
            graphSubagentRepository = sessionGraph.subagentRepository as? SubagentRepository,
        ),
    )
    return DesktopShellPanels(
        httpApis = httpApis,
        cronPanel = cronPanel,
        skills = skills,
        agentSlashCommands = agentSlashCommands,
        subagents = subagents,
        chatDockGeometry = rememberChatDockGeometry(remember { DesktopChatDockGeometryStore().asLayoutPrefStorage() }),
        libraries = rememberDesktopLibraryControllers(
            sessionGraphId = sessionGraph.id,
            sessionGraphProvider = core.bootstrap.dataBindings.sessionGraphProvider,
            chatScope = chatScope,
            settingsStore = core.bootstrap.secureSettingsStore,
        ),
    )
}

/** The window's image source for the composer: drag-and-drop while the chat shows. */
@Composable
internal fun rememberDesktopImageIntake(core: DesktopShellCore, chatShowing: Boolean): DesktopImageIntake {
    val chatController = core.chatController
    val loader = remember { DesktopImageAttachmentLoader() }
    val isDragActive = DesktopImageIngressEffect(
        DesktopImageIngressConfig(
            enabled = chatShowing,
            scope = core.chatScope,
            loader = loader,
            onImage = chatController::attachImage,
            onError = chatController::showComposerError,
        ),
    )
    return DesktopImageIntake(isDragActive = isDragActive)
}
