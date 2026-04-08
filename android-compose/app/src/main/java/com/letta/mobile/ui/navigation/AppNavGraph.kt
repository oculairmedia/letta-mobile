package com.letta.mobile.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.letta.mobile.data.repository.SettingsRepository
import com.letta.mobile.ui.screens.about.AboutScreen
import com.letta.mobile.ui.screens.agentlist.AgentListScreen
import com.letta.mobile.ui.screens.dashboard.HomeScreen
import com.letta.mobile.ui.screens.archival.ArchivalScreen
import com.letta.mobile.ui.screens.chat.AgentScaffold
import com.letta.mobile.ui.screens.config.ConfigListScreen
import com.letta.mobile.ui.screens.config.ConfigScreen
import com.letta.mobile.ui.screens.conversations.ConversationsScreen
import com.letta.mobile.ui.screens.editagent.EditAgentScreen
import com.letta.mobile.ui.screens.mcp.McpScreen
import com.letta.mobile.ui.screens.models.ModelBrowserScreen
import com.letta.mobile.ui.screens.templates.TemplatesScreen
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NavViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    val hasConfig = settingsRepository.activeConfig.map { it != null }

    fun clearAllData() {
        viewModelScope.launch { settingsRepository.clearAllData() }
    }
}

@Composable
fun AppNavGraph() {
    val navViewModel: NavViewModel = hiltViewModel()
    val hasConfig by navViewModel.hasConfig.collectAsState(initial = true)

    val startDestination = if (hasConfig) "home" else "config"
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable("home") {
            HomeScreen(
                onNavigateToAgents = { navController.navigate("agentList") },
                onNavigateToConversations = { navController.navigate("conversations") },
                onNavigateToSettings = { navController.navigate("config") },
                onNavigateToChat = { agentId ->
                    navController.navigate("agent/$agentId/chat")
                },
            )
        }

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
                },
                onNavigateToTemplates = {
                    navController.navigate("templates")
                },
                onNavigateToMcp = {
                    navController.navigate("mcp")
                },
                onNavigateToAbout = {
                    navController.navigate("about")
                },
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
                onNavigateBack = {
                    if (navController.previousBackStackEntry != null) {
                        navController.popBackStack()
                    } else {
                        navController.navigate("conversations") {
                            popUpTo("config") { inclusive = true }
                        }
                    }
                },
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
                },
                onNavigateToArchival = { agentId ->
                    navController.navigate("agent/$agentId/archival")
                },
                onSwitchConversation = { agentId, conversationId ->
                    navController.navigate("agent/$agentId/chat?conversationId=$conversationId") {
                        popUpTo("conversations")
                    }
                },
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

        composable("about") {
            AboutScreen(
                onNavigateBack = { navController.popBackStack() },
                onLogout = {
                    navViewModel.clearAllData()
                    navController.navigate("config") {
                        popUpTo(0) { inclusive = true }
                    }
                },
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
