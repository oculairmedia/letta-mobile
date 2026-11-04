package com.letta.mobile.ui.chat.subagents

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.subagents.SubagentRoster
import com.letta.mobile.data.subagents.SubagentRosterItem
import com.letta.mobile.data.subagents.SubagentRosterStatus
import com.letta.mobile.data.subagents.projectSubagentRoster
import com.letta.mobile.ui.components.list.RosterList
import com.letta.mobile.ui.components.list.RosterListTag
import com.letta.mobile.ui.components.list.RosterOptions
import com.letta.mobile.ui.components.list.RosterSelection
import com.letta.mobile.ui.components.list.RosterSpec
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.util.time.TimeLabels
import kotlin.time.Clock

/** Opens a subagent's own conversation from the roster. */
typealias OpenSubagentConversation = (SubagentRosterItem) -> Unit

/**
 *  (PR4), on the shared RosterList since the one
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
    onDismiss: ((SubagentRosterItem) -> Unit)? = null,
    dismissableToolCallIds: Set<String> = emptySet(),
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
        val dismiss = onDismiss?.takeIf { !line.item.status.isTerminal && line.item.toolCallId in dismissableToolCallIds }
        if (line.card) RunningCard(line.item, onOpenConversation, dismiss) else EndedRow(line.item, row, onOpenConversation, dismiss)
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
    onDismiss: ((SubagentEntry) -> Unit)? = null,
) {
    var clearedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    // The roster stays open while subagents stream status updates, so keep the projection keyed
    // to its inputs instead of re-projecting per recomposition.
    val roster = remember(entries, clearedKeys) { projectSubagentRoster(entries, now(), clearedKeys) }
    val dismissable = entries.groupBy { it.toolCallId }.filter { (tool, matches) ->
        val entry = matches.singleOrNull()
        tool.isNotBlank() && entry != null && !entry.parentAgentId.isNullOrBlank() && !entry.parentConversationId.isNullOrBlank()
    }.mapValues { it.value.single() }
    SubagentRosterList(
        roster = roster,
        dismissableToolCallIds = dismissable.keys,
        onDismiss = onDismiss?.let { action ->
            { item: SubagentRosterItem -> dismissable[item.toolCallId]?.let(action); Unit }
        },
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

@Composable
private fun EmptyRoster(modifier: Modifier) {
    Text(
        text = "No subagents",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(vertical = LettaDimens.Space.lg).testTag(SUBAGENT_ROSTER_EMPTY_TAG),
    )
}

private val SUBAGENT_ROSTER_LIST = RosterListTag("subagent-roster")

/** "General-purpose · 1m 28s · 12 tools · claude-sonnet-5-5 · quiet". */
fun SubagentRosterItem.subtitle(): String {
    val parts = mutableListOf<String>()
    typeLabel.takeIf { it.isNotBlank() }?.let(parts::add)
    if (status.isTerminal) parts += statusWord(status)
    elapsedMs?.let { parts += TimeLabels.duration(it) }
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
