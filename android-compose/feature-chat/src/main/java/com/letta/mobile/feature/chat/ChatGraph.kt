package com.letta.mobile.feature.chat

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute

fun NavGraphBuilder.chatGraph(
    enterTransition: (AnimatedContentTransitionScope<*>.() -> EnterTransition)? = null,
    exitTransition: (AnimatedContentTransitionScope<*>.() -> ExitTransition)? = null,
    popEnterTransition: (AnimatedContentTransitionScope<*>.() -> EnterTransition)? = null,
    popExitTransition: (AnimatedContentTransitionScope<*>.() -> ExitTransition)? = null,
    onNavigateBack: () -> Unit,
    onNavigateToSettings: (String) -> Unit,
    onNavigateToArchival: (String) -> Unit,
    onNavigateToTools: () -> Unit,
    onSwitchConversation: (AgentChatRoute) -> Unit,
) {
    composable<AgentChatRoute>(
        enterTransition = enterTransition,
        exitTransition = exitTransition,
        popEnterTransition = popEnterTransition,
        popExitTransition = popExitTransition,
    ) { backStackEntry ->
        val route = backStackEntry.toRoute<AgentChatRoute>()
        AgentScaffold(
            initialProjectStartAction = route.projectStartAction,
            onNavigateBack = onNavigateBack,
            onNavigateToSettings = onNavigateToSettings,
            onNavigateToArchival = onNavigateToArchival,
            onNavigateToTools = onNavigateToTools,
            onSwitchConversation = { agentId, conversationId, agentName ->
                val normalizedConversationId = conversationId?.takeIf { it.isNotBlank() }
                onSwitchConversation(
                    AgentChatRoute(
                        agentId = agentId,
                        agentName = agentName,
                        conversationId = normalizedConversationId,
                        freshRouteKey = if (normalizedConversationId == null) System.currentTimeMillis() else null,
                    )
                )
            },
            viewModel = hiltViewModel(
                backStackEntry,
                key = route.toViewModelKey(),
            ),
        )
    }
}

fun AgentChatRoute.toViewModelKey(): String = buildString {
    append(agentId)
    append(':')
    append(conversationId.orEmpty())
    append(':')
    append(freshRouteKey?.toString().orEmpty())
    append(':')
    append(projectIdentifier.orEmpty())
}
