package com.letta.mobile.ui.screens.conversations

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.letta.mobile.ui.navigation.AgentChatRoute
import com.letta.mobile.ui.navigation.ArchivalRoute
import com.letta.mobile.ui.navigation.EditAgentRoute
import com.letta.mobile.ui.navigation.AllToolsRoute
import com.letta.mobile.ui.screens.chat.AgentScaffold
import com.letta.mobile.ui.theme.LocalWindowSizeClass
import com.letta.mobile.ui.theme.isWideWidth

@Composable
fun TwoPaneConversationsLayout(
    outerNavController: NavController,
    onNavigateToSettings: () -> Unit,
    onNavigateToAgentList: () -> Unit,
    onNavigateToTemplates: () -> Unit,
    onNavigateToArchives: () -> Unit,
    onNavigateToFolders: () -> Unit,
    onNavigateToGroups: () -> Unit,
    onNavigateToProviders: () -> Unit,
    onNavigateToBlocks: () -> Unit,
    onNavigateToIdentities: () -> Unit,
    onNavigateToSchedules: () -> Unit,
    onNavigateToRuns: () -> Unit,
    onNavigateToJobs: () -> Unit,
    onNavigateToMessageBatches: () -> Unit,
    onNavigateToMcp: () -> Unit,
    onNavigateToAbout: () -> Unit,
) {
    val windowSizeClass = LocalWindowSizeClass.current
    val listPaneWidth = if (windowSizeClass.isWideWidth) 400.dp else 340.dp
    val detailNavController = rememberNavController()
    var hasDetail by rememberSaveable { mutableStateOf(false) }

    Row(modifier = Modifier.fillMaxSize()) {
        ConversationsScreen(
            onNavigateToChat = { agentId, conversationId ->
                hasDetail = true
                detailNavController.navigate(
                    AgentChatRoute(agentId = agentId, conversationId = conversationId)
                ) {
                    popUpTo(0) { inclusive = true }
                }
            },
            onNavigateToSettings = onNavigateToSettings,
            onNavigateToAgentList = onNavigateToAgentList,
            onNavigateToTemplates = onNavigateToTemplates,
            onNavigateToArchives = onNavigateToArchives,
            onNavigateToFolders = onNavigateToFolders,
            onNavigateToGroups = onNavigateToGroups,
            onNavigateToProviders = onNavigateToProviders,
            onNavigateToBlocks = onNavigateToBlocks,
            onNavigateToIdentities = onNavigateToIdentities,
            onNavigateToSchedules = onNavigateToSchedules,
            onNavigateToRuns = onNavigateToRuns,
            onNavigateToJobs = onNavigateToJobs,
            onNavigateToMessageBatches = onNavigateToMessageBatches,
            onNavigateToMcp = onNavigateToMcp,
            onNavigateToAbout = onNavigateToAbout,
            modifier = Modifier
                .width(listPaneWidth)
                .fillMaxHeight(),
        )

        VerticalDivider()

        if (hasDetail) {
            NavHost(
                navController = detailNavController,
                startDestination = AgentChatRoute(agentId = ""),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                composable<AgentChatRoute> {
                    AgentScaffold(
                        onNavigateBack = { hasDetail = false },
                        onNavigateToSettings = { agentId ->
                            outerNavController.navigate(EditAgentRoute(agentId))
                        },
                        onNavigateToArchival = { agentId ->
                            outerNavController.navigate(ArchivalRoute(agentId))
                        },
                        onNavigateToTools = {
                            outerNavController.navigate(AllToolsRoute)
                        },
                        onSwitchConversation = { agentId, conversationId ->
                            detailNavController.navigate(
                                AgentChatRoute(agentId = agentId, conversationId = conversationId)
                            ) {
                                popUpTo(0) { inclusive = true }
                            }
                        },
                    )
                }
            }
        } else {
            EmptyDetailPane(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
        }
    }
}
