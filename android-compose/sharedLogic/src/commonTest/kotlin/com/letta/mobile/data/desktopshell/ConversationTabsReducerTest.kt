package com.letta.mobile.data.desktopshell

import kotlin.test.Test
import kotlin.test.assertEquals

class ConversationTabsReducerTest {
    @Test
    fun `opening another conversation replaces the preview`() {
        val first = ConversationTabsReducer.select(ConversationTabsState(), "a")
        val second = ConversationTabsReducer.select(first, "b")
        assertEquals(listOf("b"), second.openConversationIds)
    }

    @Test
    fun `pinning keeps a tab while opening and revisiting others`() {
        val a = ConversationTabsReducer.pin(ConversationTabsReducer.select(ConversationTabsState(), "a"), "a")
        val b = ConversationTabsReducer.select(a, "b")
        assertEquals(listOf("a"), b.pinnedConversationIds)
        assertEquals("b", b.transientConversationId)
        assertEquals(b, ConversationTabsReducer.select(b, "a"))
        val closed = ConversationTabsReducer.close(b, "b")
        assertEquals("a", closed.fallbackConversationId)
        assertEquals(listOf("a"), closed.state.openConversationIds)
    }

    @Test
    fun `retaining and unpinning preserve pinned order`() {
        val state = ConversationTabsState(listOf("a", "b", "c"), "preview")
        assertEquals(state, ConversationTabsReducer.reorder(state, "preview", 0))
        assertEquals(listOf("b", "c", "a", "preview"), ConversationTabsReducer.reorder(state, "a", 3).openConversationIds)
        val kept = ConversationTabsReducer.retainAvailable(state, setOf("a", "c", "preview"), null)
        assertEquals(listOf("a", "c", "preview"), kept.openConversationIds)
        val unpinned = ConversationTabsReducer.unpin(kept, "a", "preview")
        assertEquals(listOf("c", "preview"), unpinned.openConversationIds)
        val selected = ConversationTabsReducer.unpin(unpinned, "c", "c")
        assertEquals(listOf("c"), selected.openConversationIds)
        assertEquals("c", selected.transientConversationId)
        assertEquals(selected, ConversationTabsReducer.reorder(selected, "c", 0))
    }

    @Test
    fun `selection opens each conversation once`() {
        val first = ConversationTabsReducer.select(ConversationTabsState(), "conversation-1")
        val duplicate = ConversationTabsReducer.select(first, "conversation-1")

        assertEquals(listOf("conversation-1"), duplicate.openConversationIds)
    }

    @Test
    fun `pruning retains the selected conversation while lists refresh`() {
        val state = ConversationTabsState(listOf("conversation-1", "conversation-2"))

        val retained = ConversationTabsReducer.retainAvailable(
            state = state,
            availableConversationIds = setOf("conversation-2"),
            selectedConversationId = "conversation-1",
        )

        assertEquals(listOf("conversation-1", "conversation-2"), retained.openConversationIds)
    }

    @Test
    fun `close selects right neighbor before left neighbor`() {
        val state = ConversationTabsState(listOf("left", "closing", "right"))

        val middle = ConversationTabsReducer.close(state, "closing")
        val last = ConversationTabsReducer.close(state, "right")

        assertEquals("right", middle.fallbackConversationId)
        assertEquals(listOf("left", "right"), middle.state.openConversationIds)
        assertEquals("closing", last.fallbackConversationId)
    }

    @Test
    fun `reorder moves a tab left`() {
        val state = ConversationTabsState(listOf("a", "b", "c", "d"))

        val reordered = ConversationTabsReducer.reorder(state, "c", 0)

        assertEquals(listOf("c", "a", "b", "d"), reordered.openConversationIds)
    }

    @Test
    fun `reorder moves a tab right`() {
        val state = ConversationTabsState(listOf("a", "b", "c", "d"))

        val reordered = ConversationTabsReducer.reorder(state, "a", 2)

        assertEquals(listOf("b", "c", "a", "d"), reordered.openConversationIds)
    }

    @Test
    fun `reorder moves a tab to the end`() {
        val state = ConversationTabsState(listOf("a", "b", "c"))

        val reordered = ConversationTabsReducer.reorder(state, "a", 2)

        assertEquals(listOf("b", "c", "a"), reordered.openConversationIds)
    }

    @Test
    fun `reorder moves a tab to the start`() {
        val state = ConversationTabsState(listOf("a", "b", "c"))

        val reordered = ConversationTabsReducer.reorder(state, "c", 0)

        assertEquals(listOf("c", "a", "b"), reordered.openConversationIds)
    }

    @Test
    fun `reorder onto its own position is a no-op`() {
        val state = ConversationTabsState(listOf("a", "b", "c"))

        val reordered = ConversationTabsReducer.reorder(state, "b", 1)

        assertEquals(state, reordered)
    }

    @Test
    fun `reorder clamps an out-of-range target index`() {
        val state = ConversationTabsState(listOf("a", "b", "c"))

        val clampedHigh = ConversationTabsReducer.reorder(state, "a", 99)
        val clampedLow = ConversationTabsReducer.reorder(state, "c", -5)

        assertEquals(listOf("b", "c", "a"), clampedHigh.openConversationIds)
        assertEquals(listOf("c", "a", "b"), clampedLow.openConversationIds)
    }

    @Test
    fun `reorder ignores a conversation id that is not open`() {
        val state = ConversationTabsState(listOf("a", "b", "c"))

        val reordered = ConversationTabsReducer.reorder(state, "missing", 0)

        assertEquals(state, reordered)
    }

    @Test
    fun `reorder is a no-op with fewer than two tabs`() {
        val state = ConversationTabsState(listOf("a"))

        val reordered = ConversationTabsReducer.reorder(state, "a", 0)

        assertEquals(state, reordered)
    }
}
