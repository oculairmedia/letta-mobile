package com.letta.mobile.feature.chat.screen

import androidx.compose.runtime.staticCompositionLocalOf

internal data class ChatScreenNavigationCallbacks(
    val onBugCommand: (() -> Unit)? = null,
    val onViewSubagentConversation: ((String, String) -> Unit)? = null,
    /** Open the agent's pane (the scaffold drawer) - the composer companion mascot taps into it. */
    val onOpenAgentPane: (() -> Unit)? = null,
    val onOpenCanvas: (() -> Unit)? = null,
    /** Open the agent switcher (the header's agent pill), from where the header is not shown. */
    val onOpenAgentSwitcher: (() -> Unit)? = null,
    /** letta-mobile-bzvro.15/.16: open a conversation (agent id, conversation id), e.g. a fresh fork. */
    val onOpenConversation: ((String, String) -> Unit)? = null,
)

/**
 * letta-mobile-bccty: the inter-agent provenance label's two lookups, from the scaffold: an agent
 * id's display name, and opening that agent's most recent conversation. Hard cut S9: the shared
 * page hands them to its rows as the surface host's resolveAgentName and openAgent (the legacy
 * bubbles read them from here directly).
 */
internal data class AndroidAgentMessageContext(
    val resolveName: ((String) -> String?)? = null,
    val onAgentClick: ((String) -> Unit)? = null,
)

internal val LocalAndroidAgentMessageContext = staticCompositionLocalOf { AndroidAgentMessageContext() }
