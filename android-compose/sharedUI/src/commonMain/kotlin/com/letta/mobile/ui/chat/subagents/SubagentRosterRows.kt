package com.letta.mobile.ui.chat.subagents

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.data.subagents.SubagentRosterItem
import com.letta.mobile.data.subagents.SubagentRosterStatus
import com.letta.mobile.ui.chat.AgentActivity
import com.letta.mobile.ui.chat.AgentActivityOrb
import com.letta.mobile.ui.components.row.LettaListRowSpec
import com.letta.mobile.ui.components.list.RosterListRow
import com.letta.mobile.ui.components.list.RosterRowSlots
import com.letta.mobile.ui.components.list.RosterRowState
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.customColors

@Composable
internal fun RunningCard(
    item: SubagentRosterItem,
    onOpenConversation: OpenSubagentConversation?,
    onDismiss: ((SubagentRosterItem) -> Unit)?,
) {
    var expanded by remember(item.key) { mutableStateOf(false) }
    val canExpand = item.activity.size > 1
    val expandToggle = if (canExpand) Modifier.clickable(role = Role.Button) { expanded = !expanded } else Modifier
    val orbActivity = if (item.status == SubagentRosterStatus.QUIET) AgentActivity.Idle else AgentActivity.Working
    val visibleLines = if (expanded) item.activity else item.activity.takeLast(1)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = LettaDimens.Space.lg * item.depth)
            .testTag(SubagentRosterTag.RUNNING.of(item.key)),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(LettaDimens.Stroke.hairline, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .then(expandToggle)
                .padding(LettaDimens.Space.md),
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AgentActivityOrb(size = LettaDimens.Orb.sm, activity = orbActivity)
                TitleBlock(item, modifier = Modifier.weight(1f))
            }
            visibleLines.forEach { line -> ActivityLine(line) }
            ConversationAction(item, onOpenConversation)
            if (onDismiss != null) TextButton(onClick = { onDismiss(item) }) { Text("Dismiss") }
        }
    }
}

/** An ended (or background) run: the shared roster row, its status glyph leading and its conversation trailing. */
@Composable
internal fun EndedRow(
    item: SubagentRosterItem,
    row: RosterRowState,
    onOpenConversation: OpenSubagentConversation?,
    onDismiss: ((SubagentRosterItem) -> Unit)?,
) {
    RosterListRow(
        spec = LettaListRowSpec(title = item.title, subtitle = item.subtitle(), trailing = item.identity.label),
        row = row,
        slots = RosterRowSlots(
            leading = { StatusGlyph(item.status) },
            end = {
                ConversationAction(item, onOpenConversation)
                if (onDismiss != null) TextButton(onClick = { onDismiss(item) }) { Text("Dismiss") }
            },
        ),
        modifier = Modifier
            .padding(start = LettaDimens.Space.lg * item.depth)
            .testTag(SubagentRosterTag.ENDED.of(item.key)),
    )
}

/** A check or an error mark once ended; a background run still going keeps its orb. */
@Composable
private fun StatusGlyph(status: SubagentRosterStatus) {
    if (!status.isTerminal) {
        AgentActivityOrb(size = LettaDimens.Control.icon, activity = AgentActivity.Working)
        return
    }
    val failed = status == SubagentRosterStatus.FAILED || status == SubagentRosterStatus.CANCELLED
    val icon = if (failed) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle
    val tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.customColors.successColor
    Icon(
        imageVector = icon,
        contentDescription = statusWord(status),
        tint = tint,
        modifier = Modifier.size(LettaDimens.Control.icon),
    )
}

@Composable
private fun TitleBlock(item: SubagentRosterItem, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.hair)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            ExternalAgentBadge(item.identity, testTag = SubagentRosterTag.BADGE.of(item.key))
        }
        Text(
            text = item.subtitle(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag(SubagentRosterTag.SUBTITLE.of(item.key)),
        )
    }
}

@Composable
private fun ActivityLine(text: String) {
    Text(
        text = "› $text",
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ConversationAction(item: SubagentRosterItem, onOpenConversation: OpenSubagentConversation?) {
    if (onOpenConversation == null || !item.canOpenConversation) return
    TextButton(
        onClick = { onOpenConversation(item) },
        modifier = Modifier.testTag(SubagentRosterTag.OPEN.of(item.key)),
    ) {
        Text("View conversation", style = MaterialTheme.typography.labelMedium)
    }
}
