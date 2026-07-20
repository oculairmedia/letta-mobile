package com.letta.mobile.feature.chat.screen

internal data class AgentScaffoldNavigationCallbacks(
    val onNavigateBack: () -> Unit,
    val onNavigateToSettings: (String) -> Unit,
    val onNavigateToArchival: ((String) -> Unit)? = null,
    val onNavigateToTools: (() -> Unit)? = null,
    val onNavigateToMemory: ((String) -> Unit)? = null,
    val onSwitchConversation: ((String, String?, String?) -> Unit)? = null,
    val onViewSubagentConversation: ((String, String) -> Unit)? = null,
    val onNavigateToAdmin: (() -> Unit)? = null,
    val onNavigateToConversationList: (() -> Unit)? = null,
    val onNavigateToSchedules: ((String) -> Unit)? = null,
)
