package com.letta.mobile.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.AppLaunchTarget
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.LastChatSelection
import com.letta.mobile.feature.chat.route.AgentChatRoute
import kotlinx.coroutines.flow.StateFlow

/** The settings the app's start destination is decided from; all are read synchronously. */
internal class AppStartInputs(
    val activeConfig: StateFlow<LettaConfig?>,
    val lastChatSelection: StateFlow<LastChatSelection?>,
    val favoriteAgentId: StateFlow<String?>,
    val adminAgentId: StateFlow<String?>,
)

/**
 * letta-mobile-v7aln: the NavHost's start destination, decided once for as long as a backend is
 * (or is not) configured.
 *
 * Changing a NavHost's start destination to a different route class replaces its graph, and
 * Navigation pops the whole back stack when it does: every entry's ViewModel is cleared and the
 * new start destination opens fresh. The start destination used to follow live settings, seeded
 * with `null` placeholders for the first frame. After an Activity recreation (a rotation), the
 * first frame resolved to [ConversationsRoute] and the restored back stack went up on that
 * graph. One frame later the real last-chat selection resolved to [AgentChatRoute], the graph
 * was replaced, and the chat the user was reading was torn down and reloaded in a new ViewModel.
 * The same switch also fired mid-session the first time a chat set the last-chat selection.
 *
 * Every input is a StateFlow the settings repository fills synchronously at construction, so
 * their current values are read directly, never a placeholder. Only whether a backend is
 * configured re-decides the start: clearing all data still lands on [ConfigRoute], and
 * configuring the first backend still leaves it.
 */
@Suppress("NoAnyType") // NavHost.startDestination is Any; each branch is a typed route object.
@Composable
internal fun rememberAppStartDestination(
    inputs: AppStartInputs,
    initialNotificationTarget: AppLaunchTarget?,
): Any {
    val activeConfig by inputs.activeConfig.collectAsStateWithLifecycle()
    val hasConfig = activeConfig != null
    return remember(hasConfig) {
        resolveStartDestination(
            hasConfig = hasConfig,
            initialNotificationTarget = initialNotificationTarget,
            restoredChatSelection = inputs.lastChatSelection.value,
            fallbackAgentId = inputs.favoriteAgentId.value ?: inputs.adminAgentId.value,
        )
    }
}

@Suppress("NoAnyType")
internal fun resolveStartDestination(
    hasConfig: Boolean,
    initialNotificationTarget: AppLaunchTarget?,
    restoredChatSelection: LastChatSelection?,
    fallbackAgentId: String?,
): Any = when {
    hasConfig && initialNotificationTarget != null -> initialNotificationTarget.toRoute()
    hasConfig && restoredChatSelection != null -> AgentChatRoute(
        agentId = restoredChatSelection.agentId,
        agentName = restoredChatSelection.agentName,
        conversationId = restoredChatSelection.conversationId,
    )
    hasConfig && fallbackAgentId != null -> AgentChatRoute(agentId = fallbackAgentId)
    hasConfig -> ConversationsRoute
    else -> ConfigRoute()
}
