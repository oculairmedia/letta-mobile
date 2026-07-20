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
import com.letta.mobile.feature.chat.screen.calculateLazyIndexForRenderItem
import com.letta.mobile.ui.chat.render.ChatMessageGeometryBucket
import com.letta.mobile.ui.chat.render.ChatMessageGeometryState
import com.letta.mobile.ui.chat.render.ChatRenderItemGeometrySignature
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.chatGeometrySignature
import com.letta.mobile.ui.common.GroupPosition
import org.junit.Assert.assertEquals

internal data class ScrollTestMessageRole(
    val value: String = "assistant",
    val isReasoning: Boolean = false,
)

internal data class ScrollTestMessageSpec(
    val id: String,
    val ts: String = "2026-04-20T12:00:00Z",
    val content: String = id,
    val role: ScrollTestMessageRole = ScrollTestMessageRole(),
)

internal data class ScrollTestLazyViewport(
    val firstVisibleItemIndex: Int = 0,
    val firstVisibleItemScrollOffset: Int = 0,
) {
    companion object {
        val pinned = ScrollTestLazyViewport()
        fun scrolledUpIndex(index: Int = 1) = ScrollTestLazyViewport(firstVisibleItemIndex = index)
        fun scrolledUpOffset(offset: Int) = ScrollTestLazyViewport(firstVisibleItemScrollOffset = offset)
    }
}

internal data class ScrollTestAutoScrollTiming(
    val nowMs: Long,
    val lastStreamingSnapMs: Long,
) {
    companion object {
        fun throttled(nowMs: Long = 1000L) = ScrollTestAutoScrollTiming(nowMs, nowMs - 50L)
        fun readyToSnap(nowMs: Long = 1000L) = ScrollTestAutoScrollTiming(nowMs, nowMs - 100L)
        fun streamingPinned(nowMs: Long = 120L) = ScrollTestAutoScrollTiming(nowMs, 0L)
        fun streamingThrottled(nowMs: Long = 150L) = ScrollTestAutoScrollTiming(nowMs, 100L)
    }
}

internal data class ScrollTestAutoScrollCase(
    val signature: ChatAutoScrollSignature = scrollTestSignature(),
    val isStreaming: Boolean = true,
    val viewport: ScrollTestLazyViewport = ScrollTestLazyViewport.pinned,
    val timing: ScrollTestAutoScrollTiming = ScrollTestAutoScrollTiming.readyToSnap(),
)

internal data class ScrollTestAutoScrollInput(
    val signature: ChatAutoScrollSignature,
    val isStreaming: Boolean,
    val viewport: ScrollTestLazyViewport,
    val timing: ScrollTestAutoScrollTiming,
)

internal data class ScrollTestLazyIndexExpectation(
    val expected: Int,
    val targetRenderIndex: Int,
    val items: List<ChatRenderItem>,
)

internal data class ScrollTestGeometryMeasurement(
    val signature: ChatRenderItemGeometrySignature,
    val heightPx: Int,
    val isStreaming: Boolean,
)

internal data class GeometryFloorAssertion(
    val signature: ChatRenderItemGeometrySignature,
    val isStreaming: Boolean,
    val expected: Int,
) {
    fun verify(harness: ScrollTestGeometryHarness) {
        assertEquals(expected, harness.floor(signature, isStreaming = isStreaming))
    }
}

internal class ScrollTestGeometryHarness(
    maxEntries: Int = 8,
) {
    val state = ChatMessageGeometryState(maxEntries = maxEntries)

    fun record(measurement: ScrollTestGeometryMeasurement) {
        state.recordMeasuredHeight(
            signature = measurement.signature,
            heightPx = measurement.heightPx,
            isStreaming = measurement.isStreaming,
        )
    }

    fun floor(signature: ChatRenderItemGeometrySignature, isStreaming: Boolean): Int =
        state.heightFloorFor(signature, isStreaming = isStreaming)
}

