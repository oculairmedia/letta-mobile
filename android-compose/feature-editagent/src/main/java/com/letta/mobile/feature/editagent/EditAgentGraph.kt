package com.letta.mobile.feature.editagent

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

@Serializable
data class EditAgentRoute(val agentId: String)

fun NavGraphBuilder.editAgentGraph(
    onNavigateBack: () -> Unit,
) {
    composable<EditAgentRoute> {
        EditAgentScreen(
            onNavigateBack = onNavigateBack
        )
    }
}
