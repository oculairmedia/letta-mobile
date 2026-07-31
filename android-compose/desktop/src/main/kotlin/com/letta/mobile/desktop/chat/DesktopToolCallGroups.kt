package com.letta.mobile.desktop.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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

        // Keyed off the FIRST member: the group grows at the tail while a turn
        // streams, so the head is the stable end. Prefixed so it can never
        // collide with the member's own key if grouping flips off for a frame.
        override val key: String = "toolgroup-${singles.first().key}"

        val toolCallCount: Int = singles.sumOf { it.message.toolCalls.orEmpty().size }

        /** Newest member timestamp — the group's single clock label. */
        val boundaryTimestamp: String = singles.maxOf { it.boundaryTimestamp }
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
        var j = i
        while (j < renderItems.size && renderItems[j].isToolOnlySingle()) j++
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
 * The collapsed "N tool calls" card. Collapsed by default — the count keeps
 * ticking up while a turn streams, which is still live feedback; expanding
 * shows the full [ToolCard] per call, exactly what ungrouped rendering showed.
 */
@Composable
internal fun DesktopToolGroupCard(group: DesktopChatRow.ToolGroup) {
    // Plain remember keyed on the group: matches ToolCard's own disclosure
    // pattern in this package. Scrolling far away resets to collapsed, which
    // is the desired default anyway.
    var expanded by remember(group.key) { mutableStateOf(false) }
    val summary = remember(group) { group.toolNameSummary() }
    Surface(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Terminal,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = "${group.toolCallCount} tool calls",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse tool calls" else "Expand tool calls",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
            if (expanded) {
                Column(
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    group.singles.forEach { single ->
                        single.message.toolCalls.orEmpty().forEachIndexed { index, toolCall ->
                            ToolCard(
                                toolCall = toolCall,
                                disclosureKey = toolCall.toolCallId ?: "${single.message.id}:$index",
                            )
                        }
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
