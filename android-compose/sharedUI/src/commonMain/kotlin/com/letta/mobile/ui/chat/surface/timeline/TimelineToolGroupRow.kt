package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.timeline_collapsed
import com.letta.mobile.sharedui.resources.timeline_expanded
import com.letta.mobile.sharedui.resources.timeline_tool_calls_count
import com.letta.mobile.sharedui.resources.timeline_tool_group_collapse
import com.letta.mobile.sharedui.resources.timeline_tool_group_expand
import com.letta.mobile.ui.chat.ChatColumnMaxWidth
import com.letta.mobile.ui.chat.surface.timeline.rows.ChatRenderItemRow
import com.letta.mobile.ui.chat.surface.timeline.rows.ChatRowCallbacks
import com.letta.mobile.ui.components.DisclosureChevron
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens
import org.jetbrains.compose.resources.stringResource

/**
 * letta-mobile-bglj6.1: the collapsed "N tool calls" row (desktop DesktopToolGroupCard). Expanded,
 * it draws each folded message through the shared row seam, exactly as ungrouped rendering would.
 */
@Composable
internal fun ToolGroupRow(
    group: TimelineRow.ToolGroup,
    contexts: TimelineRowContexts,
    callbacks: ChatRowCallbacks,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(group.key) { mutableStateOf(group.startsExpanded) }
    // One-way: a call that starts running or fails later re-opens the group; nothing collapses it
    // back under the user.
    LaunchedEffect(group.key, group.startsExpanded) {
        if (group.startsExpanded) expanded = true
    }
    Column(modifier = modifier.widthIn(max = ChatColumnMaxWidth).fillMaxWidth().animateContentSize()) {
        ToolGroupHeader(group, expanded) { expanded = !expanded }
        if (expanded) {
            Column(
                modifier = Modifier.padding(start = LettaDimens.Space.md),
                verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.hair),
            ) {
                group.singles.forEach { single ->
                    ChatRenderItemRow(item = single, context = contexts.forItem(single), callbacks = callbacks)
                }
            }
        }
    }
}

@Composable
private fun ToolGroupHeader(group: TimelineRow.ToolGroup, expanded: Boolean, onToggle: () -> Unit) {
    val state = stringResource(if (expanded) Res.string.timeline_expanded else Res.string.timeline_collapsed)
    val summary = remember(group) {
        group.toolNameCounts.joinToString(" · ") { (name, count) -> if (count > 1) "$name ×$count" else name }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics { stateDescription = state }
            .padding(horizontal = LettaDimens.Space.md, vertical = LettaDimens.Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        Icon(
            imageVector = LettaIcons.Tool,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(LettaDimens.Control.iconSm),
        )
        Text(
            text = stringResource(Res.string.timeline_tool_calls_count, group.toolCallCount),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (!expanded) {
            Text(
                text = summary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        DisclosureChevron(
            expanded = expanded,
            compact = true,
            contentDescription = stringResource(
                if (expanded) Res.string.timeline_tool_group_collapse else Res.string.timeline_tool_group_expand,
            ),
        )
    }
}
