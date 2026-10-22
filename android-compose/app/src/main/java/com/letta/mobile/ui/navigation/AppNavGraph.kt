package com.letta.mobile.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.letta.mobile.AppLaunchTarget
import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.chat.runtime.ChatScreenStatus
import com.letta.mobile.data.model.BackendConnectionStatus
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.toBackendLabel
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.di.pluginViewsOf
import com.letta.mobile.feature.chat.screen.shared.ChatCanvasSlot
import com.letta.mobile.feature.chat.screen.shared.LocalChatCanvasSlot
import com.letta.mobile.ui.screens.agenteditor.androidAgentEditorHost
import com.letta.mobile.ui.screens.config.ConfigListScreen
import com.letta.mobile.ui.screens.config.SettingsDestinations
import com.letta.mobile.ui.screens.config.androidSettingsPage
import com.letta.mobile.ui.screens.systemaccess.SystemAccessDashboardScreen
import com.letta.mobile.ui.shell.pages.ShellHomeConnection
import com.letta.mobile.ui.shell.pages.ShellHomeHost
import com.letta.mobile.ui.shell.pages.ShellHostPages
import com.letta.mobile.ui.shell.pages.ShellPageFocus
import com.letta.mobile.ui.shell.pages.ShellPageHost
import com.letta.mobile.ui.shell.pages.channels.ChannelsPageSlots
import com.letta.mobile.ui.shell.pages.home.HomeConnectionState
import com.letta.mobile.ui.shell.pages.home.HomePageOptions
import com.letta.mobile.ui.shell.pages.plugins.rememberPluginsEntry
import com.letta.mobile.util.Telemetry
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * letta-mobile-bglj6.1: the conversation's own board under the shared chat page. Its share and
 * back act on that page (attach to the draft, expand the chat), not on navigation.
 */
private val AppChatCanvasSlot = ChatCanvasSlot { target, actions, chromeTopInset ->
    // No board for a chat without a conversation yet; once the first send creates one, the
    // board is that conversation's own, in a ViewModel keyed by it (CanvasViewModel binds once).
    val canvasKey = com.letta.mobile.ui.chat.surface.chatCanvasKey(target.conversationId)
    if (canvasKey == null) {
        com.letta.mobile.ui.chat.surface.ChatCanvasPlaceholder()
    } else {
        androidx.compose.runtime.key(canvasKey) {
            com.letta.mobile.ui.screens.canvas.CanvasScreen(
                canvasId = "",
                conversationId = target.conversationId,
                agentId = target.agentId,
                // The canvas is the page: no title bar or back arrow, just its actions pill.
                onNavigateBack = null,
                onShareToChat = actions::shareToChat,
                viewModel = hiltViewModel(key = canvasKey),
                showTitle = false,
                // Under the chat's floating header, where one shows: the board runs behind it. In the
                // phone's canvas mode the header steps aside and the board's actions sit at its foot.
                chromeTopInset = chromeTopInset,
                // A chat card's "Show on canvas" frames its artifact on this board (bglj6.13).
                cameraRequest = actions.camera,
            )
        }
    }
}

private fun androidx.navigation.NavGraphBuilder.appCanvasGraph(navController: NavHostController) {
    // letta-mobile-swup: slide-up "card lifts" transition. Forward plays
    // slideInVertically (canvas rises from the bottom edge where the
    // prompt card lives); back plays slideOutVertically in reverse so the
    // canvas appears to collapse back into the card. Fades sit underneath
    // so the chat fades out gently under the rising canvas instead of
    // popping. ~280ms is the "Meridian" tween for medium surface changes
    // — long enough to read the lift, short enough not to feel like a
    // separate navigation event.
    composable<CanvasRoute>(
        enterTransition = {
            slideInVertically(
                animationSpec = tween(280),
                initialOffsetY = { it },
            ) + fadeIn(animationSpec = tween(140))
        },
        exitTransition = {
            fadeOut(animationSpec = tween(140))
        },
        popEnterTransition = {
            fadeIn(animationSpec = tween(140))
        },
        popExitTransition = {
            slideOutVertically(
                animationSpec = tween(280),
                targetOffsetY = { it },
            ) + fadeOut(animationSpec = tween(140))
        },
    ) { backStackEntry ->
        val route = backStackEntry.toRoute<CanvasRoute>()
        val coroutineScope = rememberCoroutineScope()
        val shareRecipient = route.shareRecipient
        com.letta.mobile.ui.screens.canvas.CanvasScreen(
            canvasId = route.canvasId,
            conversationId = route.conversationId,
            agentId = route.agentId,
            onNavigateBack = { navController.popBackStack() },
            onShareToChat = shareRecipient?.let { recipient -> { bytes: ByteArray, mimeType: String ->
                val result = com.letta.mobile.data.canvas.CanvasShare.packageForChat(
                    bytes,
                    com.letta.mobile.data.canvas.CanvasMimeType.fromValue(mimeType),
                )
                result.onSuccess { image ->
                    // The scope dies with this destination, so leaving before the image is in
                    // the staging queue would cancel the hand-off; navigate only once it landed.
                    coroutineScope.launch {
                        val target = com.letta.mobile.data.canvas.CanvasConversationTarget(recipient)
                        com.letta.mobile.data.canvas.CanvasShare.stageForConversation(target, image)
                        navController.popBackStack()
                    }
                }.onFailure {
                    navController.popBackStack()
                }
            } },
        )
    }
}

