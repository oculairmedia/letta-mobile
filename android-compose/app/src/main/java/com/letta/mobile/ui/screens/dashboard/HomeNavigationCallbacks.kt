package com.letta.mobile.ui.screens.dashboard

internal data class HomeNavigationCallbacks(
    val onNavigateToAgents: () -> Unit,
    val onNavigateToConversations: () -> Unit,
    val onNavigateToTools: () -> Unit,
    val onNavigateToBlocks: () -> Unit,
    val onNavigateToSettings: () -> Unit,
    val onNavigateToChat: (agentId: String, agentName: String?, initialMessage: String?) -> Unit,
    val onNavigateToUsage: () -> Unit,
    val onNavigateToTemplates: () -> Unit = {},
    val onNavigateToArchives: () -> Unit = {},
    val onNavigateToFolders: () -> Unit = {},
    val onNavigateToGroups: () -> Unit = {},
    val onNavigateToProviders: () -> Unit = {},
    val onNavigateToIdentities: () -> Unit = {},
    val onNavigateToSchedules: () -> Unit = {},
    val onNavigateToRuns: () -> Unit = {},
    val onNavigateToJobs: () -> Unit = {},
    val onNavigateToMessageBatches: () -> Unit = {},
    val onNavigateToMcp: () -> Unit = {},
    val onNavigateToAbout: () -> Unit = {},
    val onNavigateToTelemetry: () -> Unit = {},
    val onNavigateToSystemAccess: () -> Unit = {},
    val onNavigateToBotSettings: () -> Unit = {},
    val onNavigateToProjects: () -> Unit = {},
    val onNavigateToModels: () -> Unit = {},
) {
    fun shortcutNavigator(shortcut: DashboardShortcut, state: DashboardUiState): () -> Unit = when (shortcut) {
        DashboardShortcut.CONVERSATIONS -> onNavigateToConversations
        DashboardShortcut.AGENTS -> onNavigateToAgents
        DashboardShortcut.TOOLS -> onNavigateToTools
        DashboardShortcut.BLOCKS -> onNavigateToBlocks
        DashboardShortcut.TEMPLATES -> onNavigateToTemplates
        DashboardShortcut.ARCHIVES -> onNavigateToArchives
        DashboardShortcut.FOLDERS -> onNavigateToFolders
        DashboardShortcut.GROUPS -> onNavigateToGroups
        DashboardShortcut.PROVIDERS -> onNavigateToProviders
        DashboardShortcut.IDENTITIES -> onNavigateToIdentities
        DashboardShortcut.SCHEDULES -> onNavigateToSchedules
        DashboardShortcut.RUNS -> onNavigateToRuns
        DashboardShortcut.JOBS -> onNavigateToJobs
        DashboardShortcut.MESSAGE_BATCHES -> onNavigateToMessageBatches
        DashboardShortcut.MCP_SERVERS -> onNavigateToMcp
        DashboardShortcut.BOT_SETTINGS -> onNavigateToBotSettings
        DashboardShortcut.PROJECTS -> onNavigateToProjects
        DashboardShortcut.MODELS -> onNavigateToModels
        DashboardShortcut.USAGE -> onNavigateToUsage
        DashboardShortcut.FAVORITE_AGENT -> {
            val agentId = state.favoriteAgentId
            if (agentId != null) {
                { onNavigateToChat(agentId, state.favoriteAgentName, null) }
            } else {
                onNavigateToAgents
            }
        }
        DashboardShortcut.SETTINGS -> onNavigateToSettings
        DashboardShortcut.TELEMETRY -> onNavigateToTelemetry
        DashboardShortcut.SYSTEM_ACCESS -> onNavigateToSystemAccess
        DashboardShortcut.ABOUT -> onNavigateToAbout
    }
}

internal data class HomeContentCallbacks(
    val onNavigateToTools: () -> Unit,
    val onNavigateToBlocks: () -> Unit,
    val onNavigateToChat: (String, String?, String?) -> Unit,
    val onNavigateToChatMessage: (String, String, String) -> Unit,
    val onNavigateToEditAgent: (String) -> Unit,
    val onUnpinAgent: (String) -> Unit,
    val onShortcutClick: (DashboardShortcut) -> Unit,
    val onUnpinShortcut: (DashboardShortcut) -> Unit,
    val onReorderPinnedItems: (List<String>) -> Unit,
)
