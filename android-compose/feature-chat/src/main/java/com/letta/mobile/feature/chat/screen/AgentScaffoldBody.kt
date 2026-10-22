package com.letta.mobile.feature.chat.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun AgentScaffoldBody(params: AgentScaffoldBodyParams) {
    val state = rememberAgentScaffoldRuntimeState(params)
    AgentScaffoldInteractionEffects(state)
    // The shared chat page's composer opens the picker `/models` and the drawer's card use.
    CompositionLocalProvider(LocalChatModelPicker provides params.modelPicker) { AgentScaffoldDrawerScaffold(state) }
    AgentScaffoldSheets(state)
}
