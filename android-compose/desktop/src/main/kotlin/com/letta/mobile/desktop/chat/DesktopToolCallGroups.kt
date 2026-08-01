package com.letta.mobile.desktop.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.chat.projection.ChatRenderItem

/**
 * Desktop-side row projection that folds runs of consecutive tool-only
 * assistant messages into a single collapsed group.
 *
 * A busy turn renders as six near-identical "Bash Ran cd …" cards, each with
 * its own timestamp row — the agent's prose (the actual signal) drowns in
 * mechanical noise. Grouping happens HERE, per-platform over the already-built
 * render items, rather than in the shared grouping layer: the shared layer's
 * LazyColumn key / dedupe / streaming-stability invariants are load-bearing for
 * Android too, and this is purely a desktop presentation choice.
 */
@Immutable
sealed interface DesktopChatRow {
    /** Stable LazyColumn key — delegates to the underlying render items. */
    val key: String

    @Immutable
    data class Item(val item: ChatRenderItem) : DesktopChatRow {
        override val key: String = item.key
    }

    /**
     * Two or more consecutive tool-only messages, rendered as one collapsed
     * "N tool calls" card. [singles] preserves list order.
     */
    @Immutable
    data class ToolGroup(val singles: List<ChatRenderItem.Single>) : DesktopChatRow {
        init {
            require(singles.size >= 2) { "ToolGroup needs at least 2 items; got ${singles.size}" }
        }

        // The FIRST member's key verbatim: the group grows at the tail while a
        // turn streams, so the head is the stable end. Not prefixed — a prefix
        // would change the key at the exact moment a lone tool row becomes a
        // pair, unmounting and remounting the visible row (losing disclosure
        // state and the LazyColumn anchor) for a collision that cannot happen:
        // the group REPLACES that first single, so the two never coexist.
        override val key: String = singles.first().key

        val toolCallCount: Int = singles.sumOf { it.message.toolCalls.orEmpty().size }

        /** Newest member timestamp — the group's single clock label. */
        val boundaryTimestamp: String = singles.maxOf { it.boundaryTimestamp }

        /**
         * Folding must never hide a signal the ungrouped rows would have shown.
         * A single [ToolCard] opens itself when its call is still running,
         * failed, or carries a generated image ([shouldInitiallyExpand]); the
         * group inherits that rule, so an in-flight call, a failure, or a
         * generated image is never a collapsed line the user has to discover by
         * clicking. Quiet, finished, successful runs still fold — that is the
         * whole point of the group.
         */
        val startsExpanded: Boolean = singles.any { single ->
            single.message.toolCalls.orEmpty().any { it.shouldInitiallyExpand() }
        }
    }
}

/**
 * True for an assistant message whose ONLY renderable payload is tool calls —
 * no prose, no reasoning, no approvals, no generated UI, no attachments.
 * Only these are safe to fold: anything with prose is conversation, not
 * mechanics, and must stay a standalone row.
 */
private fun ChatRenderItem.isToolOnlySingle(): Boolean {
    val single = this as? ChatRenderItem.Single ?: return false
    val message = single.message
    return message.role == "assistant" &&
        !message.isReasoning &&
        message.content.isBlank() &&
        !message.toolCalls.isNullOrEmpty() &&
        message.generatedUi == null &&
        message.approvalRequest == null &&
        message.approvalResponse == null &&
        message.attachments.isEmpty()
}

/**
 * Fold consecutive tool-only singles (2+) into [DesktopChatRow.ToolGroup]s.
 * Everything else passes through untouched, order preserved. A lone tool-only
 * single stays a normal row — a "group of one" reads as pointless chrome.
 */
fun groupDesktopChatRows(renderItems: List<ChatRenderItem>): List<DesktopChatRow> {
    val out = ArrayList<DesktopChatRow>(renderItems.size)
    var i = 0
    while (i < renderItems.size) {
        if (!renderItems[i].isToolOnlySingle()) {
            out.add(DesktopChatRow.Item(renderItems[i]))
            i++
            continue
        }
        // A group is one run's mechanics. Payload shape alone is not enough:
        // concurrent/background runs interleave in this list, and merging two
        // runs into one card with one timestamp would claim they were one
        // burst of work. The shared projection already treats a run-id change
        // as a boundary; this honours the same boundary.
        val runId = (renderItems[i] as ChatRenderItem.Single).stableRunId
        var j = i
        while (j < renderItems.size &&
            renderItems[j].isToolOnlySingle() &&
            (renderItems[j] as ChatRenderItem.Single).stableRunId == runId
        ) {
            j++
        }
        if (j - i >= 2) {
            @Suppress("UNCHECKED_CAST")
            out.add(DesktopChatRow.ToolGroup(renderItems.subList(i, j).map { it as ChatRenderItem.Single }))
        } else {
            out.add(DesktopChatRow.Item(renderItems[i]))
        }
        i = j
    }
    return out
}

/**
 * The collapsed "N tool calls" row. Collapsed by default — the count keeps
 * ticking up while a turn streams, which is still live feedback; expanding
 * shows the full [ToolCard] per call, exactly what ungrouped rendering showed.
 *
 * Styled as the SAME flat activity row as a single [ToolCard] header — same
 * paddings, glyph size, and type ramp. Tool activity is deliberately a quiet
 * log, not a stack of panels (see ToolCard's doc); a bordered card here made
 * the group visually inconsistent with the singles around it.
 */
@Composable
internal fun DesktopToolGroupCard(group: DesktopChatRow.ToolGroup) {
    // Plain remember keyed on the group: matches ToolCard's own disclosure
    // pattern in this package. Scrolling far away resets to collapsed, which
    // is the desired default anyway.
    var expanded by remember(group.key) { mutableStateOf(group.startsExpanded) }
    val summary = remember(group) { group.toolNameSummary() }
    Column(modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Terminal,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(13.dp),
            )
            Text(
                text = "${group.toolCallCount} tool calls",
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
            Icon(
                imageVector = if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse tool calls" else "Expand tool calls",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(start = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                group.singles.forEach { single ->
                    single.message.toolCalls.orEmpty().forEach { toolCall ->
                        // Same disclosure key the ungrouped row uses, so a card's
                        // open/closed state survives grouping flipping on or off.
                        ToolCard(toolCall = toolCall)
                    }
                }
            }
        }
    }
}

/** "Bash ×5 · Read ×1" — distinct tool names with counts, insertion order. */
private fun DesktopChatRow.ToolGroup.toolNameSummary(): String {
    val counts = LinkedHashMap<String, Int>()
    singles.forEach { single ->
        single.message.toolCalls.orEmpty().forEach { call ->
            counts[call.name] = (counts[call.name] ?: 0) + 1
        }
    }
    return counts.entries.joinToString(" · ") { (name, count) ->
        if (count > 1) "$name ×$count" else name
    }
}
