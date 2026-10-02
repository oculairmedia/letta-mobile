package com.letta.mobile.web

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.canvas.CanvasDocumentStore
import com.letta.mobile.ui.chat.AgentOrb
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.web.chat.WebChatLoad
import com.letta.mobile.web.chat.WebChatPageNavigation
import com.letta.mobile.web.chat.WebSharedChatPage
import com.letta.mobile.web.data.AgentItemState
import com.letta.mobile.web.data.WebConnectionState

/** The agent roster and connection the chat destination's header and empty state show. */
internal data class WebChatRoster(
    val agents: List<AgentItemState>,
    val selectedAgent: AgentItemState?,
    val connectionState: WebConnectionState,
    val error: String?,
)

/** The board docked under the conversation: where it is kept, and whether the page opens on it. */
internal data class WebChatCanvas(
    val store: CanvasDocumentStore,
    val openOnCanvas: Boolean,
)

/** Everything the chat destination draws from. */
internal data class WebChatDestinationState(
    val compact: Boolean,
    val roster: WebChatRoster,
    val chat: WebChatLoad,
    val canvas: WebChatCanvas,
)

/** Where the chat destination's shell controls go. */
internal data class WebChatShellActions(
    val onAgentSelected: (AgentItemState) -> Unit,
    val onSettings: () -> Unit,
    val onShowAgents: () -> Unit,
)

/**
 * letta-mobile-o4ygk.4.5: the chat destination. The web shell's header (the agent, the
 * connection) over the shared chat page once the conversation is open, or the connect state until
 * then. Everything inside the page is the shared UI; only the header and the empty state are the
 * web's own.
 */
@Composable
internal fun WebChatDestination(
    state: WebChatDestinationState,
    actions: WebChatShellActions,
    modifier: Modifier = Modifier,
) {
    val roster = state.roster
    Column(modifier = modifier.fillMaxHeight()) {
        WebChatHeader(state.compact, roster, actions)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (val chat = state.chat) {
                is WebChatLoad.Ready -> WebSharedChatPage(
                    port = chat.port,
                    canvas = state.canvas,
                    navigation = WebChatPageNavigation(
                        openAgentPane = actions.onShowAgents,
                        agentNamesById = roster.agents.associate { it.id to it.name },
                    ),
                )
                WebChatLoad.Opening -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is WebChatLoad.Failed, WebChatLoad.Idle -> WebConnectState(
                    state = WebConnectInputs(
                        connection = roster.connectionState,
                        error = (chat as? WebChatLoad.Failed)?.message ?: roster.error,
                        compact = state.compact,
                    ),
                    onSettings = actions.onSettings,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

@Composable
private fun WebChatHeader(compact: Boolean, roster: WebChatRoster, actions: WebChatShellActions) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(HeaderHeight)
                .padding(horizontal = if (compact) LettaDimens.Space.sm else LettaDimens.Space.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (compact) CompactAgentPicker(roster, actions.onAgentSelected) else SelectedAgentTitle(roster)
            if (compact) {
                WebTooltip("Backend settings") {
                    IconButton(onClick = actions.onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Backend settings")
                    }
                }
            } else {
                WebConnectionStatus(roster.connectionState)
            }
        }
    }
}

@Composable
private fun SelectedAgentTitle(roster: WebChatRoster) {
    val agent = roster.selectedAgent
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
        AgentOrb(roster.agents.indexOf(agent).coerceAtLeast(0), OrbSize, agentId = agent?.id)
        Column {
            Text(agent?.name ?: "Select an agent", style = MaterialTheme.typography.titleSmall)
            Text(
                agent?.model ?: "No active conversation",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CompactAgentPicker(roster: WebChatRoster, onAgentSelected: (AgentItemState) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { expanded = true },
            label = { Text(roster.selectedAgent?.name ?: "Select agent") },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            roster.agents.forEach { agent ->
                DropdownMenuItem(
                    text = { Text(agent.name) },
                    onClick = {
                        expanded = false
                        onAgentSelected(agent)
                    },
                )
            }
        }
    }
}

/** What the connect state shows: the connection, the last error, and the layout it sits in. */
private data class WebConnectInputs(
    val connection: WebConnectionState,
    val error: String?,
    val compact: Boolean,
)

/** Before a conversation opens: the connection, and what to do next. */
@Composable
private fun WebConnectState(
    state: WebConnectInputs,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(LettaDimens.Space.xl).testTag(WebChatTags.CONNECT_STATE),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        WebConnectionStatus(state.connection)
        Spacer(Modifier.height(LettaDimens.Space.md))
        Text(connectHint(state.connection), style = MaterialTheme.typography.bodyMedium)
        state.error?.let { error ->
            Spacer(Modifier.height(LettaDimens.Space.sm))
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (state.compact && state.connection !is WebConnectionState.Connected) {
            Spacer(Modifier.height(LettaDimens.Space.sm))
            AssistChip(onClick = onSettings, label = { Text("Open settings") })
        }
    }
}

private fun connectHint(state: WebConnectionState): String = when (state) {
    is WebConnectionState.Connected -> "The connected server returned no agents."
    WebConnectionState.Connecting -> "Connecting to the App Server..."
    is WebConnectionState.Failed -> "Could not reach the App Server. Check the backend settings."
    WebConnectionState.Unconfigured -> "Configure a backend before starting a conversation."
}

/** Test tags for the web chat destination. */
internal object WebChatTags {
    const val CONNECT_STATE = "web-chat-connect-state"
}

private val HeaderHeight = 44.dp
private val OrbSize = 26.dp
