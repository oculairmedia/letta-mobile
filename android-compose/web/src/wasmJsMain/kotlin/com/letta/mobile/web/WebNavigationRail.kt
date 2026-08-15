package com.letta.mobile.web

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
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
        RailHoverItem(
            tooltip = if (showSidebar) "Hide agent library" else "Search agents",
            accessibleDescription = "Search agents",
            onClick = onToggleSidebar,
        ) {
            Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(agents, key = AgentItemState::id) { agent ->
                val selected = destination == WebNavDestination.CHAT && agent.id == selectedAgentId
                RailHoverItem(
                    tooltip = agent.name,
                    accessibleDescription = agent.name,
                    size = 44.dp,
                    selected = selected,
                    onClick = { onAgentSelected(agent) },
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
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
            RailHoverItem(
                tooltip = if (workspaceSelected) "Change workspace" else "Open local workspace",
                accessibleDescription = "Open local workspace",
                selected = workspaceSelected,
                onClick = onOpenWorkspace,
            ) {
                Icon(
                    imageVector = Icons.Default.FolderOpen,
                    contentDescription = null,
                    tint = if (workspaceSelected) MaterialTheme.colorScheme.secondary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RailHoverItem(
                tooltip = "Backend settings",
                accessibleDescription = "Backend settings",
                selected = destination == WebNavDestination.SETTINGS,
                onClick = onSettings,
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    tint = if (destination == WebNavDestination.SETTINGS) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RailHoverItem(
    tooltip: String,
    accessibleDescription: String,
    onClick: () -> Unit,
    size: Dp = 40.dp,
    selected: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val containerColor = when {
        pressed -> MaterialTheme.colorScheme.surfaceContainerHighest
        hovered || selected -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> Color.Transparent
    }
    WebTooltip(tooltip, Modifier.size(size)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(8.dp))
                .background(containerColor)
                .hoverable(interaction)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Button,
                    onClick = onClick,
                )
                .semantics { contentDescription = accessibleDescription },
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}
