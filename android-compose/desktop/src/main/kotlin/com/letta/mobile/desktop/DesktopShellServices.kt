package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.FocusRequester
import com.letta.mobile.data.attachment.ImageIngressPolicy
import com.letta.mobile.data.commands.AgentSlashCommand
import com.letta.mobile.data.desktopshell.ShellLayoutEvent
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.SubagentRepository
import com.letta.mobile.data.repository.iroh.IrohAdminRpcAgentDirectory
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import com.letta.mobile.desktop.canvas.DesktopCanvasShell
import com.letta.mobile.desktop.canvas.rememberDesktopCanvasShell
import com.letta.mobile.desktop.chat.DesktopChatController
import com.letta.mobile.desktop.chat.DesktopChatSurfaceState
import com.letta.mobile.desktop.chat.DesktopImageAttachmentLoader
import com.letta.mobile.desktop.chat.rememberDesktopChatDockGeometry
import com.letta.mobile.desktop.data.DesktopSessionGraph
import com.letta.mobile.desktop.data.DesktopShellLayoutStore
import com.letta.mobile.desktop.phone.LocalDesktopPhone
import com.letta.mobile.desktop.phone.reducedMotionOr
import com.letta.mobile.ui.chat.session.ChatDockGeometry
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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
    val sessionGraph: State<DesktopSessionGraph>,
    val layout: DesktopShellLayout,
    val chatScope: CoroutineScope,
    val railPrefs: DesktopRailPrefs,
    val canvasShell: DesktopCanvasShell,
    val nucleusController: DesktopNucleusController,
    val iroh: DesktopIrohLink,
    val chatController: DesktopChatController,
    val localConfig: DesktopLocalConfigState,
)

/** The per-backend panels and libraries behind the shell's destinations. */
internal data class DesktopShellPanels(
    val httpApis: DesktopHttpApis,
    val cronPanel: DesktopCronPanelState,
    val skillsPanel: DesktopSkillsPanelState,
    /** The focused agent's server slash commands, for the composer palette. */
    val agentSlashCommands: MutableState<List<AgentSlashCommand>>,
    val subagents: DesktopSubagentRegistry,
    /** letta-mobile-bglj6.1: the docked chat's saved placement, read once per app session. */
    val chatDockGeometry: MutableState<ChatDockGeometry?>,
    val libraries: DesktopLibraryControllers,
)

/** Images into the composer: the file picker, and whether a drag is over the window. */
internal data class DesktopImageIntake(
    val launchPicker: () -> Unit,
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
    val httpApis = rememberDesktopHttpApis(core.bootstrap.activeConfig, core.iroh.transport != null, core.iroh.agentDirectory)
    val cronPanel = remember(httpApis.cronApi) { DesktopCronPanelState(httpApis.cronApi, chatScope) }
    val skillsPanel = remember(httpApis.skillApi) { DesktopSkillsPanelState(httpApis.skillApi, chatScope) }
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
        skillsPanel = skillsPanel,
        agentSlashCommands = agentSlashCommands,
        subagents = subagents,
        chatDockGeometry = rememberDesktopChatDockGeometry(),
        libraries = rememberDesktopLibraryControllers(
            sessionGraphId = sessionGraph.id,
            sessionGraphProvider = core.bootstrap.dataBindings.sessionGraphProvider,
            chatScope = chatScope,
            settingsStore = core.bootstrap.secureSettingsStore,
        ),
    )
}

/** The composer's image sources: the Attach images picker, and drag-and-drop while the chat shows. */
@Composable
internal fun rememberDesktopImageIntake(core: DesktopShellCore, navigator: DesktopShellNavigator): DesktopImageIntake {
    val chatController = core.chatController
    val loader = remember { DesktopImageAttachmentLoader() }
    val pickerLauncher = rememberFilePickerLauncher(
        type = FileKitType.Image,
        mode = FileKitMode.Multiple(maxItems = ImageIngressPolicy.MAX_FILES),
        dialogSettings = FileKitDialogSettings(title = "Attach images"),
    ) { files ->
        files.orEmpty().forEach { file -> launchImageAttach(file.file, loader, core) }
    }
    val isDragActive = DesktopImageIngressEffect(
        DesktopImageIngressConfig(
            enabled = navigator.selectedDestination == DesktopDestination.Conversations,
            scope = core.chatScope,
            loader = loader,
            onImage = chatController::attachImage,
            onError = chatController::showComposerError,
        ),
    )
    return DesktopImageIntake(
        launchPicker = { pickerLauncher.launch() },
        isDragActive = isDragActive,
    )
}

private fun launchImageAttach(file: File, loader: DesktopImageAttachmentLoader, core: DesktopShellCore) {
    val chatController = core.chatController
    core.chatScope.launch {
        runCatching {
            val path = file.toPath()
            loader.load(path)
        }.onSuccess(chatController::attachImage)
            .onFailure { chatController.showComposerError(attachFailureMessage(it)) }
    }
}

private fun attachFailureMessage(error: Throwable): String {
    return error.message ?: error::class.simpleName ?: "Could not attach image"
}
