package com.letta.mobile.web

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.dp
import com.letta.mobile.web.data.AgentItemState
import com.letta.mobile.web.data.WebChatEntry
import com.letta.mobile.web.data.WebConnectionState

@Composable
internal fun WebChatPane(
    modifier: Modifier = Modifier,
    compact: Boolean,
    agents: List<AgentItemState>,
    selectedAgent: AgentItemState?,
    connectionState: WebConnectionState,
    messages: List<WebChatEntry>,
    input: String,
    isLoadingConversation: Boolean,
    isSending: Boolean,
    error: String?,
    workspaceName: String?,
    onInputChanged: (String) -> Unit,
    onAgentSelected: (AgentItemState) -> Unit,
    onSend: () -> Unit,
    onOpenWorkspace: () -> Unit,
    onSettings: () -> Unit,
) {
    val canSend = selectedAgent != null && connectionState is WebConnectionState.Connected &&
        !isLoadingConversation && !isSending
    Column(modifier = modifier.fillMaxHeight()) {
        ChatHeader(
            compact = compact,
            agents = agents,
            selectedAgent = selectedAgent,
            connectionState = connectionState,
            workspaceName = workspaceName,
            onAgentSelected = onAgentSelected,
            onOpenWorkspace = onOpenWorkspace,
            onSettings = onSettings,
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
            when {
                isLoadingConversation -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                selectedAgent == null -> EmptyChatState(connectionState, onSettings, Modifier.align(Alignment.Center))
                else -> WebChatMessages(messages, selectedAgent, isSending, compact)
            }
        }
        if (error != null) {
            Text(
                error,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(12.dp),
            )
        }
        WebChatComposer(
            input = input,
            selectedAgent = selectedAgent,
            enabled = canSend,
            compact = compact,
            workspaceName = workspaceName,
            onInputChanged = onInputChanged,
            onOpenWorkspace = onOpenWorkspace,
            onSend = onSend,
        )
    }
}

@Composable
private fun ChatHeader(
    compact: Boolean,
    agents: List<AgentItemState>,
    selectedAgent: AgentItemState?,
    connectionState: WebConnectionState,
    workspaceName: String?,
    onAgentSelected: (AgentItemState) -> Unit,
    onOpenWorkspace: () -> Unit,
    onSettings: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.background, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(44.dp).padding(horizontal = if (compact) 10.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (compact) Box {
                AssistChip(
                    onClick = { menuExpanded = true },
                    label = { Text(selectedAgent?.name ?: "Select agent") },
                    trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
                )
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    agents.forEach { agent ->
                        DropdownMenuItem(
                            text = { Text(agent.name) },
                            onClick = { menuExpanded = false; onAgentSelected(agent) },
                        )
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    WebAgentSphere(26.dp)
                    Column {
                        Text(selectedAgent?.name ?: "Select an agent", style = MaterialTheme.typography.titleSmall)
                        Text(
                            selectedAgent?.model ?: "No active conversation",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!compact) WebConnectionStatus(connectionState)
                WebTooltip("Backend settings") {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Backend settings")
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyChatState(state: WebConnectionState, onSettings: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        WebConnectionStatus(state)
        Spacer(Modifier.height(12.dp))
        Text(
            if (state is WebConnectionState.Connected) "The connected server returned no agents."
            else "Configure a backend before starting a conversation.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (state !is WebConnectionState.Connected) {
            Spacer(Modifier.height(8.dp))
            AssistChip(onClick = onSettings, label = { Text("Open settings") })
        }
    }
}
