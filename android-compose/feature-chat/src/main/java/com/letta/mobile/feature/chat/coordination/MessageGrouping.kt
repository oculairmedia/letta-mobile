package com.letta.mobile.feature.chat.coordination

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
internal sealed interface ChatRenderItem {
    /** Stable LazyColumn `key` — must be unique across the whole list. */
    val key: String

    /**
     * A per-item discriminator that is stable across re-renders and never
     * collides with another render item. Used by [deduplicateRenderKeys] as
     * a suffix when two items would otherwise resolve to the same [key], so
     * a future duplicate run id degrades gracefully (a slightly less-stable
     * slot identity) instead of hard-crashing the LazyColumn with a
     * duplicate-key IllegalArgumentException (letta-mobile-y70m0).
     */
    val stableItemDiscriminator: String

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
        /**
         * The raw server run id behind [stableRunKey], carried verbatim so
         * collapse-state lookups (`runId in collapsedRunIds`) match the
         * [RunBlock] path exactly. We can't recover this from [stableRunKey]
         * via `removePrefix("run-")` because the server id may *itself* start
         * with `run-` (letta-mobile-lkj4r), which normalisation would strip.
         */
        val stableRunId: String? = null,
        /**
         * Set by [deduplicateRenderKeys] only when this item's natural key
         * would collide with another render item. Null in the common case,
         * so normal slot identity is unchanged (letta-mobile-y70m0).
         */
        val keyOverride: String? = null,
    ) : ChatRenderItem {
        override val key: String = keyOverride ?: (stableRunKey ?: "msg-${message.id}")
        override val boundaryTimestamp: String = message.timestamp
        override val stableItemDiscriminator: String = message.id
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
        private val stableKey: String? = null,
        /**
         * Set by [deduplicateRenderKeys] only when this item's natural key
         * would collide with another render item. Null in the common case,
         * so normal slot identity is unchanged (letta-mobile-y70m0).
         */
        val keyOverride: String? = null,
    ) : ChatRenderItem {
        init {
            require(messages.isNotEmpty()) { "RunBlock must contain at least one message" }
        }

        override val key: String = keyOverride ?: (stableKey ?: runKey(runId))

        /**
         * Stable per-item discriminator: the id of the run's first (oldest)
         * message. Distinct render items never share their first message id,
         * so this is a safe global tiebreaker for [deduplicateRenderKeys].
         */
        override val stableItemDiscriminator: String = messages.first().first.id

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
 * Collapse contiguous assistant messages into [ChatRenderItem.RunBlock]
 * entries when they share the same server `runId`. Messages without a
 * `role == "assistant"`, without a run id, or from a different run break the
 * grouping and render as [ChatRenderItem.Single].
 *
 * Input is the **already-reversed** grouped list (newest first), as produced
 * by `ChatScreen`'s `reversed` memo. Output preserves that order: the run
 * block lands at the position of its newest member.
 *
 * Algorithm:
 * 1. Walk the reversed input.
 * 2. For each assistant entry with a non-null runId, accumulate it together
 *    with contiguous assistant neighbours from the same run only.
 * 3. Emit either a `Single` (one-message group) or a `RunBlock`
 *    (multi-message group). For `RunBlock` we re-reverse the accumulator so
 *    the gutter renders oldest→newest top-down.
 *
 * letta-mobile-m772.2
 */
internal fun groupMessagesForRender(
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
        // Greedy walk: collect contiguous assistant messages from the same
        // run only. The RunBlock header says "Run", so crossing runId
        // boundaries makes prior turns look like steps of the current run.
        // Because the input is reversed (newest first), the accumulator is
        // also newest-first; we re-reverse before storing so the RunBlock
        // holds chat order.
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
        val compactedAcc = compactRunBlockEchoes(
            accumulator = acc,
            reversed = reversed,
            olderStartIndex = j,
        )
        if (compactedAcc.size == 1) {
            // Adopt the future RunBlock key when this runId is unique in the
            // snapshot (count == 1 means: just this one Single, no other
            // Single or RunBlock will use `run-$runId`). This keeps the
            // LazyColumn slot stable when a sibling message later arrives and
            // promotes this Single into a RunBlock mid-stream.
            val isUniqueRun = (runIdCounts[runId] ?: 0) == 1
            val stableKey = if (isUniqueRun) runKey(runId) else null
            out.add(
                ChatRenderItem.Single(
                    compactedAcc[0].first,
                    compactedAcc[0].second,
                    stableRunKey = stableKey,
                    stableRunId = if (isUniqueRun) runId else null,
                )
            )
        } else {
            out.add(
                ChatRenderItem.RunBlock(
                    runId = runId,
                    messages = compactedAcc.asReversed(),
                    stableKey = runBlockKey(
                        runId = runId,
                        runIdCounts = runIdCounts,
                        accumulator = compactedAcc,
                    ),
                )
            )
        }
        i = j
    }
    return deduplicateRenderKeys(out)
}

