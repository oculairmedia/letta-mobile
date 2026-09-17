package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.letta.mobile.ui.mascot.LocalMascotTransport
import com.letta.mobile.ui.mascot.MascotStage

/**
 * One rule for where the focused agent's mascot stands (wbin4.4): the agent pane's hero seat
 * while the pane shows (however it was opened), else rest. Editing moves nothing - the editor
 * previews its pick on the character where it stands. The previous agent is let go when focus
 * moves, so every control that opens a pane moves the mascot the same way.
 */
@Composable
internal fun DriveMascotStage(selectedAgentId: String?, agentPaneVisible: Boolean) {
    val transport = LocalMascotTransport.current
    var placedAgent by remember { mutableStateOf<String?>(null) }
    val stage = if (agentPaneVisible) MascotStage.AGENT_PANE_HERO else null
    LaunchedEffect(selectedAgentId, stage) {
        placedAgent?.takeIf { it != selectedAgentId }?.let(transport::rest)
        placedAgent = selectedAgentId
        val agent = selectedAgentId ?: return@LaunchedEffect
        if (stage != null) transport.transportTo(agent, stage) else transport.rest(agent)
    }
}
