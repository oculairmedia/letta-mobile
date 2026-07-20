package com.letta.mobile.feature.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.feature.chat.screen.AutoScrollActionInput
import com.letta.mobile.feature.chat.screen.AutoScrollClockMs
import com.letta.mobile.feature.chat.screen.ChatAutoScrollAction
import com.letta.mobile.feature.chat.screen.ChatAutoScrollSignature
import com.letta.mobile.feature.chat.screen.ChatMessageRole
import com.letta.mobile.feature.chat.screen.LazyFirstVisibleIndex
import com.letta.mobile.feature.chat.screen.LazyScrollOffsetPx
import com.letta.mobile.feature.chat.screen.StreamingSnapTimestampMs
import com.letta.mobile.feature.chat.screen.autoScrollAction
import com.letta.mobile.ui.chat.render.ChatMessageGeometryBucket
import com.letta.mobile.ui.chat.render.ChatRenderItemGeometrySignature
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.chatGeometrySignature
import com.letta.mobile.ui.common.GroupPosition
import kotlinx.collections.immutable.persistentSetOf

internal fun scrollTestSignature(role: String, messageId: String = "m1"): ChatAutoScrollSignature =
    ChatAutoScrollSignature(
        messageId = messageId,
        role = ChatMessageRole(role),
        contentLength = 0,
        contentHash = 0,
        latencyMs = null,
        toolCallsHash = 0,
        generatedUiHash = 0,
        approvalHash = 0,
        attachmentCount = 0,
    )

internal fun scrollTestAutoScrollAction(
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
        firstVisibleItemIndex = LazyFirstVisibleIndex(firstVisibleItemIndex),
        firstVisibleItemScrollOffset = LazyScrollOffsetPx(firstVisibleItemScrollOffset),
        lastStreamingSnapMs = StreamingSnapTimestampMs(lastStreamingSnapMs),
        nowMs = AutoScrollClockMs(nowMs),
    ),
)

internal fun scrollTestSingle(
    id: String,
    ts: String = "2026-04-20T12:00:00Z",
    content: String = id,
): ChatRenderItem.Single = ChatRenderItem.Single(
    message = scrollTestMessage(id = id, ts = ts, content = content),
    groupPosition = GroupPosition.None,
)

internal fun scrollTestRunBlock(
    runId: String,
    ts: String,
): ChatRenderItem.RunBlock = ChatRenderItem.RunBlock(
    runId = runId,
    messages = listOf(scrollTestMessage(id = "$runId-message", ts = ts) to GroupPosition.None),
)

internal fun scrollTestMessage(
    id: String,
    ts: String = "2026-04-20T12:00:00Z",
    content: String = id,
    isReasoning: Boolean = false,
    role: String = "assistant",
) = UiMessage(
    id = id,
    role = role,
    content = content,
    timestamp = ts,
    isReasoning = isReasoning,
)

internal fun scrollTestGeometrySignature(
    renderKey: String = "msg-assistant",
    content: String,
    widthPx: Int = 320,
): ChatRenderItemGeometrySignature =
    ChatRenderItemGeometrySignature(
        bucket = ChatMessageGeometryBucket(
            renderKey = renderKey,
            widthPx = widthPx,
            densityBucket = 2000,
            fontScaleBucket = 1000,
            chatFontScaleBucket = 1000,
            layoutDirection = LayoutDirection.Ltr,
            chatMode = "interactive",
            expansionHash = 17,
        ),
        contentLength = content.length,
        contentHash = content.hashCode(),
    )

internal fun ChatRenderItem.scrollTestGeometrySignature(
    state: ChatUiState = ChatUiState(),
    widthPx: Int = 320,
    activeFontScale: Float = 1f,
    layoutDirection: LayoutDirection = LayoutDirection.Ltr,
): ChatRenderItemGeometrySignature =
    chatGeometrySignature(
        state = state,
        chatMode = "interactive",
        widthPx = widthPx,
        density = Density(density = 2f, fontScale = 1f),
        layoutDirection = layoutDirection,
        activeFontScale = activeFontScale,
    )

internal val scrollTestChatBackgroundSolid = com.letta.mobile.ui.theme.ChatBackground.SolidColor(Color(0xFF123456), "Custom")
