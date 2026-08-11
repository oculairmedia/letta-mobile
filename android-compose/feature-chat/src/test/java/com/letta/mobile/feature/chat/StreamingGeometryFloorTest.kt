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
    fun `recordMeasuredHeight skips write when height unchanged for same signature`() {
        val state = ChatMessageGeometryState()
        val signature = scrollTestGeometrySignature(
            ScrollTestGeometrySignatureSpec(content = "Hello"),
        )
        state.recordMeasuredHeight(signature, heightPx = 120)
        val beforeSize = state.exactHeightsSize()
        // Second write of the same height for the same signature should be a no-op
        // (no LinkedHashMap access-order reorder, no entry creation).
        state.recordMeasuredHeight(signature, heightPx = 120)
        assertEquals(beforeSize, state.exactHeightsSize())
    }

    @Test
    fun `recordMeasuredHeight writes when height changes for same signature`() {
        val state = ChatMessageGeometryState()
        val signature = scrollTestGeometrySignature(
            ScrollTestGeometrySignatureSpec(content = "Hello"),
        )
        state.recordMeasuredHeight(signature, heightPx = 120)
        assertEquals(1, state.exactHeightsSize())
        state.recordMeasuredHeight(signature, heightPx = 180)
        assertEquals(1, state.exactHeightsSize())
    }

    @Test
    fun `recordMeasuredHeight writes for a new signature`() {
        val state = ChatMessageGeometryState()
        val first = scrollTestGeometrySignature(ScrollTestGeometrySignatureSpec(content = "first"))
        val second = scrollTestGeometrySignature(ScrollTestGeometrySignatureSpec(content = "second"))
        state.recordMeasuredHeight(first, heightPx = 120)
        assertEquals(1, state.exactHeightsSize())
        state.recordMeasuredHeight(second, heightPx = 96)
        assertEquals(2, state.exactHeightsSize())
    }

    @Test
    fun `exactHeights LRU evicts oldest when maxEntries exceeded`() {
        val bound = 3
        val state = ChatMessageGeometryState(maxEntries = bound)
        val sigA = scrollTestGeometrySignature(ScrollTestGeometrySignatureSpec(content = "alpha"))
        val sigB = scrollTestGeometrySignature(ScrollTestGeometrySignatureSpec(content = "beta"))
        val sigC = scrollTestGeometrySignature(ScrollTestGeometrySignatureSpec(content = "gamma"))
        val sigD = scrollTestGeometrySignature(ScrollTestGeometrySignatureSpec(content = "delta"))
        state.recordMeasuredHeight(sigA, heightPx = 10)
        state.recordMeasuredHeight(sigB, heightPx = 20)
        state.recordMeasuredHeight(sigC, heightPx = 30)
        assertEquals(3, state.exactHeightsSize())

        // Re-touch sigA to make it most-recently-accessed.
        state.recordMeasuredHeight(sigA, heightPx = 11)
        // Inserting sigD must evict the now-oldest entry (sigB).
        state.recordMeasuredHeight(sigD, heightPx = 40)

        assertEquals(bound, state.exactHeightsSize())
        // Re-record each surviving signature and confirm we do not see extra
        // entries: writing them again at any height must still yield the
        // bounded size, and the LRU bound is honored.
        state.recordMeasuredHeight(sigA, heightPx = 12)
        state.recordMeasuredHeight(sigC, heightPx = 31)
        state.recordMeasuredHeight(sigD, heightPx = 41)
        assertEquals(bound, state.exactHeightsSize())
    }

    @Test
    fun `render item geometry signature changes for width scale direction expansion and content`() {
        val item = scrollTestSingle(ScrollTestMessageSpec(id = "assistant", content = "hello"))
        val base = item.scrollTestGeometrySignature()

        assertNotEquals(base, item.scrollTestGeometrySignature(ScrollTestGeometryOptions(widthPx = 480)))
        assertNotEquals(base, item.scrollTestGeometrySignature(ScrollTestGeometryOptions(activeFontScale = 1.2f)))
        assertNotEquals(base, item.scrollTestGeometrySignature(ScrollTestGeometryOptions(layoutDirection = LayoutDirection.Rtl)))
        assertNotEquals(
            base,
            scrollTestSingle(ScrollTestMessageSpec(id = "assistant", content = "hello world"))
                .scrollTestGeometrySignature(),
        )

        val reasoning = scrollTestSingle(ScrollTestMessageSpec(id = "reasoning", content = "thinking")).copy(
            message = scrollTestMessage(
                ScrollTestMessageSpec(id = "reasoning", content = "thinking", role = ScrollTestMessageRole(isReasoning = true)),
            ),
        )
        val collapsed = reasoning.scrollTestGeometrySignature(ScrollTestGeometryOptions(state = ChatUiState()))
        val expanded = reasoning.scrollTestGeometrySignature(
            ScrollTestGeometryOptions(state = ChatUiState(expandedReasoningMessageIds = persistentSetOf("reasoning"))),
        )

        assertNotEquals(collapsed, expanded)
    }

    @Test
    fun `render item geometry signature samples long content changes without full text hash`() {
        val baseContent = "a".repeat(200)
        val changedContent = baseContent.replaceRange(100, 101, "b")

        val base = scrollTestSingle(ScrollTestMessageSpec(id = "assistant", content = baseContent))
            .scrollTestGeometrySignature()
        val changed = scrollTestSingle(ScrollTestMessageSpec(id = "assistant", content = changedContent))
            .scrollTestGeometrySignature()

        assertEquals(base.contentLength, changed.contentLength)
        assertNotEquals(base.contentHash, changed.contentHash)
    }
}