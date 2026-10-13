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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
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
import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.subagents.SubagentRoster
import com.letta.mobile.data.subagents.SubagentRosterItem
import com.letta.mobile.data.subagents.SubagentRosterStatus
import com.letta.mobile.data.subagents.formatSubagentElapsed
import com.letta.mobile.data.subagents.projectSubagentRoster
import com.letta.mobile.ui.chat.AgentActivity
import com.letta.mobile.ui.chat.AgentActivityOrb
import com.letta.mobile.ui.components.LettaListRowSpec
import com.letta.mobile.ui.components.list.RosterList
import com.letta.mobile.ui.components.list.RosterListRow
import com.letta.mobile.ui.components.list.RosterListTag
import com.letta.mobile.ui.components.list.RosterOptions
import com.letta.mobile.ui.components.list.RosterRowSlots
import com.letta.mobile.ui.components.list.RosterRowState
import com.letta.mobile.ui.components.list.RosterSection
import com.letta.mobile.ui.components.list.RosterSelection
import com.letta.mobile.ui.components.list.RosterSpec
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.customColors
import kotlin.time.Clock

/** Opens a subagent's own conversation from the roster. */
typealias OpenSubagentConversation = (SubagentRosterItem) -> Unit

/**
 * letta-mobile-ulcg6 (PR4), on the shared RosterList since letta-mobile-ckmub.3.17: the one
 * subagent surface. The Background tasks pane and the subagent sheet both render this, from the
 * same [SubagentRoster] projection, so a subagent reads the same on every client: Running (cards
 * with their latest activity), Finished (with Clear), and reflection / dream runs tucked under a
 * collapsed Background section. Ended rows are [RosterListRow]s; a row that has a conversation
 * opens it.
 *
 * Rows are keyed by the subagent's stable key and status changes recolour in place, so nothing
 * shifts under the reader; only a tap (expand a card, show background runs) changes a row's height.
 * It is a lazy list: a host gives it a bounded height (the pane fills, the sheet caps it).
 *
 * [onOpenConversation] null hides "View conversation" (a host that cannot navigate to a subagent's
 * conversation). It is offered only once the child's conversation exists - while running it
 * usually does not yet.
 */
@Composable
fun SubagentRosterList(
    roster: SubagentRoster,
    onOpenConversation: OpenSubagentConversation?,
    onClearFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (roster.isEmpty) {
        EmptyRoster(modifier)
        return
    }
    var backgroundShown by rememberSaveable { mutableStateOf(false) }
    val sections = subagentRosterSections(roster, backgroundShown, SubagentRosterHeaderActions(onClearFinished) { backgroundShown = !backgroundShown })
    RosterList(
        spec = RosterSpec(
            sections = sections,
            key = { it.item.key },
            selection = RosterSelection(onSelect = { line -> if (line.item.canOpenConversation) onOpenConversation?.invoke(line.item) }),
            options = RosterOptions(testTag = SUBAGENT_ROSTER_LIST, spacing = LettaDimens.Space.sm),
        ),
        modifier = modifier.fillMaxWidth(),
    ) { line, row ->
        if (line.card) RunningCard(line.item, onOpenConversation) else EndedRow(line.item, row, onOpenConversation)
    }
}

/**
 * The roster of [entries] with its own Clear: what the Background tasks pane and the subagent
 * sheet list, projected once per change of [entries].
 */
@Composable
fun SubagentRoster(
    entries: List<SubagentEntry>,
    onOpenConversation: OpenSubagentConversation?,
    modifier: Modifier = Modifier,
    now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    var clearedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    // The roster stays open while subagents stream status updates, so keep the projection keyed
    // to its inputs instead of re-projecting per recomposition.
    val roster = remember(entries, clearedKeys) { projectSubagentRoster(entries, now(), clearedKeys) }
    SubagentRosterList(
        roster = roster,
        onOpenConversation = onOpenConversation,
        onClearFinished = { clearedKeys = clearedKeys + roster.finished.map { it.key } },
        modifier = modifier,
    )
}

/** Opens this row's conversation through [open] when both its agent and conversation are known. */
fun SubagentRosterItem.openWith(open: (agentId: String, conversationId: String) -> Unit) {
    val agentId = childAgentId ?: return
    val conversationId = childConversationId ?: return
    open(agentId, conversationId)
}

/** One roster row: a running [card] (activity lines) or an ended row. */
@Immutable
private data class SubagentRosterLine(val item: SubagentRosterItem, val card: Boolean)

/** The section headers' actions: Finished's Clear and Background's Show / Hide. */
private class SubagentRosterHeaderActions(val onClearFinished: () -> Unit, val onToggleBackground: () -> Unit)

private fun subagentRosterSections(
    roster: SubagentRoster,
    backgroundShown: Boolean,
    actions: SubagentRosterHeaderActions,
): List<RosterSection<SubagentRosterLine>> = buildList {
    if (roster.running.isNotEmpty()) {
        add(RosterSection("running", roster.running.map { SubagentRosterLine(it, card = true) }, title = "Running · ${roster.running.size}"))
    }
    if (roster.finished.isNotEmpty()) {
        add(
            RosterSection(
                "finished",
                roster.finished.map { SubagentRosterLine(it, card = false) },
                title = "Finished · ${roster.finished.size}",
                headerEnd = { SectionAction(CLEAR_LABEL, actions.onClearFinished) },
            ),
        )
    }
    if (roster.background.isNotEmpty()) {
        val shown = if (backgroundShown) roster.background else emptyList()
        add(
            RosterSection(
                "background",
                shown.map { SubagentRosterLine(it, card = false) },
                title = "Background · ${roster.background.size}",
                headerEnd = { SectionAction(if (backgroundShown) HIDE_LABEL else SHOW_LABEL, actions.onToggleBackground) },
            ),
        )
    }
}

@Composable
private fun EmptyRoster(modifier: Modifier) {
    Text(
        text = "No subagents",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(vertical = LettaDimens.Space.lg).testTag(SUBAGENT_ROSTER_EMPTY_TAG),
    )
}

@Composable
private fun SectionAction(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
    )
}

private const val CLEAR_LABEL = "Clear"
private const val SHOW_LABEL = "Show"
private const val HIDE_LABEL = "Hide"
private val SUBAGENT_ROSTER_LIST = RosterListTag("subagent-roster")

@Composable
private fun RunningCard(item: SubagentRosterItem, onOpenConversation: OpenSubagentConversation?) {
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
        }
    }
}

/** An ended (or background) run: the shared roster row, its status glyph leading and its conversation trailing. */
@Composable
private fun EndedRow(item: SubagentRosterItem, row: RosterRowState, onOpenConversation: OpenSubagentConversation?) {
    RosterListRow(
        spec = LettaListRowSpec(title = item.title, subtitle = item.subtitle(), trailing = item.identity.label),
        row = row,
        slots = RosterRowSlots(
            leading = { StatusGlyph(item.status) },
            end = { ConversationAction(item, onOpenConversation) },
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
            IdentityBadge(item)
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
    val label = item.identity.label ?: return
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = label,
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
private fun ConversationAction(item: SubagentRosterItem, onOpenConversation: OpenSubagentConversation?) {
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
