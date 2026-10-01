package com.letta.mobile.feature.chat.screen

import androidx.compose.runtime.staticCompositionLocalOf

internal data class ChatScreenNavigationCallbacks(
    val onBugCommand: (() -> Unit)? = null,
    val onViewSubagentConversation: ((String, String) -> Unit)? = null,
    /** Open the agent's pane (the scaffold drawer) - the composer companion mascot taps into it. */
    val onOpenAgentPane: (() -> Unit)? = null,
    val onOpenCanvas: (() -> Unit)? = null,
)

/**
 * letta-mobile-bccty: the scaffold's inter-agent provenance wiring - an agent id's display name
 * and switching to that agent's conversation - which the shared chat page's host hands its rows.
 */
internal data class AndroidAgentMessageContext(
    val resolveName: (String) -> String? = { null },
    val onAgentClick: (String) -> Unit = {},
)

internal val LocalAndroidAgentMessageContext = staticCompositionLocalOf { AndroidAgentMessageContext() }
