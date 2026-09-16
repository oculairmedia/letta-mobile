package com.letta.mobile.feature.chat.screen

import androidx.compose.foundation.lazy.LazyListState
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.chat.runtime.ChatViewportSnapshot
import com.letta.mobile.data.model.UiMessage
import kotlin.math.abs

@JvmInline
internal value class ChatMessageRole(val raw: String)

@JvmInline
internal value class LazyFirstVisibleIndex(val value: Int)

@JvmInline
internal value class LazyScrollOffsetPx(val value: Int)

@JvmInline
internal value class StreamingSnapTimestampMs(val value: Long)

@JvmInline
internal value class AutoScrollClockMs(val value: Long)

internal data class ChatAutoScrollSignature(
    val messageId: String,
    val role: ChatMessageRole,
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
    val firstVisibleItemIndex: LazyFirstVisibleIndex,
    val firstVisibleItemScrollOffset: LazyScrollOffsetPx,
    val lastStreamingSnapMs: StreamingSnapTimestampMs,
    val nowMs: AutoScrollClockMs,
)

internal object ChatMessageRoles {
    val User = ChatMessageRole("user")
    val Assistant = ChatMessageRole("assistant")
}

internal const val StreamingAutoScrollSnapThrottleMs = 96L

internal fun chatRenderItemSeesLiveScale(
    isPinching: Boolean,
    scaleWindowIndexRange: IntRange,
    itemIndex: Int,
): Boolean = !isPinching || scaleWindowIndexRange.isEmpty() || itemIndex in scaleWindowIndexRange

internal fun autoScrollAction(input: AutoScrollActionInput): ChatAutoScrollAction {
    if (!input.isStreaming || input.signature.role != ChatMessageRoles.Assistant) {
        return ChatAutoScrollAction.Animate
    }
    if (input.firstVisibleItemIndex.value != 0) return ChatAutoScrollAction.Animate
    if (abs(input.firstVisibleItemScrollOffset.value) > 12) return ChatAutoScrollAction.Animate
    if (input.nowMs.value - input.lastStreamingSnapMs.value < StreamingAutoScrollSnapThrottleMs) {
        return ChatAutoScrollAction.Skip
    }
    return ChatAutoScrollAction.Snap
}

internal fun shouldForceScrollOnUserSend(
    signature: ChatAutoScrollSignature,
    previousNewestMessageId: String?,
): Boolean {
    if (signature.role != ChatMessageRoles.User) return false
    return signature.messageId != previousNewestMessageId
}

internal fun newestMessageAutoScrollSignature(messages: List<UiMessage>): ChatAutoScrollSignature? {
    val newest = messages.lastOrNull() ?: return null
    return ChatAutoScrollSignature(
        messageId = newest.id,
        role = ChatMessageRole(newest.role),
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

/**
 * Snapshot of a chat [LazyListState] projected into the per-message domain that
 * [ChatViewportFollowPolicy] reasons about.
 *
 * `totalItems` is the number of *render* items (chat messages, not date
 * headers). The mapping from "visible lazy index" to "visible render index"
 * is computed here so that policy decisions are made on a consistent axis.
 *
 * `lastVisibleIndex` is the render-item index of the newest visible message.
 * `dateHeaderOffset` counts how many of the lazy items between the head and
 * the first visible render item are date headers, so that the
 * `lastVisibleIndex >= totalItems - 1 - threshold` comparison in
 * [ChatViewportFollowPolicy.isNearLatest] is not tripped by a one-message
 * scroll that only happens to cross a date boundary.
 */
internal fun LazyListState.toChatViewportSnapshot(
    isUserScrolling: Boolean,
    renderItems: List<ChatRenderItem>,
): ChatViewportSnapshot {
    val renderItemCount = renderItems.size
    val lazyItemCount = layoutInfo.totalItemsCount
    val visibleDateHeadersBeforeFirstRender = countDateHeadersBeforeFirstRender(
        renderItems = renderItems,
    )
    // Lazy index 1 is always render item 0 (calculateLazyIndexForRenderItem
    // returns 1 for target=0). The H date headers above `firstVisibleItemIndex`
    // occupy H of the indices between 1 and the first visible index, so the
    // first visible *render* index is firstVisibleItemIndex - 1 - H.
    val firstVisibleRenderIndex = (firstVisibleItemIndex - 1 - visibleDateHeadersBeforeFirstRender)
        .coerceAtLeast(0)
    return ChatViewportSnapshot(
        totalItems = renderItemCount,
        lastVisibleIndex = (renderItemCount - 1 - firstVisibleRenderIndex)
            .takeIf { renderItemCount > 0 && lazyItemCount > 0 },
        isUserScrolling = isUserScrolling,
        dateHeaderOffset = visibleDateHeadersBeforeFirstRender,
    )
}

/**
 * Counts how many of the lazy items at indices `[0, firstVisibleItemIndex)`
 * are date headers rather than chat messages, given the same render-items
 * ordering used to build the lazy list. Mirrors the offset logic in
 * [calculateLazyIndexForRenderItem].
 */
private fun LazyListState.countDateHeadersBeforeFirstRender(
    renderItems: List<ChatRenderItem>,
): Int {
    if (renderItems.isEmpty() || firstVisibleItemIndex <= 0) return 0
    var lazyIndex = 1 // matches calculateLazyIndexForRenderItem's starting offset
    var dateHeaders = 0
    for (i in renderItems.indices) {
        val prevDate = renderItems.getOrNull(i + 1)?.boundaryTimestamp?.take(10)
        val curDate = renderItems[i].boundaryTimestamp.take(10)
        if (prevDate != null && prevDate != curDate) {
            if (lazyIndex >= firstVisibleItemIndex) return dateHeaders
            dateHeaders++
            lazyIndex++
        }
        if (lazyIndex >= firstVisibleItemIndex) return dateHeaders
        lazyIndex++
    }
    return dateHeaders
}
