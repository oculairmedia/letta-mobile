package com.letta.mobile.ui.mascot

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.letta.mobile.data.model.AgentId

/**
 * One rule for where the focused agent's mascot stands, for every shell (the desktop and browser
 * workspace, the phone's navigation drawer): the agent pane's hero seat while the pane shows, else
 * rest. The previous agent is let go when focus moves.
 */
@Composable
fun DriveMascotStage(selectedAgent: AgentId?, agentPaneVisible: Boolean) {
    val selectedAgentId = selectedAgent?.value
    val transport = LocalMascotTransport.current
    var placedAgent by remember { mutableStateOf<String?>(null) }
    val stage = if (agentPaneVisible) MascotStage.AGENT_PANE_HERO else null
    LaunchedEffect(transport, selectedAgentId, stage) {
        placedAgent?.takeIf { it != selectedAgentId }?.let(transport::rest)
        placedAgent = selectedAgentId
        val agent = selectedAgentId ?: return@LaunchedEffect
        if (stage != null) transport.transportTo(agent, stage) else transport.rest(agent)
    }
}
