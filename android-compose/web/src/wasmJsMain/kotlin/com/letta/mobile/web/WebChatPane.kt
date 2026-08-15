package com.letta.mobile.web

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.letta.mobile.web.data.AgentItemState
import com.letta.mobile.ui.theme.customColors
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
                messages.isEmpty() -> Text(
                    "No messages yet. Start a conversation with ${selectedAgent.name}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = if (compact) 12.dp else 24.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(messages, key = WebChatEntry::id) { message -> ChatMessage(message) }
                    if (isSending) {
                        item { CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) }
                    }
                }
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
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChanged,
                    enabled = canSend,
                    placeholder = { Text(selectedAgent?.let { "Message ${it.name}" } ?: "Select an agent") },
                    modifier = Modifier.weight(1f),
                    maxLines = 4,
                )
                Spacer(Modifier.width(12.dp))
                FilledIconButton(onClick = onSend, enabled = canSend && input.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send message")
                }
            }
        }
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
    Surface(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = if (compact) 12.dp else 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Box {
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
            }
            if (!compact) WebConnectionStatus(connectionState)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!compact) {
                    AssistChip(
                        onClick = onOpenWorkspace,
                        label = { Text(workspaceName?.let { "Workspace: $it" } ?: "Select workspace") },
                        leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    )
                }
                IconButton(onClick = onSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "Backend settings")
                }
            }
        }
    }
}

@Composable
private fun ChatMessage(message: WebChatEntry) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (message.isUser) MaterialTheme.customColors.userBubbleBgColor
            else MaterialTheme.customColors.agentBubbleBgColor,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.widthIn(max = 640.dp),
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(message.sender, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(message.text, style = MaterialTheme.typography.bodyMedium)
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
