package com.letta.mobile.ui.screens.chat

import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.common.GroupPosition
import com.letta.mobile.ui.common.groupMessages
import java.time.Duration
import java.time.Instant

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

private const val ChatRenderModelDebugLogging = false

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
): ChatRenderModel {
    val afterReasoningDedup = dedupeReasoningAssistantEchoes(messages)

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

    val renderItems = groupMessagesForRender(reversed)

    if (ChatRenderModelDebugLogging) {
        val inputCount = messages.size
        val renderItemCount = renderItems.size
        val seenKeys = HashSet<String>(renderItems.size)
        val dupKeys = renderItems.filterNot { seenKeys.add(it.key) }.map { it.key }
        if (dupKeys.isNotEmpty()) {
            android.util.Log.w(
                "ChatRenderModel-DEBUG",
                "DUP_RENDER_KEYS: ${dupKeys.size} duplicates: ${dupKeys.take(5)} renderItems=$renderItemCount",
            )
        }

        val dedupDropPercent = if (inputCount > 0) {
            ((inputCount - renderItemCount).toFloat() / inputCount * 100).toInt()
        } else 0
        if (dedupDropPercent > 20) {
            android.util.Log.w(
                "ChatRenderModel-DEBUG",
                "RENDER_MODEL_DEDUP_DROP: input=$inputCount renderItems=$renderItemCount drop=$dedupDropPercent%",
            )
        }
    }

    return ChatRenderModel(
        visibleMessages = visibleMessages,
        groupedMessages = groupedMessages,
        renderItems = renderItems,
    )
}

private fun attachLatencyMetadata(messages: List<UiMessage>): List<UiMessage> {
    val result = messages.toMutableList()
    var promptAt: Instant? = null
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
            Duration.between(turnPromptAt, end).toMillis()
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

private fun String.parseInstantOrNull(): Instant? = runCatching { Instant.parse(this) }.getOrNull()

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
    val idDropped = groupedMessages.size - idDeduped.size
    if (ChatRenderModelDebugLogging && idDropped > 0) {
        android.util.Log.w(
            "ChatRenderModel-DEBUG",
            "KEY_DEDUP_DROPPED: $idDropped duplicate message IDs detected in grouped messages",
        )
    }

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
    val totalDropped = groupedMessages.size - contentDeduped.size
    if (ChatRenderModelDebugLogging && totalDropped > idDropped) {
        android.util.Log.w(
            "ChatRenderModel-DEBUG",
            "CONTENT_DEDUP_DROPPED: ${totalDropped - idDropped} optimistic content-twins collapsed",
        )
    }
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
internal fun dedupeOptimisticContentTwins(
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
