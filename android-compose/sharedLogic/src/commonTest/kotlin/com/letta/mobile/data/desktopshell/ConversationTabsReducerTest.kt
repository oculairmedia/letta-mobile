package com.letta.mobile.data.desktopshell

import kotlin.test.Test
import kotlin.test.assertEquals

class ConversationTabsReducerTest {
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

        assertEquals(listOf("conversation-2", "conversation-1"), retained.openConversationIds)
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
}
