package com.letta.mobile.feature.chat

import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.common.GroupPosition
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.collections.immutable.persistentSetOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ChatMessageListScrollTest {

    @Test
    fun `first render item maps after persistent typing slot when not streaming`() {
        val items = listOf(single("m1", ts = "2026-04-20T12:00:00Z"))

        assertEquals(
            1,
            calculateLazyIndexForRenderItem(
                targetRenderIndex = 0,
                renderItems = items,
            ),
        )
    }

    @Test
    fun `first render item maps after typing slot`() {
        val items = listOf(single("m1", ts = "2026-04-20T12:00:00Z"))

        assertEquals(
            1,
            calculateLazyIndexForRenderItem(
                targetRenderIndex = 0,
                renderItems = items,
            ),
        )
    }

    @Test
    fun `target index includes typing slot and previous message rows`() {
        val items = listOf(
            single("newest", ts = "2026-04-20T12:00:00Z"),
            single("older", ts = "2026-04-20T11:00:00Z"),
        )

        assertEquals(
            2,
            calculateLazyIndexForRenderItem(
                targetRenderIndex = 1,
                renderItems = items,
            ),
        )
    }

    @Test
    fun `date separator before target adds lazy item offset after typing slot`() {
        val items = listOf(
            single("today", ts = "2026-04-20T12:00:00Z"),
            single("yesterday", ts = "2026-04-19T12:00:00Z"),
        )

        assertEquals(
            3,
            calculateLazyIndexForRenderItem(
                targetRenderIndex = 1,
                renderItems = items,
            ),
        )
    }

    @Test
    fun `multiple date separators before target accumulate offsets`() {
        val items = listOf(
            single("day3", ts = "2026-04-21T12:00:00Z"),
            single("day2", ts = "2026-04-20T12:00:00Z"),
            single("day1", ts = "2026-04-19T12:00:00Z"),
        )

        assertEquals(
            5,
            calculateLazyIndexForRenderItem(
                targetRenderIndex = 2,
                renderItems = items,
            ),
        )
    }

    @Test
    fun `run block boundary timestamp participates in date separator offsets after typing slot`() {
        val items = listOf(
            runBlock("run1", ts = "2026-04-20T12:00:00Z"),
            single("older", ts = "2026-04-19T12:00:00Z"),
        )

        assertEquals(
            3,
            calculateLazyIndexForRenderItem(
                targetRenderIndex = 1,
                renderItems = items,
            ),
        )
    }

    @Test
    fun `auto-scroll signature changes when newest message content grows`() {
        val before = newestMessageAutoScrollSignature(
            listOf(message(id = "assistant", content = "hel")),
        )
        val after = newestMessageAutoScrollSignature(
            listOf(message(id = "assistant", content = "hello")),
        )

        assertNotEquals(before, after)
    }

    @Test
    fun `auto-scroll signature ignores older page additions when newest message is unchanged`() {
        val before = newestMessageAutoScrollSignature(
            listOf(message(id = "newest", content = "hello")),
        )
        val after = newestMessageAutoScrollSignature(
            listOf(
                message(id = "older", content = "old"),
                message(id = "newest", content = "hello"),
            ),
        )

        assertEquals(before, after)
    }

    @Test
    fun `streaming geometry floor follows render bucket across content growth`() {
        val state = ChatMessageGeometryState(maxEntries = 8)
        val short = geometrySignature(content = "Hello")
        val longer = geometrySignature(content = "Hello, this is still streaming")

        state.recordMeasuredHeight(short, heightPx = 120, isStreaming = true)
        state.recordMeasuredHeight(longer, heightPx = 96, isStreaming = true)

        assertEquals(120, state.heightFloorFor(longer, isStreaming = true))

        state.recordMeasuredHeight(longer, heightPx = 148, isStreaming = true)

        assertEquals(148, state.heightFloorFor(longer, isStreaming = true))
    }

    @Test
    fun `streaming geometry floor does not constrain settled content`() {
        val state = ChatMessageGeometryState(maxEntries = 8)
        val short = geometrySignature(content = "Hello")
        val longer = geometrySignature(content = "Hello after the stream ends")

        state.recordMeasuredHeight(short, heightPx = 120, isStreaming = true)

        assertEquals(0, state.heightFloorFor(longer, isStreaming = false))
    }

    @Test
    fun `streaming geometry measurement does not seed settled exact height for same content`() {
        val state = ChatMessageGeometryState(maxEntries = 8)
        val tableMessage = geometrySignature(
            content = "| a | b |\n| --- | --- |\n| 1 | 2 |\n\nAfter table",
        )

        state.recordMeasuredHeight(tableMessage, heightPx = 420, isStreaming = true)

        assertEquals(420, state.heightFloorFor(tableMessage, isStreaming = true))
        assertEquals(0, state.heightFloorFor(tableMessage, isStreaming = false))

        state.recordMeasuredHeight(tableMessage, heightPx = 180, isStreaming = false)

        assertEquals(180, state.heightFloorFor(tableMessage, isStreaming = false))
    }

    @Test
    fun `inactive streaming geometry buckets are pruned`() {
        val state = ChatMessageGeometryState(maxEntries = 8)
        val first = geometrySignature(renderKey = "msg-first", content = "first")
        val firstGrown = geometrySignature(renderKey = "msg-first", content = "first after more tokens")
        val second = geometrySignature(renderKey = "msg-second", content = "second")
        val secondGrown = geometrySignature(renderKey = "msg-second", content = "second after more tokens")

        state.recordMeasuredHeight(first, heightPx = 120, isStreaming = true)
        state.recordMeasuredHeight(second, heightPx = 80, isStreaming = true)
        state.retainStreamingBuckets(setOf(second.bucket))

        assertEquals(0, state.heightFloorFor(firstGrown, isStreaming = true))
        assertEquals(80, state.heightFloorFor(secondGrown, isStreaming = true))
    }

    @Test
    fun `render item geometry signature changes for width scale direction expansion and content`() {
        val item = single("assistant", content = "hello")
        val base = item.chatGeometrySignature()

        assertNotEquals(base, item.chatGeometrySignature(widthPx = 480))
        assertNotEquals(base, item.chatGeometrySignature(activeFontScale = 1.2f))
        assertNotEquals(base, item.chatGeometrySignature(layoutDirection = LayoutDirection.Rtl))
        assertNotEquals(base, single("assistant", content = "hello world").chatGeometrySignature())

        val reasoning = single("reasoning", content = "thinking").copy(
            message = message(id = "reasoning", content = "thinking", isReasoning = true),
        )
        val collapsed = reasoning.chatGeometrySignature(state = ChatUiState())
        val expanded = reasoning.chatGeometrySignature(
            state = ChatUiState(expandedReasoningMessageIds = persistentSetOf("reasoning")),
        )

        assertNotEquals(collapsed, expanded)
    }

    private fun single(
        id: String,
        ts: String = "2026-04-20T12:00:00Z",
        content: String = id,
    ): ChatRenderItem.Single = ChatRenderItem.Single(
        message = message(id = id, ts = ts, content = content),
        groupPosition = GroupPosition.None,
    )

    private fun runBlock(
        runId: String,
        ts: String,
    ): ChatRenderItem.RunBlock = ChatRenderItem.RunBlock(
        runId = runId,
        messages = listOf(message(id = "$runId-message", ts = ts) to GroupPosition.None),
    )

    private fun message(
        id: String,
        ts: String = "2026-04-20T12:00:00Z",
        content: String = id,
        isReasoning: Boolean = false,
    ) = UiMessage(
        id = id,
        role = "assistant",
        content = content,
        timestamp = ts,
        isReasoning = isReasoning,
    )

    private fun geometrySignature(
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

    private fun ChatRenderItem.chatGeometrySignature(
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
}
