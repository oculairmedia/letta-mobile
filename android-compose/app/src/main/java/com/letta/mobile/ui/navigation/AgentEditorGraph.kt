package com.letta.mobile.ui.navigation

import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.letta.mobile.feature.chat.route.AgentChatRoute
import com.letta.mobile.ui.screens.agenteditor.AgentEditorNavigation
import com.letta.mobile.ui.screens.agenteditor.AgentEditorScreen

/** letta-mobile-ckmub.3.24: the shared agent editor; a route without an agent is the new-agent flow. */
internal fun androidx.navigation.NavGraphBuilder.appAgentEditorGraph(navController: NavHostController) {
    composable<EditAgentRoute> {
        AgentEditorScreen(
            navigation = AgentEditorNavigation(
                onBack = { navController.popBackStack() },
                onCreated = { created ->
                    navController.navigate(AgentChatRoute(agentId = created.agentId, conversationId = created.conversationId)) {
                        popUpTo<EditAgentRoute> { inclusive = true }
                    }
                },
                onDeleted = {
                    if (!navController.popBackStack<ConversationsRoute>(inclusive = false)) {
                        navController.navigate(ConversationsRoute) { popUpTo(0) { inclusive = true } }
                    }
                },
                onOpen = { destination -> navController.navigateTo(destination) },
            ),
        )
    }
}
