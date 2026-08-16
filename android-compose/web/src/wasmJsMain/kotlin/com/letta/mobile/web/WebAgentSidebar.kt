package com.letta.mobile.web

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.web.data.AgentItemState
import com.letta.mobile.web.data.WebConnectionState

@Composable
internal fun WebAgentSidebar(
    agents: List<AgentItemState>,
    selectedAgentId: String?,
    connectionState: WebConnectionState,
    isLoading: Boolean,
    error: String?,
    onAgentSelected: (AgentItemState) -> Unit,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, agents) {
        if (query.isBlank()) agents else agents.filter {
            it.name.contains(query, ignoreCase = true) ||
                it.description?.contains(query, ignoreCase = true) == true
        }
    }
    Column(
        modifier = Modifier
            .width(231.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val selectedAgent = agents.firstOrNull { it.id == selectedAgentId }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WebAgentAvatar(agents.indexOf(selectedAgent).coerceAtLeast(0), 30.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(selectedAgent?.name ?: "Letta", style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(
                    selectedAgent?.model ?: "Agent workspace",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                WebTooltip("Refresh agents") {
                    IconButton(onClick = onRefresh, enabled = connectionState !is WebConnectionState.Connecting) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh agents", modifier = Modifier.size(17.dp))
                    }
                }
            }
        }
        WebConnectionStatus(connectionState)
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(10.dp))
                Text("Conversation", style = MaterialTheme.typography.labelLarge)
            }
        }
        if (error != null) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    Text(
                        "Open backend settings",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(onClick = onSettings),
                    )
                }
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            placeholder = { Text("Search agents") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Agents",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
        )
        if (!isLoading && agents.isEmpty()) {
            Text(
                text = if (connectionState is WebConnectionState.Connected) "No agents returned by the server"
                else "Configure and connect a backend to load agents",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(filtered, key = AgentItemState::id) { agent ->
                val selected = agent.id == selectedAgentId
                WebTooltip(agent.name, Modifier.fillMaxWidth()) {
                    Surface(
                        color = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onAgentSelected(agent) },
                    ) {
                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            WebAgentAvatar(agents.indexOf(agent), 28.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(agent.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    agent.model,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
