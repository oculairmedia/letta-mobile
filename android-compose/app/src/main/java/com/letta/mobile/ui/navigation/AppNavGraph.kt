package com.letta.mobile.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.letta.mobile.ui.screens.agentlist.AgentListScreen
import com.letta.mobile.ui.screens.archival.ArchivalScreen
import com.letta.mobile.ui.screens.chat.AgentScaffold
import com.letta.mobile.ui.screens.config.ConfigListScreen
import com.letta.mobile.ui.screens.config.ConfigScreen
import com.letta.mobile.ui.screens.conversations.ConversationsScreen
import com.letta.mobile.ui.screens.editagent.EditAgentScreen
import com.letta.mobile.ui.screens.mcp.McpScreen
import com.letta.mobile.ui.screens.models.ModelBrowserScreen
import com.letta.mobile.ui.screens.templates.TemplatesScreen

@Composable
fun AppNavGraph(
    startDestination: String = "conversations" // TODO: Check if hasConfig from settings
) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable("conversations") {
            ConversationsScreen(
                onNavigateToChat = { agentId, conversationId ->
                    navController.navigate("agent/$agentId/chat?conversationId=$conversationId")
                },
                onNavigateToSettings = {
                    navController.navigate("config")
                },
                onNavigateToAgentList = {
                    navController.navigate("agentList")
                }
            )
        }

        composable("agentList") {
            AgentListScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToAgent = { agentId ->
                    navController.navigate("agent/$agentId/chat")
                },
                onNavigateToEditAgent = { agentId ->
                    navController.navigate("editAgent/$agentId")
                }
            )
        }

        composable("config") {
            ConfigScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToConfigList = {
                    navController.navigate("configList")
                }
            )
        }

        composable("configList") {
            ConfigListScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable("templates") {
            TemplatesScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToAgent = { agentId ->
                    navController.navigate("agent/$agentId/chat")
                }
            )
        }

        composable("mcp") {
            McpScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = "agent/{agentId}/chat?conversationId={conversationId}",
            arguments = listOf(
                navArgument("agentId") { type = NavType.StringType },
                navArgument("conversationId") { type = NavType.StringType; nullable = true; defaultValue = null },
            )
        ) {
            AgentScaffold(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToSettings = { agentId ->
                    navController.navigate("editAgent/$agentId")
                }
            )
        }

        composable(
            route = "editAgent/{agentId}",
            arguments = listOf(navArgument("agentId") { type = NavType.StringType })
        ) {
            EditAgentScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable("models") {
            ModelBrowserScreen(
                onNavigateBack = { navController.popBackStack() },
                onModelSelected = { modelId ->
                    navController.previousBackStackEntry?.savedStateHandle?.set("selectedModel", modelId)
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = "agent/{agentId}/archival",
            arguments = listOf(navArgument("agentId") { type = NavType.StringType })
        ) {
            ArchivalScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
