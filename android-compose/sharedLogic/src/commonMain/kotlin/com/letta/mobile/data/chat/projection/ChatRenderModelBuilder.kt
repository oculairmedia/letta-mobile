package com.letta.mobile.data.chat.projection

import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.timeline.TimelineInstant
import com.letta.mobile.data.timeline.parseTimelineInstantOrNull
import com.letta.mobile.data.timeline.timelineInstantDurationMillis
import com.letta.mobile.ui.common.GroupPosition
import com.letta.mobile.ui.common.groupMessages

/**
 * Display-level message filtering mode for chat rendering.
 *
 * Debug currently uses the same message set as Interactive; it only changes
 * the per-message renderer in ChatScreen.
 */
enum class ChatDisplayMode {
    Simple,
    Interactive,
    Debug,
}

fun String.toChatDisplayMode(): ChatDisplayMode = when (this) {
    "simple" -> ChatDisplayMode.Simple
    "debug" -> ChatDisplayMode.Debug
    else -> ChatDisplayMode.Interactive
}

/**
 * Pure render model consumed by ChatScreen's reverse-layout LazyColumn.
 *
 * [visibleMessages] and [groupedMessages] are in chronological chat order.
 * [renderItems] is newest-first, matching LazyColumn(reverseLayout = true).
 */
data class ChatRenderModel(
    val visibleMessages: List<UiMessage>,
    val groupedMessages: List<Pair<UiMessage, GroupPosition>>,
    val renderItems: List<ChatRenderItem>,
)

fun buildChatRenderModel(
    messages: List<UiMessage>,
    mode: ChatDisplayMode,
    // letta-mobile-c4igq.4: when non-null, drop any message whose owning agentId
    // differs from the active conversation agent BEFORE grouping, so a foreign-
    // agent run block (e.g. a PM-agent run that landed in the list) can never
    // render inside another agent conversation. A null message agentId (legacy/
    // unknown) is kept — we only exclude a PROVEN foreign agent, never same-agent
    // or unscoped history.
    activeAgentId: String? = null,
): ChatRenderModel {
    val agentScoped = if (activeAgentId == null) {
        messages
    } else {
        messages.filter { it.agentId == null || it.agentId == activeAgentId }
    }
    val afterReasoningDedup = dedupeReasoningAssistantEchoes(agentScoped)

    val visibleMessages = attachLatencyMetadata(
        filterMessagesForMode(
            messages = afterReasoningDedup,
            mode = mode,
        )
    )

    val groupedMessages = groupMessages(
        messages = visibleMessages,
        getRole = { it.role },
        getTimestamp = { it.timestamp },
    )

    val reversed = dedupeGroupedMessagesForLazyKeys(groupedMessages).asReversed()

    // letta-mobile-x1xnl: collapse the streaming-RunBlock vs reconciled-final-
    // Single duplicate (same run, adjacent, different server ids) here in the
    // single common chokepoint so EVERY caller — incremental cache, desktop's
    // buildDesktopChatRenderItems, and any other direct render path — is covered
    // (#824 review P2).
    val renderItems = deduplicateRenderItemsByMessageId(groupMessagesForRender(reversed))

    return ChatRenderModel(
        visibleMessages = visibleMessages,
        groupedMessages = groupedMessages,
        renderItems = renderItems,
    )
}

class IncrementalChatRenderItemsCache {
    var fullBuildCount: Int = 0
        private set
    var incrementalBuildCount: Int = 0
        private set

    private var cachedMode: ChatDisplayMode? = null
    private var previousMessages: List<UiMessage> = emptyList()
    private var previousTailStartIndex: Int = -1
    private var committedRenderItems: List<ChatRenderItem> = emptyList()
    // letta-mobile-g87l6: the full list returned on the previous build, keyed by
    // LazyColumn key, so a streaming tick can hand back the SAME instance for
    // items whose content is unchanged (see reuseUnchangedInstances).
    private var previousResultByKey: Map<String, ChatRenderItem> = emptyMap()