/**
 * letta-mobile-y70m0 (defensive hardening): guarantee that every render
 * item's LazyColumn [ChatRenderItem.key] is globally unique, even if two
 * distinct items legitimately (or buggily) resolve to the same run id.
 *
 * The #337 [runKey] normaliser prevents the `run-run-<id>` double-prefix
 * collision, but it does NOT guarantee uniqueness when two distinct render
 * items map to the same single-prefixed `run-<id>` key. Before this pass a
 * repeated run id crashed the LazyColumn with
 * `IllegalArgumentException: Key "run-<id>" was already used`.
 *
 * We keep the FIRST occurrence's key verbatim (preserving the stable slot
 * identity / #337 behaviour for the common, already-unique case) and only
 * suffix subsequent collisions with their stable per-item discriminator —
 * so a duplicate degrades into a distinct-but-stable slot instead of a hard
 * crash. In a correct snapshot no item is rewritten, so this is a no-op.
 */
internal fun deduplicateRenderKeys(items: List<ChatRenderItem>): List<ChatRenderItem> {
    if (items.size < 2) return items
    val seen = HashSet<String>(items.size)
    var rewroteAny = false
    val out = ArrayList<ChatRenderItem>(items.size)
    for (item in items) {
        if (seen.add(item.key)) {
            out.add(item)
            continue
        }
        // Collision: derive a unique key from the stable per-item id, and
        // keep probing in the (astronomically unlikely) event that even the
        // discriminated key was already used.
        var candidate = "${item.key}#${item.stableItemDiscriminator}"
        var n = 1
        while (!seen.add(candidate)) {
            candidate = "${item.key}#${item.stableItemDiscriminator}#${n++}"
        }
        rewroteAny = true
        out.add(
            when (item) {
                is ChatRenderItem.Single -> item.copy(keyOverride = candidate)
                is ChatRenderItem.RunBlock -> item.copy(keyOverride = candidate)
            }
        )
    }
    return if (rewroteAny) out else items
}

private const val MinRunPanelEchoLength = 24
private val RunPanelWhitespaceRegex = Regex("\\s+")

private fun compactRunBlockEchoes(
    accumulator: List<Pair<UiMessage, GroupPosition>>,
    reversed: List<Pair<UiMessage, GroupPosition>>,
    olderStartIndex: Int,
): List<Pair<UiMessage, GroupPosition>> {
    if (accumulator.size < 2) return accumulator

    val olderPlainAssistantText = buildSet {
        for (index in olderStartIndex until reversed.size) {
            reversed[index].first.runPanelEchoKey()?.let(::add)
        }
    }
    val seenInBlock = HashSet<String>()
    return accumulator.filter { (message, _) ->
        val key = message.runPanelEchoKey() ?: return@filter true
        seenInBlock.add(key) && key !in olderPlainAssistantText
    }.ifEmpty {
        // Never erase an entire run. If the server sent only duplicate text
        // frames, keep the newest one so the conversation still has an anchor.
        listOf(accumulator.first())
    }
}

private fun UiMessage.runPanelEchoKey(): String? {
    if (role != "assistant") return null
    if (isReasoning || isError) return null
    if (!toolCalls.isNullOrEmpty()) return null
    if (generatedUi != null || approvalRequest != null || approvalResponse != null) return null
    if (attachments.isNotEmpty()) return null
    val normalized = content.trim().replace(RunPanelWhitespaceRegex, " ")
    return normalized.takeIf { it.length >= MinRunPanelEchoLength }
}

private fun runBlockKey(
    runId: String,
    runIdCounts: Map<String, Int>,
    accumulator: List<Pair<UiMessage, GroupPosition>>,
): String {
    val matchingMessagesInThisBlock = accumulator.count { (message, _) ->
        message.role == "assistant" && message.runId == runId
    }
    val allMatchingMessagesAreInThisBlock = runIdCounts[runId] == matchingMessagesInThisBlock
    return if (allMatchingMessagesAreInThisBlock) {
        runKey(runId)
    } else {
        "${runKey(runId)}-${accumulator.first().first.id}"
    }
}

/**
 * Build the canonical LazyColumn key for a server run id.
 *
 * Server run ids frequently already carry a `run-` prefix (e.g.
 * `run-80aa0047-…`). Naively doing `"run-$runId"` then produces a
 * **double**-prefixed `run-run-80aa0047-…` key. That doubled key collides
 * with a sibling that derived the single-prefixed `run-80aa0047-…` form
 * (e.g. a unique-runId Single's stable key vs. a RunBlock for the same run),
 * which LazyColumn treats as a fatal duplicate-key crash:
 *
 *   IllegalArgumentException: Key "run-run-<id>" was already used.
 *
 * Normalising here guarantees a single, stable `run-<id>` prefix regardless
 * of whether the server id already had one (letta-mobile-lkj4r).
 */
internal fun runKey(runId: String): String =
    if (runId.startsWith("run-")) runId else "run-$runId"
