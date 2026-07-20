package com.letta.mobile.ui.screens.conversations

internal data class ConversationsNavigation(
    val onNavigateToTemplates: () -> Unit,
    val onNavigateToArchives: () -> Unit,
    val onNavigateToFolders: () -> Unit,
    val onNavigateToGroups: () -> Unit,
    val onNavigateToProviders: () -> Unit,
    val onNavigateToBlocks: () -> Unit,
    val onNavigateToIdentities: () -> Unit,
    val onNavigateToSchedules: () -> Unit,
    val onNavigateToRuns: () -> Unit,
    val onNavigateToJobs: () -> Unit,
    val onNavigateToMessageBatches: () -> Unit,
    val onNavigateToMcp: () -> Unit,
    val onNavigateToProjects: () -> Unit,
    val onNavigateToAbout: () -> Unit,
)
