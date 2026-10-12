package com.letta.mobile.ui.screens.conversations

internal data class ConversationsNavigation(
    val onNavigateToTemplates: () -> Unit,
    val onNavigateToProviders: () -> Unit,
    val onNavigateToBlocks: () -> Unit,
    val onNavigateToSchedules: () -> Unit,
    val onNavigateToRuns: () -> Unit,
    val onNavigateToMcp: () -> Unit,
    val onNavigateToProjects: () -> Unit,
    val onNavigateToAbout: () -> Unit,
)
