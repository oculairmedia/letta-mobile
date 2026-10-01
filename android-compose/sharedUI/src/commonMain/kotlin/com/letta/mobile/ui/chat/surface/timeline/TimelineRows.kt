package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.UiToolCall
import kotlinx.datetime.LocalDate

/**
 * letta-mobile-bglj6.1: one row the timeline's LazyColumn holds.
 *
 * Lifted from desktop's DesktopChatRow. Tool-call folding and day dividers are presentation
 * choices layered over the shared render items, not part of the shared grouping layer, whose key
 * and dedupe invariants the paged route also depends on.
 */
@Immutable
internal sealed interface TimelineRow {
    /** Stable LazyColumn key. */
    val key: String

    @Immutable
    data class Item(val item: ChatRenderItem) : TimelineRow {
        override val key: String get() = item.key
    }

    /** "Today" / "Yesterday" / "March 4": the start of a local day. */
    @Immutable
    data class DayDivider(val date: LocalDate) : TimelineRow {
        override val key: String = "__day__$date"
    }

    /**
     * Two or more consecutive tool-only messages of one run, folded into one "N tool calls" row.
     * [singles] is in chat order (oldest first).
     */
    @Immutable
    data class ToolGroup(val singles: List<ChatRenderItem.Single>) : TimelineRow {
        init {
            require(singles.size >= 2) { "ToolGroup needs at least 2 items; got ${singles.size}" }
        }

        /**
         * The OLDEST member's key verbatim: a streaming turn grows the group at its newest end, so
         * the oldest is the stable one, and the group replaces that single, so the two never
         * coexist. Keeping it means a lone tool row becoming a pair keeps its LazyColumn slot.
         */
        override val key: String = singles.first().key

        val toolCallCount: Int = singles.sumOf { it.message.toolCalls.orEmpty().size }

        /** Newest member timestamp: the group's one clock. */
        val boundaryTimestamp: String = singles.maxOf { it.boundaryTimestamp }

        /** Folding never hides a running call, a failure or a generated image. */
        val startsExpanded: Boolean = singles.any { single ->
            single.message.toolCalls.orEmpty().any(UiToolCall::needsAttention)
        }

        /** "Bash ×5 · Read" - distinct tool names with counts, in first-seen order. */
        val toolNameCounts: List<Pair<String, Int>> by lazy {
            val counts = LinkedHashMap<String, Int>()
            singles.forEach { single ->
                single.message.toolCalls.orEmpty().forEach { call -> counts[call.name] = (counts[call.name] ?: 0) + 1 }
            }
            counts.entries.map { it.key to it.value }
        }
    }
}

private val DoneToolStatuses = setOf("completed", "success", "ok")

/** A call still running (no done status), or one carrying a generated image, wants to be seen. */
internal fun UiToolCall.needsAttention(): Boolean =
    generatedImageAttachments.isNotEmpty() || status?.lowercase() !in DoneToolStatuses

/**
 * Only assistant messages whose ONLY payload is tool calls fold. Anything with prose, reasoning,
 * approvals, generated UI or attachments is conversation and stays its own row.
 */
private fun ChatRenderItem.isToolOnlySingle(): Boolean {
    val message = (this as? ChatRenderItem.Single)?.message ?: return false
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
 * Folds consecutive tool-only singles of the SAME run (2+) into [TimelineRow.ToolGroup]s. Input
 * and output are in chat order (oldest first). Lifted from desktop's groupDesktopChatRows.
 */
internal fun groupToolCallRows(chatOrderItems: List<ChatRenderItem>): List<TimelineRow> {
    val out = ArrayList<TimelineRow>(chatOrderItems.size)
    var i = 0
    while (i < chatOrderItems.size) {
        val end = toolRunEnd(chatOrderItems, i)
        if (end - i >= 2) {
            out += TimelineRow.ToolGroup(chatOrderItems.subList(i, end).map { it as ChatRenderItem.Single })
            i = end
        } else {
            out += TimelineRow.Item(chatOrderItems[i])
            i++
        }
    }
    return out
}

/** Exclusive end of the run of same-run tool-only singles starting at [start]. */
private fun toolRunEnd(items: List<ChatRenderItem>, start: Int): Int {
    if (!items[start].isToolOnlySingle()) return start + 1
    val runId = (items[start] as ChatRenderItem.Single).stableRunId
    var end = start
    while (end < items.size && items[end].isToolOnlySingle() &&
        (items[end] as ChatRenderItem.Single).stableRunId == runId
    ) {
        end++
    }
    return end
}

internal fun TimelineRow.timestampOrNull(): String? = when (this) {
    is TimelineRow.Item -> item.boundaryTimestamp
    is TimelineRow.ToolGroup -> boundaryTimestamp
    is TimelineRow.DayDivider -> null
}

internal fun ChatRenderItem.isUserPrompt(): Boolean =
    this is ChatRenderItem.Single && message.role == "user"

internal fun TimelineRow.isUserPrompt(): Boolean = this is TimelineRow.Item && item.isUserPrompt()
