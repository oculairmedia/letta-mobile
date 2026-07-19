package com.letta.mobile.desktop

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.desktop.chat.AgentOrb

/**
 * Format an ISO-8601 instant (e.g. lastMessageAt) as a compact relative label
 * (now / 5m / 2h / 4d / 3w / 2mo). Non-ISO values are returned unchanged.
 */
internal fun formatRelativeTimestamp(raw: String): String {
    val instant = runCatching { java.time.Instant.parse(raw) }.getOrNull() ?: return raw
    val seconds = java.time.Duration.between(instant, java.time.Instant.now()).seconds
    return when {
        seconds < 60 -> "now"
        seconds < 3_600 -> "${seconds / 60}m"
        seconds < 86_400 -> "${seconds / 3_600}h"
        seconds < 604_800 -> "${seconds / 86_400}d"
        seconds < 2_592_000 -> "${seconds / 604_800}w"
        else -> "${seconds / 2_592_000}mo"
    }
}

@Composable
internal fun RailDivider() {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/** Pulsing teal ring used as the agent "thinking" indicator. */
@Composable
private fun ThinkingRing(
    diameter: Dp,
    cornerRadius: Dp,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "thinking")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "thinkingAlpha",
    )
    Box(
        modifier = modifier
            .size(diameter)
            .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = alpha), RoundedCornerShape(cornerRadius)),
    )
}

internal data class DesktopAgentRailFocus(
    val selectedAgentId: String?,
    val thinkingAgentId: String?,
    val avatarStyleByAgentId: Map<String, Int>,
)

internal data class DesktopAgentRailState(
    val agents: List<Pair<String, String>>,
    val focus: DesktopAgentRailFocus,
    val avatarCompanionActive: Boolean = false,
)

internal data class DesktopAgentRailActions(
    val onAgentSelected: (String) -> Unit,
    val onNewSession: () -> Unit,
    val onSearch: () -> Unit,
    val onAvatarCompanion: () -> Unit = {},
)

/**
 * Far-left workspace/agent rail (Penpot "App Mockups v2", 56.dp wide, #0A0A0A):
 * a "+" new-session button, a stack of gradient agent orbs (one per agent), and
 * search/settings/identity actions pinned to the bottom.
 */
@Composable
internal fun DesktopAgentRail(
    state: DesktopAgentRailState,
    actions: DesktopAgentRailActions,
) {
    // Collapse agents that share a display name — e.g. the many ephemeral
    // "Letta Code" agents spawned per task — into a single stacked orb with a
    // count chip, so the rail doesn't grow unbounded with near-duplicate spawns.
    // Order follows first appearance in [agents].
    val groups = remember(state.agents) {
        state.agents.groupBy { it.second }
            .map { (name, members) -> AgentRailGroup(name = name, agentIds = members.map { it.first }) }
    }
    Column(
        modifier = Modifier
            .width(56.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.background)
            .padding(vertical = 15.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NewSessionButton(onNewSession = actions.onNewSession)
        Spacer(Modifier.height(8.dp))
        AgentRailOrbList(
            groups = groups,
            focus = state.focus,
            onAgentSelected = actions.onAgentSelected,
        )
        AgentRailBottomActions(state = state, actions = actions)
    }
}

@Composable
private fun ColumnScope.AgentRailOrbList(
    groups: List<AgentRailGroup>,
    focus: DesktopAgentRailFocus,
    onAgentSelected: (String) -> Unit,
) {
    // The agent list scrolls so a long roster never pushes the bottom
    // actions (search/settings/account) off-screen.
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        groups.forEachIndexed { index, group ->
            AgentRailOrb(
                AgentRailOrbParams(
                    group = group,
                    index = index,
                    focus = focus,
                    onAgentSelected = onAgentSelected,
                ),
            )
        }
    }
}

@Composable
private fun AgentRailBottomActions(
    state: DesktopAgentRailState,
    actions: DesktopAgentRailActions,
) {
    RailActionIcon(
        RailActionIconModel(
            icon = Icons.Outlined.Face,
            description = if (state.avatarCompanionActive) "Stop avatar companion" else "Avatar companion",
            onClick = actions.onAvatarCompanion,
            tint = if (state.avatarCompanionActive) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        ),
    )
    RailActionIcon(
        RailActionIconModel(
            icon = Icons.Outlined.Search,
            description = "Search",
            onClick = actions.onSearch,
        ),
    )
}

