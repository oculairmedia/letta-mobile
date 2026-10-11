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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.data.subagents.SubagentRoster
import com.letta.mobile.data.subagents.SubagentRosterItem
import com.letta.mobile.data.subagents.SubagentRosterStatus
import com.letta.mobile.data.subagents.formatSubagentElapsed
import com.letta.mobile.ui.chat.AgentActivity
import com.letta.mobile.ui.chat.AgentActivityOrb
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.customColors

/**
 * letta-mobile-ulcg6 (PR4): the one subagent surface. The desktop Background
 * tasks pane and the Android subagent sheet both render this, from the same
 * [SubagentRoster] projection, so a subagent reads the same on both clients:
 * Running (with its latest activity), Finished (with Clear), and reflection /
 * dream runs tucked under a collapsed Background section.
 *
 * A plain column, not a lazy list, so it nests in a bottom sheet or a
 * scrolling pane; the roster is one conversation's subagents and stays small.
 * Rows are keyed by the subagent's stable key and status changes recolour in
 * place, so nothing shifts under the reader; only a tap (expand a card, show
 * background runs) changes a row's height.
 *
 * [onOpenConversation] null hides "View conversation" (a host that cannot
 * navigate to a subagent's conversation). It is offered only once the child's
 * conversation exists - while running it usually does not yet.
 */
@Composable
fun SubagentRosterList(
    roster: SubagentRoster,
    onOpenConversation: ((SubagentRosterItem) -> Unit)?,
    onClearFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
        if (roster.isEmpty) {
            Text(
                text = "No subagents",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = LettaDimens.Space.lg).testTag(SUBAGENT_ROSTER_EMPTY_TAG),
            )
            return@Column
        }
        RosterSection("Running", roster.running) { item -> RunningCard(item, onOpenConversation) }
        RosterSection("Finished", roster.finished, action = "Clear" to onClearFinished) { item ->
            EndedRow(item, onOpenConversation)
        }
        BackgroundSection(roster.background, onOpenConversation)
    }
}

@Composable
private fun RosterSection(
    title: String,
    items: List<SubagentRosterItem>,
    action: Pair<String, () -> Unit>? = null,
    row: @Composable (SubagentRosterItem) -> Unit,
) {
    if (items.isEmpty()) return
    SectionHeader(title = "$title · ${items.size}", action = action)
    items.forEach { item -> key(item.key) { row(item) } }
}

@Composable
private fun BackgroundSection(items: List<SubagentRosterItem>, onOpenConversation: ((SubagentRosterItem) -> Unit)?) {
    if (items.isEmpty()) return
    var shown by rememberSaveable { mutableStateOf(false) }
    SectionHeader(
        title = "Background · ${items.size}",
        action = (if (shown) "Hide" else "Show") to { shown = !shown },
    )
    if (shown) items.forEach { item -> key(item.key) { EndedRow(item, onOpenConversation) } }
}

@Composable
private fun SectionHeader(title: String, action: Pair<String, () -> Unit>?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = LettaDimens.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (action != null) {
            Text(
                text = action.first,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(role = Role.Button, onClick = action.second),
            )
        }
    }
}

@Composable
private fun RunningCard(item: SubagentRosterItem, onOpenConversation: ((SubagentRosterItem) -> Unit)?) {
    var expanded by remember(item.key) { mutableStateOf(false) }
    val canExpand = item.activity.size > 1
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
                .then(if (canExpand) Modifier.clickable(role = Role.Button) { expanded = !expanded } else Modifier)
                .padding(LettaDimens.Space.md),
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AgentActivityOrb(
                    size = LettaDimens.Orb.sm,
                    activity = if (item.status == SubagentRosterStatus.QUIET) AgentActivity.Idle else AgentActivity.Working,
                )
                TitleBlock(item, modifier = Modifier.weight(1f))
            }
            (if (expanded) item.activity else item.activity.takeLast(1)).forEach { line -> ActivityLine(line) }
            ConversationAction(item, onOpenConversation)
        }
    }
}

@Composable
private fun EndedRow(item: SubagentRosterItem, onOpenConversation: ((SubagentRosterItem) -> Unit)?) {
    val failed = item.status == SubagentRosterStatus.FAILED || item.status == SubagentRosterStatus.CANCELLED
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = LettaDimens.Space.lg * item.depth)
            .testTag(SubagentRosterTag.ENDED.of(item.key)),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (item.status.isTerminal) {
                Icon(
                    imageVector = if (failed) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle,
                    contentDescription = statusWord(item.status),
                    tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.customColors.successColor,
                    modifier = Modifier.size(LettaDimens.Control.icon),
                )
            } else {
                AgentActivityOrb(size = LettaDimens.Control.icon, activity = AgentActivity.Working)
            }
            TitleBlock(item, modifier = Modifier.weight(1f))
        }
        ConversationAction(item, onOpenConversation)
    }
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
            if (item.identity.label != null) IdentityBadge(item)
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

/** External coding agents (Claude Code, Codex) are named, so their work is never mistaken for Letta's. */
@Composable
private fun IdentityBadge(item: SubagentRosterItem) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = item.identity.label.orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = LettaDimens.Space.xs).testTag(SubagentRosterTag.BADGE.of(item.key)),
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
private fun ConversationAction(item: SubagentRosterItem, onOpenConversation: ((SubagentRosterItem) -> Unit)?) {
    if (onOpenConversation == null || !item.canOpenConversation) return
    TextButton(
        onClick = { onOpenConversation(item) },
        modifier = Modifier.testTag(SubagentRosterTag.OPEN.of(item.key)),
    ) {
        Text("View conversation", style = MaterialTheme.typography.labelMedium)
    }
}

/** "General-purpose · 1m 28s · 12 tools · claude-sonnet-5-5 · quiet". */
fun SubagentRosterItem.subtitle(): String {
    val parts = mutableListOf<String>()
    typeLabel.takeIf { it.isNotBlank() }?.let(parts::add)
    if (status.isTerminal) parts += statusWord(status)
    elapsedMs?.let { parts += formatSubagentElapsed(it) }
    toolCallCount?.takeIf { it > 0 }?.let { parts += if (it == 1) "1 tool" else "$it tools" }
    model?.let(parts::add)
    if (status == SubagentRosterStatus.QUIET) parts += "quiet"
    return parts.joinToString(" · ")
}

fun statusWord(status: SubagentRosterStatus): String = STATUS_WORDS.getValue(status)

private val STATUS_WORDS = mapOf(
    SubagentRosterStatus.RUNNING to "Running",
    SubagentRosterStatus.QUIET to "Quiet",
    SubagentRosterStatus.COMPLETED to "Finished",
    SubagentRosterStatus.FAILED to "Failed",
    SubagentRosterStatus.CANCELLED to "Stopped",
)

/** Test tag of the roster's empty state. */
const val SUBAGENT_ROSTER_EMPTY_TAG = "subagent-roster-empty"

/** Test tags shared by both clients' UI tests, one per roster element kind. */
enum class SubagentRosterTag(private val prefix: String) {
    RUNNING("subagent-roster-running"),
    ENDED("subagent-roster-ended"),
    SUBTITLE("subagent-roster-subtitle"),
    BADGE("subagent-roster-badge"),
    OPEN("subagent-roster-open"),
    ;

    fun of(key: String): String = "$prefix-$key"
}