    fun renderItems(
        messages: List<UiMessage>,
        mode: ChatDisplayMode,
        change: ChatMessageListChange,
        // letta-mobile-c4igq.4: active conversation agent; forwarded to
        // buildChatRenderModel so foreign-agent messages are scoped out.
        activeAgentId: String? = null,
    ): List<ChatRenderItem> {
        if (messages.isEmpty()) {
            cachedMode = mode
            previousMessages = messages
            previousTailStartIndex = 0
            committedRenderItems = emptyList()
            previousResultByKey = emptyMap()
            return emptyList()
        }

        val tailStartIndex = activeTailStartIndex(messages)
        if (canReuseCommittedHistory(messages, mode, change, tailStartIndex)) {
            val tailRenderItems = buildChatRenderModel(
                messages = messages.subList(tailStartIndex, messages.size),
                mode = mode,
                activeAgentId = activeAgentId,
            ).renderItems
            // letta-mobile-vxase: the tail and the committed history are each
            // deduplicated WITHIN themselves by groupMessagesForRender, but
            // concatenating them can reintroduce a duplicate LazyColumn key
            // when the SAME runId straddles the tail/committed split — the tail
            // emits a RunBlock keyed `run-<runId>` and the committed history
            // already holds one. Two identical keys crash the LazyColumn
            // ("Key was already used"). Re-run the global key de-dupe across
            // the joined list so the boundary can never produce a collision.
            // letta-mobile-x1xnl: collapse a turn that appears as a RunBlock in
            // one half and a standalone Single (a reconciled final with a
            // DIFFERENT server id but the SAME runId) in the other, before the
            // key-level dedup — otherwise the two DIFFERENT keys both render as a
            // stranded duplicate. Then run the key dedup for remaining same-key
            // collisions.
            val rebuilt = deduplicateRenderKeys(
                deduplicateRenderItemsByMessageId(tailRenderItems + committedRenderItems),
            )
            // letta-mobile-g87l6 (desktop -> mobile flicker fix): the committed
            // history items are already reused by identity, but activeTailStartIndex
            // pulls the latest user prompt AND the immediately-preceding assistant
            // turn into the rebuilt active tail. Those bubbles are content-unchanged
            // during streaming, yet buildChatRenderModel gives them a FRESH instance
            // every fanned-out token. A new instance for the same LazyColumn key
            // recomposes the whole subtree — the per-token flicker the desktop's
            // higher frame rate makes visible on mobile. Hand back the SAME instance
            // for any item that is content-equal to the previous build so Compose
            // skips recomposing it; only the item that actually changed (the
            // streaming tail whose text grew) gets a new instance.
            val next = reuseUnchangedInstances(rebuilt)
            cachedMode = mode
            previousMessages = messages
            previousTailStartIndex = tailStartIndex
            previousResultByKey = next.associateBy { it.key }
            incrementalBuildCount++
            return next
        }

        return rebuildFull(messages, mode, tailStartIndex, activeAgentId = activeAgentId)
    }

    private fun canReuseCommittedHistory(
        messages: List<UiMessage>,
        mode: ChatDisplayMode,
        change: ChatMessageListChange,
        tailStartIndex: Int,
    ): Boolean {
        val previous = previousMessages
        if (cachedMode != mode || change == ChatMessageListChange.Full) return false
        if (tailStartIndex != previousTailStartIndex || previous.isEmpty()) return false
        if (tailStartIndex !in previous.indices) return false
        if (messages[tailStartIndex].id != previous[tailStartIndex].id) return false

        return when (change) {
            ChatMessageListChange.AppendTail ->
                messages.size == previous.size + 1 &&
                    messages[messages.size - 2].id == previous.last().id
            ChatMessageListChange.ReplaceTail ->
                messages.size == previous.size &&
                    (messages.size == 1 || messages[messages.size - 2].id == previous[messages.size - 2].id)
            ChatMessageListChange.Full -> false
            // letta-mobile-yflpp: a deduped no-op tick is suppressed upstream
            // by ChatTimelineObserver and never reaches this builder; treat it
            // conservatively as not reusable if it ever does.
            ChatMessageListChange.None -> false
        }
    }

    private fun rebuildFull(
        messages: List<UiMessage>,
        mode: ChatDisplayMode,
        tailStartIndex: Int,
        activeAgentId: String?,
    ): List<ChatRenderItem> {
        // buildChatRenderModel already applies deduplicateRenderItemsByMessageId
        // internally (single chokepoint), so no extra wrap needed here.
        val full = buildChatRenderModel(messages, mode, activeAgentId).renderItems
        committedRenderItems = if (tailStartIndex > 0) {
            buildChatRenderModel(
                messages = messages.subList(0, tailStartIndex),
                mode = mode,
                activeAgentId = activeAgentId,
            ).renderItems
        } else {
            emptyList()
        }
        cachedMode = mode
        previousMessages = messages
        previousTailStartIndex = tailStartIndex
        previousResultByKey = full.associateBy { it.key }
        fullBuildCount++
        return full
    }

