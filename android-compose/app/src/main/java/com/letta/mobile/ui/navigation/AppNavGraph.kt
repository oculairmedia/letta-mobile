package com.letta.mobile.ui.navigation

import android.content.Context
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.letta.mobile.AppLaunchTarget
import com.letta.mobile.channel.ChatPushAlarmScheduler
import com.letta.mobile.data.model.toBackendLabel
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.feature.chat.route.AgentChatRoute
import com.letta.mobile.feature.chat.route.chatGraph
import com.letta.mobile.feature.editagent.EditAgentRoute
import com.letta.mobile.feature.editagent.editAgentGraph
import com.letta.mobile.ui.screens.config.BackendSwitcherSheet
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * letta-mobile-cygd: signal flag set on the previous backstack entry's
 * SavedStateHandle when CreateProjectRoute pops on success. Both
 * HomeRoute and ProjectsRoute (the two routes that host
 * ProjectHomeScreen) read this flag in a LaunchedEffect on resume and
 * trigger a refresh + clear so the new project shows up immediately.
 */
const val PROJECT_CREATED_REFRESH_KEY: String = "project_created_refresh"

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

val LocalAnimatedVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

@HiltViewModel
class NavViewModel @Inject constructor(
    private val settingsRepository: ISettingsRepository,
    @param:ApplicationContext private val appContext: Context,
) : ViewModel() {
    val hasConfig = settingsRepository.activeConfig.map { it != null }
    val activeConfig = settingsRepository.activeConfig
    val favoriteAgentId = settingsRepository.favoriteAgentId
    val adminAgentId = settingsRepository.adminAgentId
    val lastChatSelection = settingsRepository.lastChatSelection

    fun clearAllData() {
        ChatPushAlarmScheduler.cancel(appContext)
        viewModelScope.launch { settingsRepository.clearAllData() }
    }

    fun setActiveConfig(configId: String) {
        viewModelScope.launch { settingsRepository.setActiveConfigId(configId) }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AppNavGraph(
    navController: NavHostController = rememberNavController(),
    notificationTarget: AppLaunchTarget? = null,
    onNotificationTargetConsumed: () -> Unit = {},
) {
    val navViewModel: NavViewModel = hiltViewModel()
    val hasConfig by navViewModel.hasConfig.collectAsStateWithLifecycle(initialValue = true)
    val activeConfig by navViewModel.activeConfig.collectAsStateWithLifecycle(initialValue = null)
    val favoriteAgentId by navViewModel.favoriteAgentId.collectAsStateWithLifecycle(initialValue = null)
    val adminAgentId by navViewModel.adminAgentId.collectAsStateWithLifecycle(initialValue = null)
    val lastChatSelection by navViewModel.lastChatSelection.collectAsStateWithLifecycle(initialValue = null)
    val activeBackendLabel = activeConfig.toBackendLabel()

    // letta-mobile-cdlk: backend-switcher bottom sheet. State is lifted to
    // the NavHost host so a single sheet instance overlays whichever screen
    // owns the pill (Home, Conversations, ProjectHome, TwoPaneConversations).
    // rememberSaveable so the sheet survives configuration changes; the
    // ModalBottomSheet manages its own enter/exit animation internally.
    var showBackendSwitcher by rememberSaveable { mutableStateOf(false) }
    val openBackendSwitcher: () -> Unit = remember { { showBackendSwitcher = true } }

    val initialNotificationTarget = remember { notificationTarget }
    val restoredChatSelection = lastChatSelection
    val fallbackAgentId = favoriteAgentId ?: adminAgentId
    val startDestination: Any = when {
        hasConfig && initialNotificationTarget != null -> initialNotificationTarget.toRoute()
        hasConfig && restoredChatSelection != null -> restoredChatSelection.let { selection ->
            AgentChatRoute(
                agentId = selection.agentId,
                agentName = selection.agentName,
                conversationId = selection.conversationId,
            )
        }
        hasConfig && fallbackAgentId != null -> AgentChatRoute(agentId = fallbackAgentId)
        hasConfig -> HomeRoute
        else -> ConfigRoute()
    }

    LaunchedEffect(notificationTarget) {
        val target = notificationTarget ?: return@LaunchedEffect
        if (target != initialNotificationTarget) {
            navController.navigate(target.toRoute()) {
                launchSingleTop = true
            }
        }
        onNotificationTargetConsumed()
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
    ) {
        projectsGraph(
            navController = navController,
            activeBackendLabel = activeBackendLabel,
            openBackendSwitcher = openBackendSwitcher,
        )

        adminGraph(
            navController = navController,
            activeBackendLabel = activeBackendLabel,
            openBackendSwitcher = openBackendSwitcher,
            clearAllData = { navViewModel.clearAllData() },
        )

        configGraph(
            navController = navController,
        )

        conversationsGraph(
            navController = navController,
            activeBackendLabel = activeBackendLabel,
            openBackendSwitcher = openBackendSwitcher,
        )

        editAgentGraph(
            onNavigateBack = { navController.popBackStack() }
        )

        chatGraph(
            enterTransition = { fadeIn(animationSpec = tween(150)) },
            exitTransition = { fadeOut(animationSpec = tween(150)) },
            popEnterTransition = { fadeIn(animationSpec = tween(150)) },
            popExitTransition = { fadeOut(animationSpec = tween(150)) },
            // letta-mobile: when AgentChatRoute is the cold-start landing
            // (lastChatSelection or fallbackAgentId picked it as the
            // startDestination), the back stack is empty. Fall back to the
            // conversations list so back-from-default-chat takes the user
            // somewhere useful instead.
            onNavigateBack = {
                if (!navController.popBackStack()) {
                    navController.navigate(ConversationsRoute) {
                        popUpTo(0) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            },
            onNavigateToSettings = { agentId ->
                navController.navigate(EditAgentRoute(agentId))
            },
            onNavigateToArchival = { agentId ->
                navController.navigate(ArchivalRoute(agentId))
            },
            onNavigateToTools = {
                navController.navigate(AllToolsRoute)
            },
            onNavigateToMemory = { agentId ->
                navController.navigate(MemoryRoute(agentId))
            },
            onNavigateToAdmin = {
                navController.navigate(AdminRoute)
            },
            onNavigateToSchedules = {
                navController.navigate(SchedulesRoute)
            },
            onNavigateToConversationList = {
                navController.navigate(ConversationsRoute)
            },
            onSwitchConversation = { route ->
                navController.navigate(route) {
                    popUpTo<AgentChatRoute> { inclusive = true }
                }
            },
        )
    }

    // letta-mobile-cdlk: render the backend-switcher sheet at the top level
    // so it overlays whichever screen owns the pill. The Hilt-scoped
    // ConfigListViewModel that BackendSwitcherSheet pulls observes the
    // settings flow, so the sheet contents stay fresh across reopens.
    if (showBackendSwitcher) {
        BackendSwitcherSheet(
            onDismiss = { showBackendSwitcher = false },
            onNavigateToAddNewServer = {
                navController.navigate(ConfigRoute(createNew = true))
            },
            onNavigateToEditServer = {
                navController.navigate(ConfigRoute())
            },
        )
    }
}
