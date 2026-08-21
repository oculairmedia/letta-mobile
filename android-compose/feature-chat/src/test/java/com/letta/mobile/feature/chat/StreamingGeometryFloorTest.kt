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
    fun `exactHeights evicts the oldest inserted signature when over maxEntries`() {
        val state = ChatMessageGeometryState(maxEntries = 3)
        val sigA = scrollTestGeometrySignature(ScrollTestGeometrySignatureSpec(content = "alpha"))
        val sigB = scrollTestGeometrySignature(ScrollTestGeometrySignatureSpec(content = "beta"))
        val sigC = scrollTestGeometrySignature(ScrollTestGeometrySignatureSpec(content = "gamma"))
        val sigD = scrollTestGeometrySignature(ScrollTestGeometrySignatureSpec(content = "delta"))

        state.recordMeasuredHeight(sigA, heightPx = 10)
        state.recordMeasuredHeight(sigB, heightPx = 20)
        state.recordMeasuredHeight(sigC, heightPx = 30)
        state.recordMeasuredHeight(sigD, heightPx = 40) // triggers eviction of sigA

        assertEquals(3, state.exactHeightsSize())
        assertEquals(false, state.contains(sigA))
        assertEquals(true, state.contains(sigB))
        assertEquals(true, state.contains(sigC))
        assertEquals(true, state.contains(sigD))
        assertEquals(20, state.heightFor(sigB))
        assertEquals(30, state.heightFor(sigC))
        assertEquals(40, state.heightFor(sigD))
    }

    @Test
    fun `recordMeasuredHeight with unchanged height does not refresh LRU recency`() {
        val state = ChatMessageGeometryState(maxEntries = 3)
        val sigA = scrollTestGeometrySignature(ScrollTestGeometrySignatureSpec(content = "alpha"))
        val sigB = scrollTestGeometrySignature(ScrollTestGeometrySignatureSpec(content = "beta"))
        val sigC = scrollTestGeometrySignature(ScrollTestGeometrySignatureSpec(content = "gamma"))
        val sigD = scrollTestGeometrySignature(ScrollTestGeometrySignatureSpec(content = "delta"))

        state.recordMeasuredHeight(sigA, heightPx = 10)
        state.recordMeasuredHeight(sigB, heightPx = 20)
        state.recordMeasuredHeight(sigC, heightPx = 30)

        // Re-record sigA with the same height. With accessOrder = true, this
        // would refresh sigA's recency and sigA would survive the next
        // eviction. With accessOrder = false (and the dedup early-return),
        // sigA stays in its original insertion slot and is the oldest entry —
        // sigA gets evicted when sigD arrives.
        state.recordMeasuredHeight(sigA, heightPx = 10)

        state.recordMeasuredHeight(sigD, heightPx = 40)

        assertEquals(3, state.exactHeightsSize())
        assertEquals(false, state.contains(sigA)) // sigA evicted (oldest insertion)
        assertEquals(true, state.contains(sigB))
        assertEquals(true, state.contains(sigC))
        assertEquals(true, state.contains(sigD))
        assertEquals(20, state.heightFor(sigB))
        assertEquals(30, state.heightFor(sigC))
        assertEquals(40, state.heightFor(sigD))
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

    // letta-mobile-geom-cache-wireup: contract test for the read-path
    // wire-up. `MeasuredChatRenderItem` calls `heightFor(signature)` BEFORE
    // layering onSizeChanged. When the cache is empty (signature not yet
    // measured) the read returns null, the row lays out normally, and the
    // first onSizeChanged populates the cache. When the cache has a height
    // for the same signature the read returns it; the row uses it as
    // `Modifier.heightIn(min=cached)` so Compose skips the initial measure.
    //
    // This test asserts the read-bearing contract directly on the cache.
    // The wire-up behavior (Compose modifier construction) is verified
    // dogfood via the `Telemetry/GeometryCache: read` event count in
    // PR #1266 verification comments.
    @Test
    fun `heightFor returns null for an unseen signature and the recorded height afterward`() {
        val state = ChatMessageGeometryState()
        val signature = scrollTestGeometrySignature(
            ScrollTestGeometrySignatureSpec(content = "Hello"),
        )
        // Cache miss before any measurement.
        assertEquals(null, state.heightFor(signature))
        state.recordMeasuredHeight(signature, heightPx = 120)
        // Cache hit after recording.
        assertEquals(120, state.heightFor(signature))
    }

    @Test
    fun `heightFor returns the latest recorded height after a height change`() {
        val state = ChatMessageGeometryState()
        val signature = scrollTestGeometrySignature(
            ScrollTestGeometrySignatureSpec(content = "Hello"),
        )
        state.recordMeasuredHeight(signature, heightPx = 120)
        assertEquals(120, state.heightFor(signature))
        state.recordMeasuredHeight(signature, heightPx = 180)
        assertEquals(180, state.heightFor(signature))
    }
}