/** The settings the workspace's start page and backend layout are read from. */
@HiltViewModel
class NavViewModel @Inject constructor(private val settingsRepository: ISettingsRepository) : ViewModel() {
    val activeConfig: StateFlow<LettaConfig?> = settingsRepository.activeConfig

    /** letta-mobile-v7aln: read once, synchronously (every input is filled at construction). */
    internal fun startDestination(target: AppLaunchTarget?) = resolveStartDestination(
        hasConfig = settingsRepository.activeConfig.value != null,
        initialTarget = target,
        restoredChatSelection = settingsRepository.lastChatSelection.value,
        fallbackAgentId = settingsRepository.favoriteAgentId.value ?: settingsRepository.adminAgentId.value,
    )
}

/**
 * letta-mobile-ckmub.2 (hard cut): the app is the shared shell workspace ([AndroidShellWorkspace]); the
 * nav host above it only holds Android's own screens the workspace leaves for: the camera scanner and
 * the pairing invite, the system-access dashboard, the saved backends list, the full-screen board and
 * the canvas debug board.
 */
@Composable
fun AppNavGraph(
    navController: NavHostController = rememberNavController(),
    notificationTarget: AppLaunchTarget? = null,
    onNotificationTargetConsumed: () -> Unit = {},
) {
    val navViewModel: NavViewModel = hiltViewModel()
    val activeConfig by navViewModel.activeConfig.collectAsStateWithLifecycle()
    // Decided once: a new start page would replace the workspace (see resolveStartDestination).
    val start = remember { navViewModel.startDestination(notificationTarget) }
    // letta-mobile-bglj6.1: the shared chat page docks under the conversation's canvas; the
    // canvas screen lives here in :app, so it is handed to feature-chat as a slot.
    androidx.compose.runtime.CompositionLocalProvider(LocalChatCanvasSlot provides AppChatCanvasSlot) {
        NavHost(navController = navController, startDestination = ShellWorkspaceRoute) {
            composable<ShellWorkspaceRoute> {
                AndroidShellWorkspace(
                    start = start,
                    config = activeConfig,
                    exits = androidShellExits(navController),
                    launch = AndroidShellLaunch(notificationTarget, onNotificationTargetConsumed) {
                        navController.navigate(PairingScanRoute)
                    },
                )
            }
            pairingRoutes(navController)
            composable<SystemAccessRoute> {
                SystemAccessDashboardScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToPairingScan = { navController.navigate(PairingScanRoute) },
                    onNavigateToPairDevice = { navController.navigate(PairingInviteRoute) },
                )
            }
            composable<ConfigListRoute> { ConfigListScreen(onNavigateBack = { navController.popBackStack() }) }
            composable<CanvasDebugRoute> {
                com.letta.mobile.ui.canvas.CanvasWorkspace(
                    initialJson = com.letta.mobile.ui.canvas.CanvasSamples.buildCycleJson,
                    onNavigateBack = { navController.popBackStack() },
                )
            }
            appCanvasGraph(navController)
        }
    }
}

