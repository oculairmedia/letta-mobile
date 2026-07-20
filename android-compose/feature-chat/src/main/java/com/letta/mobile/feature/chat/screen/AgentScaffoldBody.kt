package com.letta.mobile.feature.chat.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun AgentScaffoldBody(params: AgentScaffoldBodyParams) {
    val state = rememberAgentScaffoldRuntimeState(params)
    AgentScaffoldInteractionEffects(state)
    AgentScaffoldDrawerScaffold(state)
    AgentScaffoldSheets(state)
}
