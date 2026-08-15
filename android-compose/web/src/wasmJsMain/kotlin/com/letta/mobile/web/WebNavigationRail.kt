package com.letta.mobile.web

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.letta.mobile.web.data.AgentItemState
import com.letta.mobile.ui.theme.customColors

@Composable
internal fun WebNavigationRail(
    agents: List<AgentItemState>,
    selectedAgentId: String?,
    destination: WebNavDestination,
    showSidebar: Boolean,
    workspaceSelected: Boolean,
    onAgentSelected: (AgentItemState) -> Unit,
    onToggleSidebar: () -> Unit,
    onOpenWorkspace: () -> Unit,
    onSettings: () -> Unit,
) {
    val dividerColor = MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier = Modifier
            .width(56.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.background)
            .drawBehind {
                drawLine(
                    color = dividerColor,
                    start = androidx.compose.ui.geometry.Offset(size.width, 0f),
                    end = androidx.compose.ui.geometry.Offset(size.width, size.height),
                )
            }
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        WebTooltip(if (showSidebar) "Hide agent library" else "Search agents") {
            IconButton(onClick = onToggleSidebar) {
                Icon(Icons.Outlined.Search, contentDescription = "Search agents", modifier = Modifier.size(18.dp))
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(agents, key = AgentItemState::id) { agent ->
                val selected = destination == WebNavDestination.CHAT && agent.id == selectedAgentId
                WebTooltip(agent.name) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .semantics {
                                contentDescription = agent.name
                                role = Role.Button
                            }
                            .clickable { onAgentSelected(agent) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) {
                            Box(Modifier.align(Alignment.CenterStart).size(width = 3.dp, height = 28.dp).background(MaterialTheme.colorScheme.primary))
                        }
                        WebAgentAvatar(agents.indexOf(agent), 36.dp)
                        if (agent.isOnline) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.customColors.onlineColor)
                                    .border(1.dp, MaterialTheme.colorScheme.background, CircleShape),
                            )
                        }
                    }
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            WebTooltip(if (workspaceSelected) "Change workspace" else "Open local workspace") {
                IconButton(onClick = onOpenWorkspace) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = "Open local workspace",
                        tint = if (workspaceSelected) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            WebTooltip("Backend settings") {
                IconButton(onClick = onSettings) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Backend settings",
                        tint = if (destination == WebNavDestination.SETTINGS) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