internal fun scrollTestSignature(role: String = "assistant", messageId: String = "m1"): ChatAutoScrollSignature =
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

internal fun scrollTestAutoScrollAction(input: ScrollTestAutoScrollInput): ChatAutoScrollAction =
    autoScrollAction(
        AutoScrollActionInput(
            signature = input.signature,
            isStreaming = input.isStreaming,
            firstVisibleItemIndex = LazyFirstVisibleIndex(input.viewport.firstVisibleItemIndex),
            firstVisibleItemScrollOffset = LazyScrollOffsetPx(input.viewport.firstVisibleItemScrollOffset),
            lastStreamingSnapMs = StreamingSnapTimestampMs(input.timing.lastStreamingSnapMs),
            nowMs = AutoScrollClockMs(input.timing.nowMs),
        ),
    )

internal fun scrollTestAutoScrollAction(case: ScrollTestAutoScrollCase): ChatAutoScrollAction =
    scrollTestAutoScrollAction(
        ScrollTestAutoScrollInput(
            signature = case.signature,
            isStreaming = case.isStreaming,
            viewport = case.viewport,
            timing = case.timing,
        ),
    )

internal fun assertAutoScrollAction(
    expected: ChatAutoScrollAction,
    case: ScrollTestAutoScrollCase,
) {
    assertEquals(expected, scrollTestAutoScrollAction(case))
}

internal data class AutoScrollExpectation(
    val expected: ChatAutoScrollAction,
    val case: ScrollTestAutoScrollCase,
)

internal fun assertAutoScrollExpectations(vararg expectations: AutoScrollExpectation) {
    expectations.forEach { assertAutoScrollAction(it.expected, it.case) }
}

internal fun assertLazyIndexForRenderItem(expectation: ScrollTestLazyIndexExpectation) {
    assertEquals(
        expectation.expected,
        calculateLazyIndexForRenderItem(
            targetRenderIndex = expectation.targetRenderIndex,
            renderItems = expectation.items,
        ),
    )
}

internal fun assertAllLazyIndexExpectations(vararg expectations: ScrollTestLazyIndexExpectation) {
    expectations.forEach(::assertLazyIndexForRenderItem)
}

internal fun scrollTestSingle(
    id: String,
    ts: String = "2026-04-20T12:00:00Z",
    content: String = id,
): ChatRenderItem.Single = ChatRenderItem.Single(
    message = scrollTestMessage(ScrollTestMessageSpec(id = id, ts = ts, content = content)),
    groupPosition = GroupPosition.None,
)

internal fun scrollTestRunBlock(
    runId: String,
    ts: String,
): ChatRenderItem.RunBlock = ChatRenderItem.RunBlock(
    runId = runId,
    messages = listOf(scrollTestMessage(ScrollTestMessageSpec(id = "$runId-message", ts = ts)) to GroupPosition.None),
)

internal fun scrollTestMessage(spec: ScrollTestMessageSpec): UiMessage =
    UiMessage(
        id = spec.id,
        role = spec.role.value,
        content = spec.content,
        timestamp = spec.ts,
        isReasoning = spec.role.isReasoning,
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

internal data class ScrollTestGeometryOptions(
    val state: ChatUiState = ChatUiState(),
    val widthPx: Int = 320,
    val activeFontScale: Float = 1f,
    val layoutDirection: LayoutDirection = LayoutDirection.Ltr,
)

internal fun ChatRenderItem.scrollTestGeometrySignature(
    options: ScrollTestGeometryOptions = ScrollTestGeometryOptions(),
): ChatRenderItemGeometrySignature =
    chatGeometrySignature(
        state = options.state,
        chatMode = "interactive",
        widthPx = options.widthPx,
        density = Density(density = 2f, fontScale = 1f),
        layoutDirection = options.layoutDirection,
        activeFontScale = options.activeFontScale,
    )

internal val scrollTestChatBackgroundSolid = com.letta.mobile.ui.theme.ChatBackground.SolidColor(Color(0xFF123456), "Custom")
