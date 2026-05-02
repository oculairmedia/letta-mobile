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
    val visibleMessages = attachLatencyMetadata(
        filterMessagesForMode(
            messages = dedupeReasoningAssistantEchoes(messages),
            mode = mode,
        )
    )
    val groupedMessages = groupMessages(
        messages = visibleMessages,
        getRole = { it.role },
        getTimestamp = { it.timestamp },
    )
    val reversed = dedupeGroupedMessagesForLazyKeys(groupedMessages).asReversed()

    return ChatRenderModel(
        visibleMessages = visibleMessages,
        groupedMessages = groupedMessages,
        renderItems = groupMessagesForRender(reversed),
    )
}

private fun attachLatencyMetadata(messages: List<UiMessage>): List<UiMessage> {
    var lastUserAt: Instant? = null
    var assistantLatencyAssignedForTurn = false
    return messages.map { message ->
        when (message.role) {
            "user" -> {
                lastUserAt = message.timestamp.parseInstantOrNull()
                assistantLatencyAssignedForTurn = false
                message
            }
            "assistant" -> {
                val promptAt = lastUserAt
                val responseAt = message.timestamp.parseInstantOrNull()
                val latency = message.latencyMs ?: if (!assistantLatencyAssignedForTurn && promptAt != null && responseAt != null) {
                    Duration.between(promptAt, responseAt).toMillis()
                        .takeIf { it in 0L..30 * 60 * 1000L }
                } else {
                    null
                }
                if (latency != null && !message.isReasoning) assistantLatencyAssignedForTurn = true
                if (latency == message.latencyMs) message else message.copy(latencyMs = latency)
            }
            else -> message
        }
    }
}

private fun String.parseInstantOrNull(): Instant? = runCatching { Instant.parse(this) }.getOrNull()

fun dedupeReasoningAssistantEchoes(messages: List<UiMessage>): List<UiMessage> {
    val result = ArrayList<UiMessage>(messages.size)
    var lastReasoningContent: String? = null
    for (msg in messages) {
        if (msg.isReasoning) {
            lastReasoningContent = msg.content
            result.add(msg)
        } else if (msg.role == "assistant" && msg.content == lastReasoningContent) {
            // Skip assistant message that duplicates the immediately preceding reasoning content.
        } else {
            lastReasoningContent = null
            result.add(msg)
        }
    }
    return result
}

fun filterMessagesForMode(
    messages: List<UiMessage>,
    mode: ChatDisplayMode,
): List<UiMessage> = when (mode) {
    // letta-mobile-5s1n: keep error frames visible in Simple mode so users
    // see when a run aborts instead of watching a silent spinner.
    ChatDisplayMode.Simple -> messages.filter {
        it.role == "user" || (it.role == "assistant" && !it.isReasoning) || it.isError
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
    return groupedMessages.filter { (msg, _) -> seen.add(msg.id) }
}
