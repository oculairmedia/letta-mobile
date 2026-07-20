package com.letta.mobile.feature.chat

import androidx.compose.ui.unit.LayoutDirection
import com.letta.mobile.ui.chat.render.ChatMessageGeometryState
import com.letta.mobile.ui.chat.render.ChatUiState
import kotlinx.collections.immutable.persistentSetOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class StreamingGeometryFloorTest {

    @Test
    fun `streaming geometry floor follows render bucket across content growth`() {
        val state = ChatMessageGeometryState(maxEntries = 8)
        val short = scrollTestGeometrySignature(content = "Hello")
        val longer = scrollTestGeometrySignature(content = "Hello, this is still streaming")

        state.recordMeasuredHeight(short, heightPx = 120, isStreaming = true)
        state.recordMeasuredHeight(longer, heightPx = 96, isStreaming = true)

        assertEquals(120, state.heightFloorFor(longer, isStreaming = true))

        state.recordMeasuredHeight(longer, heightPx = 148, isStreaming = true)

        assertEquals(148, state.heightFloorFor(longer, isStreaming = true))
    }

    @Test
    fun `streaming geometry floor does not constrain settled content`() {
        val state = ChatMessageGeometryState(maxEntries = 8)
        val short = scrollTestGeometrySignature(content = "Hello")
        val longer = scrollTestGeometrySignature(content = "Hello after the stream ends")

        state.recordMeasuredHeight(short, heightPx = 120, isStreaming = true)

        assertEquals(0, state.heightFloorFor(longer, isStreaming = false))
    }

    @Test
    fun `clear streaming floors removes existing streaming bounds`() {
        val state = ChatMessageGeometryState(maxEntries = 8)
        val streamingItem = scrollTestGeometrySignature(content = "Streaming response...")

        state.recordMeasuredHeight(streamingItem, heightPx = 300, isStreaming = true)

        assertEquals(300, state.heightFloorFor(streamingItem, isStreaming = true))

        state.clearStreamingFloors()

        assertEquals(0, state.heightFloorFor(streamingItem, isStreaming = true))
    }

    @Test
    fun `streaming geometry measurement does not seed settled exact height for same content`() {
        val state = ChatMessageGeometryState(maxEntries = 8)
        val tableMessage = scrollTestGeometrySignature(
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
        val first = scrollTestGeometrySignature(renderKey = "msg-first", content = "first")
        val firstGrown = scrollTestGeometrySignature(renderKey = "msg-first", content = "first after more tokens")
        val second = scrollTestGeometrySignature(renderKey = "msg-second", content = "second")
        val secondGrown = scrollTestGeometrySignature(renderKey = "msg-second", content = "second after more tokens")

        state.recordMeasuredHeight(first, heightPx = 120, isStreaming = true)
        state.recordMeasuredHeight(second, heightPx = 80, isStreaming = true)
        state.retainStreamingBuckets(setOf(second.bucket))

        assertEquals(0, state.heightFloorFor(firstGrown, isStreaming = true))
        assertEquals(80, state.heightFloorFor(secondGrown, isStreaming = true))
    }

    @Test
    fun `render item geometry signature changes for width scale direction expansion and content`() {
        val item = scrollTestSingle("assistant", content = "hello")
        val base = item.scrollTestGeometrySignature()

        assertNotEquals(base, item.scrollTestGeometrySignature(widthPx = 480))
        assertNotEquals(base, item.scrollTestGeometrySignature(activeFontScale = 1.2f))
        assertNotEquals(base, item.scrollTestGeometrySignature(layoutDirection = LayoutDirection.Rtl))
        assertNotEquals(base, scrollTestSingle("assistant", content = "hello world").scrollTestGeometrySignature())

        val reasoning = scrollTestSingle("reasoning", content = "thinking").copy(
            message = scrollTestMessage(id = "reasoning", content = "thinking", isReasoning = true),
        )
        val collapsed = reasoning.scrollTestGeometrySignature(state = ChatUiState())
        val expanded = reasoning.scrollTestGeometrySignature(
            state = ChatUiState(expandedReasoningMessageIds = persistentSetOf("reasoning")),
        )

        assertNotEquals(collapsed, expanded)
    }

    @Test
    fun `render item geometry signature samples long content changes without full text hash`() {
        val baseContent = "a".repeat(200)
        val changedContent = baseContent.replaceRange(100, 101, "b")

        val base = scrollTestSingle("assistant", content = baseContent).scrollTestGeometrySignature()
        val changed = scrollTestSingle("assistant", content = changedContent).scrollTestGeometrySignature()

        assertEquals(base.contentLength, changed.contentLength)
        assertNotEquals(base.contentHash, changed.contentHash)
    }

    @Test
    fun `streaming geometry floor is invalidated across chatFontScaleBucket changes`() {
        val state = ChatMessageGeometryState(maxEntries = 8)
        val baseScale = scrollTestSingle("assistant", content = "Streaming response...")
            .scrollTestGeometrySignature(activeFontScale = 1.0f)
        val smallerScale = scrollTestSingle("assistant", content = "Streaming response...")
            .scrollTestGeometrySignature(activeFontScale = 0.8f)

        state.recordMeasuredHeight(baseScale, heightPx = 300, isStreaming = true)

        assertEquals(300, state.heightFloorFor(baseScale, isStreaming = true))
        assertEquals(0, state.heightFloorFor(smallerScale, isStreaming = true))
    }

    @Test
    fun `exact height is invalidated across chatFontScaleBucket changes`() {
        val state = ChatMessageGeometryState(maxEntries = 8)
        val baseScale = scrollTestSingle("assistant", content = "Settled response.")
            .scrollTestGeometrySignature(activeFontScale = 1.0f)
        val largerScale = scrollTestSingle("assistant", content = "Settled response.")
            .scrollTestGeometrySignature(activeFontScale = 1.5f)

        state.recordMeasuredHeight(baseScale, heightPx = 150, isStreaming = false)

        assertEquals(150, state.heightFloorFor(baseScale, isStreaming = false))
        assertEquals(0, state.heightFloorFor(largerScale, isStreaming = false))
    }
}
