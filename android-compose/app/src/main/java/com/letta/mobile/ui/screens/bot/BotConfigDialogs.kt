package com.letta.mobile.ui.screens.bot

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.components.ConfirmDialog
import com.letta.mobile.ui.icons.LettaIcons

@Composable
internal fun HelpDialog(
    show: Boolean,
    title: String,
    description: String,
    onDismiss: () -> Unit,
) {
    if (!show) return
    ConfirmDialog(
        show = true,
        title = title,
        message = description,
        confirmText = "Got it",
        dismissText = "Got it",
        onConfirm = onDismiss,
        onDismiss = onDismiss,
    )
}

@Composable
internal fun HelpIcon(title: String, description: String) {
    var showHelp by remember { mutableStateOf(false) }
    IconButton(onClick = { showHelp = true }, modifier = Modifier.size(32.dp)) {
        Icon(
            LettaIcons.Help,
            contentDescription = "Help: $title",
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(18.dp),
        )
    }
    HelpDialog(show = showHelp, title = title, description = description, onDismiss = { showHelp = false })
}

@Composable
internal fun AgentSearchResults(
    vm: BotConfigEditViewModel,
    filtered: List<com.letta.mobile.data.model.Agent>,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        if (filtered.isEmpty()) {
            Text(
                text = "No agents found",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                items(filtered, key = { it.id.value }) { agent ->
                    ListItem(
                        headlineContent = {
                            Text(agent.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            Text(
                                agent.model ?: agent.id.value.take(16),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        leadingContent = {
                            Icon(LettaIcons.Agent, contentDescription = null)
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { vm.selectAgent(agent) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun AgentSearchAllResults(
    vm: BotConfigEditViewModel,
    agents: List<com.letta.mobile.data.model.Agent>,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
            items(agents, key = { it.id.value }) { agent ->
                ListItem(
                    headlineContent = {
                        Text(agent.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = {
                        Text(
                            agent.model ?: agent.id.value.take(16),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingContent = {
                        Icon(LettaIcons.Agent, contentDescription = null)
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { vm.selectAgent(agent) },
                )
            }
        }
    }
}

@Composable
internal fun SelectedAgentCard(
    vm: BotConfigEditViewModel,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { vm.agentSearchExpanded = true }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                LettaIcons.Agent,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = vm.selectedAgentName ?: "Agent",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text = vm.heartbeatAgentId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { vm.clearAgentSelection() }) {
                Icon(
                    LettaIcons.Clear,
                    contentDescription = "Clear agent",
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}
