package com.letta.mobile.ui.chat.render

import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * letta-mobile-1260 (CodeRabbit review follow-up): locks the `maxEntries`
 * validation in `ChatMessageGeometryState` plus the bounded-eviction semantics.
 *
 * The pre-fix class accepted any `maxEntries` value. A negative value would
 * mean `while (exactHeights.size > maxEntries)` never terminates (size starts
 * at 0 and only grows); a zero value would evict every insertion on its way
 * in, which is correct but only because the trim loop runs once per insert.
 */
class ChatMessageGeometryStateTest {

    private fun bucket(renderKey: String = "k"): ChatMessageGeometryBucket =
        ChatMessageGeometryBucket(
            renderKey = renderKey,
            widthPx = 0,
            densityBucket = 0,
            fontScaleBucket = 0,
            chatFontScaleBucket = 0,
            layoutDirection = LayoutDirection.Ltr,
            chatMode = "default",
            expansionHash = 0,
        )

    private fun signature(
        renderKey: String = "k",
        contentLength: Int = 0,
        contentHash: Int = 0,
    ): ChatRenderItemGeometrySignature =
        ChatRenderItemGeometrySignature(
            bucket = bucket(renderKey),
            contentLength = contentLength,
            contentHash = contentHash,
        )

    @Test
    fun `maxEntries zero is allowed and behaves as a no-cache`() {
        val state = ChatMessageGeometryState(maxEntries = 0)
        state.recordMeasuredHeight(signature(), heightPx = 42)
        // Zero-capacity cache evicts the just-inserted entry per the trim
        // loop, so any subsequent read sees a cold cache.
        assertEquals(0, state.exactHeightsSize())
        assertNull(state.heightFor(signature()))
    }

    @Test
    fun `maxEntries one retains the most recent insertion`() {
        val state = ChatMessageGeometryState(maxEntries = 1)
        val first = signature(renderKey = "first")
        state.recordMeasuredHeight(first, heightPx = 10)
        assertEquals(1, state.exactHeightsSize())

        // A second signature evicts the first (insertion-order, oldest first).
        val second = signature(renderKey = "second")
        state.recordMeasuredHeight(second, heightPx = 20)
        assertEquals(1, state.exactHeightsSize())
        assertNull(state.heightFor(first))
        assertEquals(20, state.heightFor(second))
    }

    @Test
    fun `negative maxEntries is rejected with a clear message`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            ChatMessageGeometryState(maxEntries = -1)
        }
        assertEquals("maxEntries must be >= 0 (got -1)", ex.message)
    }

    @Test
    fun `dedup on identical signature does not bust the LRU bound`() {
        val state = ChatMessageGeometryState(maxEntries = 2)
        val sig = signature()
        state.recordMeasuredHeight(sig, heightPx = 10)
        // Same signature + same height = dedup write. The trim loop must
        // not double-evict (pre-fix this would have run unconditionally).
        state.recordMeasuredHeight(sig, heightPx = 10)
        state.recordMeasuredHeight(sig, heightPx = 10)
        assertEquals(1, state.exactHeightsSize())
    }
}