    /**
     * letta-mobile-g87l6: replace each freshly-built render item with the
     * previous build's instance when the two are content-equal (ChatRenderItem
     * variants are data classes, so `==` is a deep content compare). This
     * preserves object identity for settled bubbles across a streaming tick so
     * Compose can skip recomposing them, while genuinely-changed items (the
     * growing streaming tail) still get a new instance and recompose normally.
     */
    private fun reuseUnchangedInstances(rebuilt: List<ChatRenderItem>): List<ChatRenderItem> {
        val previousByKey = previousResultByKey
        if (previousByKey.isEmpty()) return rebuilt
        var reusedAny = false
        val reconciled = rebuilt.map { item ->
            val prior = previousByKey[item.key]
            if (prior != null && prior !== item && prior == item) {
                reusedAny = true
                prior
            } else {
                item
            }
        }
        return if (reusedAny) reconciled else rebuilt
    }

    private fun activeTailStartIndex(messages: List<UiMessage>): Int {
        val lastUserIndex = messages.indexOfLast { it.role == "user" }
        var start = if (lastUserIndex <= 0) 0 else lastUserIndex - 1
        val contextRunId = messages[start].runId.takeIf {
            messages[start].role == "assistant" && !it.isNullOrBlank()
        }
        if (contextRunId != null) {
            while (
                start > 0 &&
                messages[start - 1].role == "assistant" &&
                messages[start - 1].runId == contextRunId
            ) {
                start--
            }
        }
        return start
    }
}

private fun attachLatencyMetadata(messages: List<UiMessage>): List<UiMessage> {
    val result = messages.toMutableList()
    var promptAt: TimelineInstant? = null
    val assistantIndices = mutableListOf<Int>()

    fun flushTurn() {
        val turnPromptAt = promptAt
        if (turnPromptAt == null || assistantIndices.isEmpty()) {
            assistantIndices.clear()
            return
        }
        if (assistantIndices.any { result[it].latencyMs != null }) {
            assistantIndices.clear()
            return
        }
        val targetIndex = assistantIndices.lastOrNull { result[it].isTurnLatencyTarget() }
        val target = targetIndex?.let(result::get)
        val responseAt = target?.timestamp?.parseInstantOrNull()
        val latency = responseAt?.let { end ->
            timelineInstantDurationMillis(turnPromptAt, end)
                .takeIf { it in 0L..30 * 60 * 1000L }
        }
        if (targetIndex != null && latency != null) {
            result[targetIndex] = result[targetIndex].copy(latencyMs = latency)
        }
        assistantIndices.clear()
    }

    messages.forEachIndexed { index, message ->
        when (message.role) {
            "user" -> {
                flushTurn()
                promptAt = message.timestamp.parseInstantOrNull()
            }
            "assistant" -> assistantIndices.add(index)
            else -> Unit
        }
    }
    flushTurn()
    return result
}

private fun String.parseInstantOrNull(): TimelineInstant? = parseTimelineInstantOrNull(this)

private fun UiMessage.isTurnLatencyTarget(): Boolean =
    role == "assistant" &&
        !isReasoning &&
        !isError &&
        content.isNotBlank() &&
        toolCalls.isNullOrEmpty() &&
        generatedUi == null &&
        approvalRequest == null &&
        approvalResponse == null &&
        attachments.isEmpty()

fun dedupeReasoningAssistantEchoes(messages: List<UiMessage>): List<UiMessage> {
    val result = ArrayList<UiMessage>(messages.size)
    var lastReasoningContent: String? = null
    for (msg in messages) {
        if (msg.isReasoning) {
            lastReasoningContent = msg.content
            result.add(msg)
        } else if (msg.isPlainAssistantTextEchoOf(lastReasoningContent)) {
            // Skip assistant message that duplicates the immediately preceding reasoning content.
        } else {
            lastReasoningContent = null
            result.add(msg)
        }
    }
    return result
}

private fun UiMessage.isPlainAssistantTextEchoOf(lastReasoningContent: String?): Boolean {
    return role == "assistant" &&
        content == lastReasoningContent &&
        !isReasoning &&
        !isError &&
        toolCalls.isNullOrEmpty() &&
        generatedUi == null &&
        approvalRequest == null &&
        approvalResponse == null &&
        attachments.isEmpty()
}

fun filterMessagesForMode(
    messages: List<UiMessage>,
    mode: ChatDisplayMode,
): List<UiMessage> = when (mode) {
    // letta-mobile-5s1n: keep error frames visible in Simple mode so users
    // see when a run aborts instead of watching a silent spinner.
    // Timeline-backed tool calls intentionally use role="assistant" so they
    // can stay grouped inside assistant run blocks in Interactive/Debug mode;
    // Simple mode must still hide those operational cards just like hydrated
    // history tool messages (role="tool").
    ChatDisplayMode.Simple -> messages.filter {
        it.role == "user" ||
            (it.role == "assistant" && !it.isReasoning && it.toolCalls.isNullOrEmpty()) ||
            it.isError
    }
    ChatDisplayMode.Interactive,
    ChatDisplayMode.Debug -> messages
}

