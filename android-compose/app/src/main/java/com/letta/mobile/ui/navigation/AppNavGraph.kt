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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.letta.mobile.AppLaunchTarget
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.feature.chat.screen.shared.ChatCanvasSlot
import com.letta.mobile.feature.chat.screen.shared.LocalChatCanvasSlot
import com.letta.mobile.ui.screens.config.ConfigListScreen
import com.letta.mobile.ui.screens.config.SettingsDestinations
import com.letta.mobile.ui.screens.systemaccess.SystemAccessDashboardScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

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

