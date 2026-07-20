package com.letta.mobile.feature.chat.screen

import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.chat.runtime.ChatViewportSnapshot
import com.letta.mobile.data.model.UiMessage
import kotlin.math.abs
import androidx.compose.foundation.lazy.LazyListState

internal data class ActivePromptState(
    val promptItem: ChatRenderItem.Single,
    val renderIndex: Int,
    val lazyIndex: Int,
)

internal data class ChatAutoScrollSignature(
    val messageId: String,
    val role: String,
    val contentLength: Int,
    val contentHash: Int,
    val latencyMs: Long?,
    val toolCallsHash: Int,
    val generatedUiHash: Int,
    val approvalHash: Int,
    val attachmentCount: Int,
)

internal enum class ChatAutoScrollAction {
    Animate,
    Snap,
    Skip,
}

internal data class AutoScrollActionInput(
    val signature: ChatAutoScrollSignature,
    val isStreaming: Boolean,
    val firstVisibleItemIndex: Int,
    val firstVisibleItemScrollOffset: Int,
    val lastStreamingSnapMs: Long,
    val nowMs: Long,
)

internal const val StreamingAutoScrollSnapThrottleMs = 96L

internal fun chatRenderItemSeesLiveScale(
    isPinching: Boolean,
    scaleWindowIndexRange: IntRange,
    itemIndex: Int,
): Boolean = !isPinching || scaleWindowIndexRange.isEmpty() || itemIndex in scaleWindowIndexRange

internal fun autoScrollAction(input: AutoScrollActionInput): ChatAutoScrollAction {
    if (!input.isStreaming || input.signature.role != "assistant") return ChatAutoScrollAction.Animate
    if (input.firstVisibleItemIndex != 0) return ChatAutoScrollAction.Animate
    if (abs(input.firstVisibleItemScrollOffset) > 12) return ChatAutoScrollAction.Animate
    if (input.nowMs - input.lastStreamingSnapMs < StreamingAutoScrollSnapThrottleMs) {
        return ChatAutoScrollAction.Skip
    }
    return ChatAutoScrollAction.Snap
}

internal fun autoScrollAction(
    signature: ChatAutoScrollSignature,
    isStreaming: Boolean,
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
    lastStreamingSnapMs: Long,
    nowMs: Long,
): ChatAutoScrollAction = autoScrollAction(
    AutoScrollActionInput(
        signature = signature,
        isStreaming = isStreaming,
        firstVisibleItemIndex = firstVisibleItemIndex,
        firstVisibleItemScrollOffset = firstVisibleItemScrollOffset,
        lastStreamingSnapMs = lastStreamingSnapMs,
        nowMs = nowMs,
    ),
)

internal fun shouldForceScrollOnUserSend(
    signature: ChatAutoScrollSignature,
    previousNewestMessageId: String?,
): Boolean {
    if (signature.role != "user") return false
    return signature.messageId != previousNewestMessageId
}

internal fun newestMessageAutoScrollSignature(messages: List<UiMessage>): ChatAutoScrollSignature? {
    val newest = messages.lastOrNull() ?: return null
    return ChatAutoScrollSignature(
        messageId = newest.id,
        role = newest.role,
        contentLength = newest.content.length,
        contentHash = newest.content.hashCode(),
        latencyMs = newest.latencyMs,
        toolCallsHash = newest.toolCalls?.hashCode() ?: 0,
        generatedUiHash = newest.generatedUi?.hashCode() ?: 0,
        approvalHash = 31 * (newest.approvalRequest?.hashCode() ?: 0) +
            (newest.approvalResponse?.hashCode() ?: 0),
        attachmentCount = newest.attachments.size,
    )
}

internal fun calculateLazyIndexForRenderItem(
    targetRenderIndex: Int,
    renderItems: List<ChatRenderItem>,
): Int {
    var lazyIndex = 1
    for (j in 0 until targetRenderIndex) {
        lazyIndex++ // message item
        val prevDate = renderItems.getOrNull(j + 1)?.boundaryTimestamp?.take(10)
        val curDate = renderItems[j].boundaryTimestamp.take(10)
        if (prevDate != null && prevDate != curDate) lazyIndex++
    }
    return lazyIndex
}

internal fun LazyListState.toChatViewportSnapshot(
    isUserScrolling: Boolean,
    itemCount: Int,
): ChatViewportSnapshot {
    return ChatViewportSnapshot(
        totalItems = itemCount,
        lastVisibleIndex = (itemCount - 1 - firstVisibleItemIndex).takeIf { itemCount > 0 },
        isUserScrolling = isUserScrolling,
    )
}