@Composable
private fun NewSessionButton(onNewSession: () -> Unit) {
    DesktopTooltip(text = "New session") {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(onClick = onNewSession),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = "New session",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

private data class AgentRailOrbParams(
    val group: AgentRailGroup,
    val index: Int,
    val focus: DesktopAgentRailFocus,
    val onAgentSelected: (String) -> Unit,
)

private data class AgentRailOrbFlags(
    val selected: Boolean,
    val thinking: Boolean,
    val count: Int,
)

private data class AgentRailOrbTarget(
    val agentId: String,
    val orbStyle: Int,
    val tooltip: String,
)

private fun AgentRailOrbParams.toFlags(): AgentRailOrbFlags {
    val group = group
    return AgentRailOrbFlags(
        selected = focus.selectedAgentId != null && focus.selectedAgentId in group.agentIds,
        thinking = focus.thinkingAgentId != null && focus.thinkingAgentId in group.agentIds,
        count = group.agentIds.size,
    )
}

private fun AgentRailOrbParams.toTarget(flags: AgentRailOrbFlags): AgentRailOrbTarget {
    val group = group
    // Clicking a stack opens its already-selected member if one is
    // selected, otherwise its first (most-recent) member.
    val targetAgentId = group.agentIds.firstOrNull { it == focus.selectedAgentId } ?: group.agentIds.first()
    // Use the stack member's saved avatar style if any set one,
    // otherwise the position-derived colour.
    val orbStyle = group.agentIds.firstNotNullOfOrNull { focus.avatarStyleByAgentId[it] } ?: index
    val tooltip = buildString {
        append(group.name)
        if (flags.count > 1) append(" · ${flags.count} agents")
        if (flags.thinking) append(" · thinking…")
    }
    return AgentRailOrbTarget(agentId = targetAgentId, orbStyle = orbStyle, tooltip = tooltip)
}

@Composable
private fun AgentRailOrb(params: AgentRailOrbParams) {
    val flags = params.toFlags()
    val target = params.toTarget(flags)
    DesktopTooltip(text = target.tooltip) {
        AgentRailOrbContent(
            params = params,
            flags = flags,
            target = target,
        )
    }
}

@Composable
private fun AgentRailOrbContent(
    params: AgentRailOrbParams,
    flags: AgentRailOrbFlags,
    target: AgentRailOrbTarget,
) {
    Box(
        modifier = Modifier.size(width = 46.dp, height = 40.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (flags.selected) {
            SelectedAgentRailMarker(modifier = Modifier.align(Alignment.CenterStart))
        }
        if (flags.thinking) {
            // Concentric with the 36dp/7dp orb (2dp gap → 9dp corner)
            // and sized to fit the 40dp slot so it doesn't crowd
            // neighbouring orbs.
            ThinkingRing(diameter = 40.dp, cornerRadius = 9.dp)
        }
        AgentOrb(
            index = target.orbStyle,
            size = 36.dp,
            onClick = { params.onAgentSelected(target.agentId) },
        ) {
            Text(
                text = params.group.name.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
        }
        if (flags.count > 1) {
            AgentCountChip(
                count = flags.count,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
    }
}

@Composable
private fun SelectedAgentRailMarker(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(width = 3.dp, height = 28.dp)
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
    )
}

@Composable
private fun AgentCountChip(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 17.dp, minHeight = 17.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
            .border(1.5.dp, MaterialTheme.colorScheme.background, CircleShape)
            .padding(horizontal = 5.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A rail entry: one or more agents that share a display name, stacked together. */
private data class AgentRailGroup(
    val name: String,
    val agentIds: List<String>,
)

private data class RailActionIconModel(
    val icon: ImageVector,
    val description: String,
    val onClick: () -> Unit,
    val tint: Color = Color.Unspecified,
)

@Composable
private fun RailActionIcon(model: RailActionIconModel) {
    DesktopTooltip(text = model.description) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .clickable(onClick = model.onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = model.icon,
                contentDescription = model.description,
                tint = model.tint.takeIf { it != Color.Unspecified }
                    ?: MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
