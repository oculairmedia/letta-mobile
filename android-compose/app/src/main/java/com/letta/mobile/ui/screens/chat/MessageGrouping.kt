package com.letta.mobile.ui.screens.chat

import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.common.GroupPosition

/**
 * A renderable item in the chat list. The chat used to be a flat
 * `List<Pair<UiMessage, GroupPosition>>` rendered one bubble per
 * LazyColumn item. With the run-block refactor (letta-mobile-m772.2), assistant
 * messages that share a server `runId` collapse into a single
 * [RunBlock] item with a timeline gutter — so the LazyColumn now consumes
 * `List<ChatRenderItem>` instead.
 *
 * The list is in **reverse order** (newest first) when consumed by
 * `ChatScreen` because the LazyColumn uses `reverseLayout = true`. The
 * `groupMessagesForRender` builder accepts a reversed input list and
 * preserves that order in the output, so callers don't need to re-reverse.
 *
 * letta-mobile-m772.2
 */
sealed interface ChatRenderItem {
    /** Stable LazyColumn `key` — must be unique across the whole list. */
    val key: String

    /**
     * The newest timestamp inside this item. Used by `ChatScreen` to decide
     * whether a date separator should be inserted between adjacent items.
     */
    val boundaryTimestamp: String

    /** True if [messageId] appears anywhere inside this render item. */
    fun containsMessageId(messageId: String): Boolean

    /**
     * A single, stand-alone message bubble.
     *
     * The [stableRunKey] hint lets the grouper preemptively use the
     * `run-$runId` key for an assistant Single whose runId is unique in the
     * current snapshot. This keeps the LazyColumn slot identity stable across
     * the Single → RunBlock transition that happens mid-stream when a sibling
     * message in the same run arrives (letta-mobile-w9l3). Without this,
     * Compose unmounts the Single and remounts a RunBlock on the next frame,
     * producing a visible flash.
     *
     * `stableRunKey` is null in two cases: (a) the message has no runId
     * (user messages, untagged assistants) — falls back to `msg-${id}`; or
     * (b) the same runId appears in multiple non-contiguous Singles in this
     * snapshot, where adopting `run-$runId` would cause duplicate keys and
     * crash the LazyColumn. In case (b) we keep `msg-${id}` for safety.
     */
    data class Single(
        val message: UiMessage,
        val groupPosition: GroupPosition,
        val stableRunKey: String? = null,
    ) : ChatRenderItem {
        override val key: String = stableRunKey ?: "msg-${message.id}"
        override val boundaryTimestamp: String = message.timestamp
        override fun containsMessageId(messageId: String): Boolean =
            message.id == messageId
    }

    /**
     * A contiguous run of assistant messages sharing the same [runId].
     * Rendered as a single LazyColumn item with a timeline gutter via the
     * `RunBlock` composable. [messages] is in **chat order** (oldest first
     * within the run) so the gutter renders top→bottom correctly even when
     * the outer list is reversed.
     */
    data class RunBlock(
        val runId: String,
        val messages: List<Pair<UiMessage, GroupPosition>>,
    ) : ChatRenderItem {
        init {
            require(messages.isNotEmpty()) { "RunBlock must contain at least one message" }
        }

        override val key: String = "run-$runId"

        /**
         * Newest message timestamp in the run. The reversed input to
         * [groupMessagesForRender] means the *first* element of [messages]
         * is the newest one (we re-reverse internally to get chat order),
         * but for date-separator boundary purposes we want the newest.
         */
        override val boundaryTimestamp: String =
            messages.maxOf { it.first.timestamp }

        override fun containsMessageId(messageId: String): Boolean =
            messages.any { it.first.id == messageId }
    }
}

/**
 * Collapse adjacent assistant messages that share a non-null `runId` into
 * [ChatRenderItem.RunBlock] entries. Messages without a runId — or messages
 * whose runId only appears once in the contiguous neighbourhood — render as
 * [ChatRenderItem.Single].
 *
 * Input is the **already-reversed** grouped list (newest first), as produced
 * by `ChatScreen`'s `reversed` memo. Output preserves that order: the run
 * block lands at the position of its newest member.
 *
 * Algorithm:
 * 1. Walk the reversed input.
 * 2. For each entry with a non-null assistant runId, accumulate it together
 *    with any contiguous neighbours sharing that same runId.
 * 3. Emit either a `Single` (one-message group) or a `RunBlock`
 *    (multi-message group). For `RunBlock` we re-reverse the accumulator so
 *    the gutter renders oldest→newest top-down.
 *
 * letta-mobile-m772.2
 */
fun groupMessagesForRender(
    reversed: List<Pair<UiMessage, GroupPosition>>,
): List<ChatRenderItem> {
    if (reversed.isEmpty()) return emptyList()

    // letta-mobile-w9l3: pre-scan to count how often each assistant runId
    // appears. A runId that occurs exactly once across the entire snapshot is
    // a candidate for the "stable run key" optimisation on its Single — we
    // can safely key it by `run-$runId` because no other item will adopt the
    // same key. If the runId occurs more than once (non-contiguous Singles,
    // or a soon-to-be RunBlock), Singles must keep `msg-${id}` so we don't
    // collide with the RunBlock's `run-$runId` or with another Single.
    val runIdCounts = HashMap<String, Int>()
    for ((msg, _) in reversed) {
        val rid = msg.runId.takeIf { msg.role == "assistant" && !it.isNullOrBlank() }
            ?: continue
        runIdCounts[rid] = (runIdCounts[rid] ?: 0) + 1
    }

    val out = ArrayList<ChatRenderItem>(reversed.size)
    var i = 0
    while (i < reversed.size) {
        val (msg, pos) = reversed[i]
        val runId = msg.runId.takeIf { msg.role == "assistant" && !it.isNullOrBlank() }
        if (runId == null) {
            out.add(ChatRenderItem.Single(msg, pos))
            i++
            continue
        }
        // Greedy walk: collect every contiguous entry whose assistant runId
        // matches. Because the input is reversed (newest first), the
        // accumulator is also newest-first; we re-reverse before storing so
        // the RunBlock holds chat order.
        val acc = ArrayList<Pair<UiMessage, GroupPosition>>()
        var j = i
        while (j < reversed.size) {
            val (m, p) = reversed[j]
            if (m.role == "assistant" && m.runId == runId) {
                acc.add(m to p)
                j++
            } else {
                break
            }
        }
        if (acc.size == 1) {
            // Adopt the future RunBlock key when this runId is unique in the
            // snapshot (count == 1 means: just this one Single, no other
            // Single or RunBlock will use `run-$runId`). This keeps the
            // LazyColumn slot stable when a sibling message later arrives and
            // promotes this Single into a RunBlock mid-stream.
            val stableKey = if ((runIdCounts[runId] ?: 0) == 1) "run-$runId" else null
            out.add(ChatRenderItem.Single(acc[0].first, acc[0].second, stableRunKey = stableKey))
        } else {
            out.add(ChatRenderItem.RunBlock(runId = runId, messages = acc.asReversed()))
        }
        i = j
    }
    return out
}
