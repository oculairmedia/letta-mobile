package com.letta.mobile.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.lens.LensDestination
import com.letta.mobile.data.lens.WorkPlayLens
import com.letta.mobile.data.lens.WorkPlayMode
import org.jetbrains.jewel.ui.component.Icon as JewelIcon
import org.jetbrains.jewel.ui.component.SimpleListItem as JewelSimpleListItem
import org.jetbrains.jewel.ui.component.Text as JewelText
import com.letta.mobile.ui.theme.LettaDimens

/**
 * Agent sidebar (231.dp, #0D0D0D): the active agent header, per-agent
 * navigation (Memory/Schedules/Channels/Skills/New chat), then the pinned
 * conversation list and a Documents section.
 */
@Composable
internal fun DesktopAgentSidebar(
    state: DesktopAgentSidebarState,
    actions: DesktopAgentSidebarActions,
) {
    Column(
        modifier = Modifier
            .width(231.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.lg),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.hair),
    ) {
        // Work | Play lens switcher (Penpot "App Mockups v2": top of sidebar).
        // Temporarily hidden — restore by uncommenting. The lens itself still
        // drives the sidebar (WorkPlayLens reads state.mode in the nav and
        // conversation sections); only the toggle that changes it is hidden.
        // WorkPlaySwitcher(
        //     mode = state.mode,
        //     onModeChange = actions.onModeChange,
        //     modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        // )
        SidebarAgentHeader(state = state, actions = actions)
        SidebarNavSection(state = state, actions = actions)
        SidebarConversationList(state = state, actions = actions)

        DesktopNavRow(
            model = DesktopNavRowModel(
                label = "Settings",
                icon = Icons.Outlined.Settings,
                selected = state.selectedDestination == DesktopDestination.Settings,
            ),
            onClick = { actions.onDestinationSelected(DesktopDestination.Settings) },
        )
    }
}

/** Maps a lens nav item to its concrete desktop destination + icon for the mode. */
internal fun lensNavTarget(
    mode: WorkPlayMode,
    destination: LensDestination,
): Pair<DesktopDestination, ImageVector> = when (destination) {
    LensDestination.Memory -> DesktopDestination.Memory to Icons.Outlined.Psychology
    LensDestination.Schedules -> DesktopDestination.Schedules to Icons.Outlined.Schedule
    LensDestination.Channels -> DesktopDestination.Channels to Icons.Outlined.Hub
    LensDestination.Skills -> DesktopDestination.Agents to
        if (mode == WorkPlayMode.Play) Icons.Outlined.Group else Icons.Outlined.Build
    LensDestination.Conversations -> DesktopDestination.Conversations to Icons.Outlined.ChatBubbleOutline
}

/**
 * Segmented Work | Play toggle (Penpot "App Mockups v2", top of the sidebar).
 *
 * Currently not rendered — see the commented-out call in [DesktopAgentSidebar].
 * Kept intact so restoring it is a one-line uncomment.
 */
@Suppress("UnusedPrivateMember", "unused")
@Composable
private fun WorkPlaySwitcher(
    mode: WorkPlayMode,
    onModeChange: (WorkPlayMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(LettaDimens.Radius.md),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(modifier = Modifier.padding(LettaDimens.Space.xs), horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
            WorkPlayMode.entries.forEach { option ->
                val selected = option == mode
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(LettaDimens.Radius.sm))
                        .background(
                            if (selected) MaterialTheme.colorScheme.surfaceContainerLowest else Color.Transparent,
                        )
                        .clickable { onModeChange(option) }
                        .padding(vertical = LettaDimens.Space.sm),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = WorkPlayLens.modeLabel(option),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun DesktopNavRow(
    model: DesktopNavRowModel,
    onClick: () -> Unit,
) {
    val content = when {
        model.selected -> MaterialTheme.colorScheme.onSurface
        model.subdued -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    DesktopTooltip(text = model.resolvedTooltip) {
        JewelSimpleListItem(
            selected = model.selected,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
            height = LettaDimens.Space.xxl,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                JewelIcon(
                    imageVector = model.icon,
                    contentDescription = null,
                    modifier = Modifier.size(LettaDimens.Control.icon),
                    tint = content,
                )
                JewelText(
                    text = model.label,
                    color = content,
                    fontWeight = if (model.selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