fun dedupeGroupedMessagesForLazyKeys(
    groupedMessages: List<Pair<UiMessage, GroupPosition>>,
): List<Pair<UiMessage, GroupPosition>> {
    // Defensive: LazyColumn crashes on duplicate item keys. mergeOlderMessages
    // already dedupes by id, but a late streaming tick or reasoning-collapse
    // edge case could still leak duplicates — so guard in the pure pipeline too.
    val seen = HashSet<String>(groupedMessages.size)
    val idDeduped = groupedMessages.filter { (msg, _) -> seen.add(msg.id) }
    // Secondary content-based dedupe — guard against the
    // optimistic-Local-survives-Confirmed race documented in
    // ClientModeSendCoordinator.reconcileClientModeConversation (letta-mobile-a7ij).
    // When SSE wins the race and `postHandlerCollapse` misses fusing the
    // Confirmed with its matching Local, the two events carry *different*
    // ids — one server-issued, one optimistic ("client-…" / "cm-…" /
    // "client-user-initial-duplicate-…") — so the id-based dedupe above
    // can't catch them and the user sees a duplicated bubble.
    //
    // Strategy: only collapse a pair when (a) same role, (b) same trimmed
    // content (after envelope-reminder stripping done upstream), (c) at
    // least one side has an optimistic-id prefix. Constraint (c) makes the
    // pass strictly safer than blanket content-dedup: two server-issued
    // ids with identical content (legitimate quick-fire repeats) are
    // preserved.
    val contentDeduped = dedupeOptimisticContentTwins(idDeduped)
    return contentDeduped
}

/**
 * Optimistic-id prefixes minted by the Client Mode timeline reducer
 * (`ClientModeTimelineStreamReducer`), the conversation coordinator's
 * fresh-route bootstrap (`ChatConversationCoordinator`), and the
 * Letta `newOtid()` helper in `core/data/timeline/Timeline.kt`.
 *
 * Anything bearing one of these prefixes is a candidate for fuzzy
 * collapse against an adjacent server-issued twin.
 */
private val OptimisticIdPrefixes = listOf("client-", "cm-")

private fun String.isOptimisticUiMessageId(): Boolean =
    OptimisticIdPrefixes.any { startsWith(it) }

/**
 * Collapse adjacent (role, content)-identical pairs when at least one
 * side carries an optimistic id. Preserves the server-issued copy when
 * available so downstream consumers (notifications, latency metadata)
 * latch onto the confirmed identity.
 */
fun dedupeOptimisticContentTwins(
    grouped: List<Pair<UiMessage, GroupPosition>>,
): List<Pair<UiMessage, GroupPosition>> {
    if (grouped.size < 2) return grouped
    val out = ArrayList<Pair<UiMessage, GroupPosition>>(grouped.size)
    for (entry in grouped) {
        val prev = out.lastOrNull()
        if (prev != null && shouldCollapseOptimisticTwin(prev.first, entry.first)) {
            // Keep the confirmed (non-optimistic) side; if both are
            // optimistic keep the earlier one for stable ordering.
            val keep = when {
                !prev.first.id.isOptimisticUiMessageId() -> prev
                !entry.first.id.isOptimisticUiMessageId() -> entry
                else -> prev
            }
            out[out.lastIndex] = keep
        } else {
            out.add(entry)
        }
    }
    return out
}

private fun shouldCollapseOptimisticTwin(a: UiMessage, b: UiMessage): Boolean {
    if (a.role != b.role) return false
    if (a.content.isBlank() || b.content.isBlank()) return false
    if (a.content.trim() != b.content.trim()) return false
    // Both reasoning or both not — never collapse a reasoning bubble
    // into a final assistant message; that's a separate dedup pass
    // (`dedupeReasoningAssistantEchoes`) which handles ordering + truncation.
    if (a.isReasoning != b.isReasoning) return false
    if (a.isError != b.isError) return false
    if (!a.toolCalls.isNullOrEmpty() || !b.toolCalls.isNullOrEmpty()) return false
    // At least one side must be optimistic — otherwise this is a legitimate
    // server-confirmed repeat and we leave it alone.
    return a.id.isOptimisticUiMessageId() || b.id.isOptimisticUiMessageId()
}