/** Where the workspace leaves for Android's own screens. */
private fun androidShellExits(navController: NavHostController) = AndroidShellExits(
    openCanvas = { agentId, conversationId, shareRecipient ->
        navController.navigate(CanvasRoute(conversationId = conversationId, agentId = agentId, shareRecipient = shareRecipient))
    },
    openCanvasById = { canvasId -> navController.navigate(CanvasRoute(canvasId = canvasId)) },
    settings = SettingsDestinations(
        onConfigList = { navController.navigate(ConfigListRoute) },
        onSystemAccess = { navController.navigate(SystemAccessRoute) },
        onCanvasDebug = { navController.navigate(CanvasDebugRoute) },
    ),
)

/**
 * letta-mobile-ckmub.2: what Android adds to the shared shell pages: its chat page, its Settings and
 * Plugins, the Home connection hero, and the agent editor's creator and Dreaming panel. Every page
 * itself is the shared one desktop and web bind.
 */
@Composable
internal fun androidPageHost(shell: WorkspaceShell): ShellPageHost {
    val workspace = shell.workspace
    val sources = shell.drawerModel.sources
    val agents by shell.drawerModel.agents.collectAsStateWithLifecycle()
    val focused = workspace.focusedAgentId
    val connection = rememberAndroidConnection(shell)
    val context = LocalContext.current
    val plugins = remember(context) { pluginViewsOf(context) }
    val settings = shell.exits.settings.copy(
        onProviders = { workspace.navigate(ShellDestination.Providers) },
        onPlugins = rememberPluginsEntry(plugins) { workspace.navigate(ShellDestination.Plugins) },
    )
    return ShellPageHost(
        navigator = shell.navigator,
        focus = ShellPageFocus(focused, agents.firstOrNull { it.id == focused }?.name),
        pages = ShellHostPages(
            chat = androidChatPage(shell),
            settings = androidSettingsPage(onBack = { workspace.navigate(ShellDestination.Home) }, destinations = settings),
            plugins = androidPluginsPage(plugins, onBack = { workspace.navigate(ShellDestination.Settings()) }),
            slots = ChannelsPageSlots.Material,
            fallback = androidWaitingPage(shell, connection),
        ),
        home = ShellHomeHost(
            // The workspace header's search searches Home while it shows.
            options = HomePageOptions(showSearch = false, touch = true),
            connection = connection.homeHero(onRetry = { sources.sessions.rebuild() }),
            onA2uiAction = ::dropUnroutableA2uiAction,
        ),
        agentEditor = androidAgentEditorHost(shell.drawerModel),
        modelControl = sources.modelControl,
        settings = sources.library.settings,
    )
}

/** The backend's state as the shared pages show it: none configured, failed, or there. */
@Composable
private fun rememberAndroidConnection(shell: WorkspaceShell): BackendConnectionStatus {
    val sessions = shell.drawerModel.sources.sessions
    val error by sessions.sessionError.collectAsStateWithLifecycle()
    val agents by shell.drawerModel.agents.collectAsStateWithLifecycle()
    return when {
        shell.config == null -> BackendConnectionStatus.Unconfigured
        error != null -> BackendConnectionStatus.Failed(error?.message ?: "Couldn't reach the backend")
        agents.isEmpty() -> BackendConnectionStatus.Connecting
        else -> BackendConnectionStatus.Connected(shell.config.toBackendLabel().orEmpty())
    }
}

/** Home's connection hero until the backend is there; null once it is. */
private fun BackendConnectionStatus.homeHero(onRetry: () -> Unit): ShellHomeConnection? {
    val status = when (this) {
        BackendConnectionStatus.Unconfigured -> ChatScreenStatus.ConfigNeeded(null)
        BackendConnectionStatus.Connecting -> ChatScreenStatus.Loading
        is BackendConnectionStatus.Failed -> ChatScreenStatus.BackendOffline(message)
        is BackendConnectionStatus.Connected -> return null
    }
    return ShellHomeConnection(HomeConnectionState(status, (this as? BackendConnectionStatus.Failed)?.message), onRetry, wordmark = "LETTA")
}

/** No transport carries a Home card's A2UI action on Android yet (as on desktop): it is logged and dropped. */
private fun dropUnroutableA2uiAction(action: A2uiAction) {
    Telemetry.event(
        "AndroidA2ui", "action.unroutable",
        "surfaceId" to action.surfaceId, "name" to action.name,
        level = Telemetry.Level.WARN,
    )
}
