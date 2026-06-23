package com.letta.mobile.feature.chat.route

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.letta.mobile.feature.chat.screen.AgentScaffold

fun NavGraphBuilder.chatGraph(
    enterTransition: (AnimatedContentTransitionScope<*>.() -> EnterTransition)? = null,
    exitTransition: (AnimatedContentTransitionScope<*>.() -> ExitTransition)? = null,
    popEnterTransition: (AnimatedContentTransitionScope<*>.() -> EnterTransition)? = null,
    popExitTransition: (AnimatedContentTransitionScope<*>.() -> ExitTransition)? = null,
    onNavigateBack: () -> Unit,
    onNavigateToSettings: (String) -> Unit,
    onNavigateToArchival: (String) -> Unit,
    onNavigateToTools: () -> Unit,
    onNavigateToMemory: (String) -> Unit,
    onNavigateToAdmin: () -> Unit,
    onNavigateToSchedules: (String) -> Unit,
    onNavigateToConversationList: () -> Unit,
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
            onNavigateToMemory = onNavigateToMemory,
            onNavigateToAdmin = onNavigateToAdmin,
            onNavigateToSchedules = onNavigateToSchedules,
            onNavigateToConversationList = onNavigateToConversationList,
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
            // letta-mobile-aw0dv: open a subagent's OWN conversation, pinning
            // its conversation id (never a fresh route — a subagent always has
            // a real transcript) and defaulting to INTERACTIVE mode so the
            // user sees the full tool/turn detail of the subagent's work.
            onViewSubagentConversation = { subagentAgentId, subagentConversationId ->
                // letta-mobile-aw0dv: a subagent's registry conversation id is
                // almost always the BARE literal "default", which is NOT
                // addressable to the subagent's real transcript — the shim maps
                // bare "default" to the external form "conv-default-<agentId>"
                // (native-goal-mode.ts / mobile-channel-host.ts). #515 pinned
                // the route but passed the bare form, so resolution landed on a
                // fresh/wrong conversation. Transform "default" -> the external
                // resolvable id here so the chip opens the actual transcript.
                val normalizedConversationId = subagentConversationId
                    .takeIf { it.isNotBlank() }
                    ?.let { convId ->
                        if (convId == "default") "conv-default-$subagentAgentId" else convId
                    }
                onSwitchConversation(
                    AgentChatRoute(
                        agentId = subagentAgentId,
                        conversationId = normalizedConversationId,
                        // Never mint a fresh route for a subagent view — it has
                        // a real conversation. If the id is somehow blank, still
                        // avoid freshRouteKey so resolution targets the agent's
                        // existing transcript rather than a new conversation.
                        freshRouteKey = null,
                        initialChatMode = "interactive",
                    )
                )
            },
            viewModelKey = route.toViewModelKey(),
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
