package com.letta.mobile.data.chat.projection

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.model.SyntheticSkillEnvelopeDetector
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.common.GroupPosition
import kotlinx.atomicfu.atomic

/** LazyColumn-stable identity: prefer client otid across Pending → Confirmed. */
internal fun UiMessage.stableListKey(): String {
    val base = clientMessageId?.takeIf { it.isNotBlank() } ?: id
    // Reasoning and assistant events can share an otid without a runId; keep
    // LazyColumn keys distinct so both rows survive.
    return if (isReasoning) "reasoning:$base" else base
}


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
 *
 * letta-mobile-fqxo2 (F2): marked @Immutable so the Compose compiler can
 * skip the per-item composables (MeasuredChatRenderItem / RunBlock /
 * RenderChatMessage) when a render item is unchanged across a recompose.
 * Render items are built once per render-model build and never mutated;
 * their fields are value types (UiMessage is @Immutable, GroupPosition is
 * an enum), so the @Immutable promise holds even though RunBlock.messages
 * is a raw List (migrating that to ImmutableList is tracked separately).
 */
@Immutable
sealed interface ChatRenderItem {
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
    @Immutable
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
        override val key: String = keyOverride ?: (stableRunKey ?: "msg-${message.stableListKey()}")
        override val boundaryTimestamp: String = message.timestamp
        override val stableItemDiscriminator: String = message.stableListKey()
        override fun containsMessageId(messageId: String): Boolean =
            message.id == messageId || message.clientMessageId == messageId
    }

    /**
     * A contiguous run of assistant messages sharing the same [runId].
     * Rendered as a single LazyColumn item with a timeline gutter via the
     * `RunBlock` composable. [messages] is in **chat order** (oldest first
     * within the run) so the gutter renders top→bottom correctly even when
     * the outer list is reversed.
     */
    @Immutable
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

    // letta-mobile-p0gc (ANR fix): echo compaction used to rebuild the
    // "older plain assistant text" set from scratch for EVERY run block,
    // regex-normalizing each older message once per block — superlinear
    // O(blocks × messages) on long conversations (Pixel ANR:
    // runPanelEchoKey → compactRunBlockEchoes with 1k+ history). Now:
    //
    //   1. Each message's echo key is normalized AT MOST ONCE per build
    //      into [echoKeys] (single-pass whitespace normalization — no Regex).
    //   2. [lastEchoKeyIndex] records the LAST index each key occurs at.
    //      For a block spanning [i, j), a key is shadowed by older history
    //      iff `lastEchoKeyIndex[key] >= j` — exactly equivalent to
    //      membership in the old per-block `olderPlainAssistantText` set,
    //      but computed with ONE pre-pass instead of one set-build per block.
    //
    // Total cost: O(n) normalizations + O(n) map operations per build.
    val echoIndex = buildEchoCompactionIndex(reversed)

    val out = ArrayList<ChatRenderItem>(reversed.size)
    var i = 0
    while (i < reversed.size) {
        val (msg, pos) = reversed[i]

        // letta-mobile-45e2k: synthetic skill-instruction envelopes are
        // backend/model context, not user-visible prose. Filter them from
        // presentation so they never render as bubbles or special chips.
        // The canonical skill tool call (assistant TOOL_CALL event) remains
        // and renders through the normal UiToolCall card path.
        if (msg.role == "user" && SyntheticSkillEnvelopeDetector.isSyntheticSkillEnvelope("user", msg.content)) {
            i++
            continue
        }

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
            blockStartIndex = i,
            olderStartIndex = j,
            echoKeys = echoIndex.keys,
            lastEchoKeyIndex = echoIndex.lastIndexByKey,
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
/**
 * letta-mobile-x1xnl: collapse render items that render the SAME underlying
 * assistant message id twice.
 *
 * The incremental builder concatenates a freshly-built tail with a cached
 * committed-history list. During the Single<->RunBlock transition a message can
 * end up as a standalone [ChatRenderItem.Single] (key `msg-<id>`) in one half
 * and inside a [ChatRenderItem.RunBlock] (key `run-<runId>`) in the other. The
 * keys DIFFER, so [deduplicateRenderKeys] never sees a collision and BOTH items
 * render — the on-screen stranded duplicate (the message list count stays
 * correct; only the render expands one message into two visible items).
 *
 * A RunBlock is the promoted/canonical form, so when a message id is owned by a
 * RunBlock we drop any standalone Single for that same id. We also drop a later
 * Single that repeats an id an earlier Single already rendered. Order is
 * preserved and RunBlocks are never dropped.
 */
fun deduplicateRenderItemsByMessageId(items: List<ChatRenderItem>): List<ChatRenderItem> {
    if (items.size < 2) return items
    // Message ids owned by ANY RunBlock — a message can never legitimately render
    // twice, so this collapse is always safe regardless of position.
    val runBlockMessageIds = HashSet<String>()
    for (item in items) {
        if (item is ChatRenderItem.RunBlock) {
            item.messages.forEach { runBlockMessageIds.add(it.first.id) }
        }
    }
    // #824 review (P1): the runId collapse must be ADJACENCY-scoped, not global.
    // The streaming reply renders as a RunBlock (key run-<runId>) and the SAME
    // turn's reconciled final renders as a standalone assistant Single with a
    // DIFFERENT server id (key msg-<newId>) IMMEDIATELY next to it — that is the
    // stranded duplicate. But a conversation can legitimately have another
    // assistant Single from the SAME run elsewhere in history (a run split by
    // user turns); dropping that by runId globally would delete real messages.
    // So only drop an assistant Single that is directly adjacent (prev or next)
    // to a RunBlock carrying the same runId.
    fun runBlockRunIdAt(idx: Int): String? =
        (items.getOrNull(idx) as? ChatRenderItem.RunBlock)?.runId?.takeIf { it.isNotBlank() }

    var droppedAny = false
    val out = ArrayList<ChatRenderItem>(items.size)
    for ((idx, item) in items.withIndex()) {
        when (item) {
            is ChatRenderItem.Single -> {
                val id = item.message.id
                // #824 review (P2): only ASSISTANT Singles belong to a run's
                // RunBlock. Other renderables (e.g. ERROR frames mapped to role
                // "system" by TimelineEventToUiMessage) can carry the same runId
                // but are distinct bubbles — never collapse them by runId.
                val runId = item.message.runId
                    ?.takeIf { it.isNotBlank() && item.message.role == "assistant" }
                val adjacentSameRunBlock = runId != null &&
                    (runId == runBlockRunIdAt(idx - 1) || runId == runBlockRunIdAt(idx + 1))
                if (id in runBlockMessageIds || adjacentSameRunBlock) {
                    droppedAny = true
                    continue
                }
                out.add(item)
            }
            is ChatRenderItem.RunBlock -> out.add(item)
        }
    }
    return if (droppedAny) out else items
}

fun deduplicateRenderKeys(items: List<ChatRenderItem>): List<ChatRenderItem> {
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

private data class EchoCompactionIndex(
    val keys: Array<String?>,
    val lastIndexByKey: Map<String, Int>,
)

private fun buildEchoCompactionIndex(
    reversed: List<Pair<UiMessage, GroupPosition>>,
): EchoCompactionIndex {
    val keys = arrayOfNulls<String>(reversed.size)
    val lastIndexByKey = HashMap<String, Int>()
    reversed.forEachIndexed { index, entry ->
        val key = entry.first.runPanelEchoKey()
        keys[index] = key
        if (key != null) lastIndexByKey[key] = index
    }
    return EchoCompactionIndex(keys, lastIndexByKey)
}

/**
 * letta-mobile-p0gc (ANR fix): per-build instrumentation for the echo-key
 * hot path. [normalizationCount] counts how many times a message's content
 * was whitespace-normalized into a [runPanelEchoKey] during
 * [groupMessagesForRender] builds since the last reset. The linear
 * implementation normalizes each eligible message AT MOST ONCE per build,
 * so the count is bounded by the number of eligible messages; the retired
 * per-block implementation re-normalized every older plain assistant once
 * PER RUN BLOCK (superlinear). Test-only hook — never read on production
 * render paths.
 */
internal object EchoCompactionInstrumentation {
    private val normalizationCount = atomic(0L)

    fun recordNormalization() {
        normalizationCount.incrementAndGet()
    }

    val normalizations: Long get() = normalizationCount.value

    fun resetForTest() {
        normalizationCount.value = 0L
    }
}

/**
 * Collapse duplicate "echoed" plain assistant text inside one run block.
 *
 * A message is an echo candidate when [runPanelEchoKey] yields a key (plain
 * assistant prose ≥ 24 normalized chars, no tools/reasoning/errors/…).
 * Within the block the FIRST occurrence (newest, because input is reversed)
 * is kept; any later duplicate is dropped — as is any candidate whose text
 * already appears in OLDER history (indices ≥ [olderStartIndex]).
 *
 * letta-mobile-p0gc (ANR fix): signature changed to consume the build-scoped
 * [echoKeys] memo + [lastEchoKeyIndex] map computed ONCE per
 * [groupMessagesForRender] call. `lastEchoKeyIndex[key] >= olderStartIndex`
 * is logically identical to `key in olderPlainAssistantText` from the
 * previous implementation: the key occurs somewhere at index ≥
 * olderStartIndex iff its LAST occurrence is at index ≥ olderStartIndex.
 */
private fun compactRunBlockEchoes(
    accumulator: List<Pair<UiMessage, GroupPosition>>,
    blockStartIndex: Int,
    olderStartIndex: Int,
    echoKeys: Array<String?>,
    lastEchoKeyIndex: Map<String, Int>,
): List<Pair<UiMessage, GroupPosition>> {
    if (accumulator.size < 2) return accumulator

    val seenInBlock = HashSet<String>()
    var droppedAny = false
    val out = ArrayList<Pair<UiMessage, GroupPosition>>(accumulator.size)
    for ((offset, entry) in accumulator.withIndex()) {
        val key = echoKeys[blockStartIndex + offset]
        if (key == null) {
            out.add(entry)
            continue
        }
        if (!seenInBlock.add(key) || (lastEchoKeyIndex[key] ?: -1) >= olderStartIndex) {
            droppedAny = true
            continue
        }
        out.add(entry)
    }
    return if (!droppedAny) accumulator else out.ifEmpty {
        // Never erase an entire run. If the server sent only duplicate text
        // frames, keep the newest one so the conversation still has an anchor.
        listOf(accumulator.first())
    }
}

/**
 * The exact set of chars JVM `Regex("\\s+")` matches without
 * UNICODE_CHARACTER_CLASS: space, tab, LF, VT, FF, CR. Kept explicit (instead
 * of Char.isWhitespace()) so normalization output stays byte-identical to the
 * retired regex implementation across platforms.
 */
private fun isJvmRegexWhitespace(c: Char): Boolean =
    c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r'

/**
 * Equivalent to `content.trim().replace(RunPanelWhitespaceRegex, " ")`
 * (`\s+` → single space after trimming), but single-pass with NO Regex
 * allocation — this runs once per eligible assistant message per render-model
 * build on the Main thread (letta-mobile-p0gc ANR fix).
 */
internal fun normalizeRunPanelEchoText(content: String): String {
    var start = 0
    val len = content.length
    var end = len
    while (start < end && content[start].isWhitespace()) start++
    while (end > start && content[end - 1].isWhitespace()) end--
    var sb: StringBuilder? = null
    var i = start
    while (i < end) {
        val c = content[i]
        if (isJvmRegexWhitespace(c)) {
            if (sb == null) sb = StringBuilder(end - start).append(content, start, i)
            // Consume the whole whitespace run; emit exactly one space.
            while (i < end && isJvmRegexWhitespace(content[i])) i++
            sb.append(' ')
        } else {
            sb?.append(c)
            i++
        }
    }
    return sb?.toString() ?: content.substring(start, end)
}

private fun UiMessage.runPanelEchoKey(): String? {
    if (role != "assistant") return null
    if (isReasoning || isError) return null
    if (!toolCalls.isNullOrEmpty()) return null
    if (generatedUi != null || approvalRequest != null || approvalResponse != null) return null
    if (attachments.isNotEmpty()) return null
    EchoCompactionInstrumentation.recordNormalization()
    val normalized = normalizeRunPanelEchoText(content)
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
fun runKey(runId: String): String =
    if (runId.startsWith("run-")) runId else "run-$runId"
