package com.letta.mobile.ui.chat.surface

import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.common.GroupPosition
import kotlin.test.Test
import kotlin.test.assertEquals

class DockedReplyTurnTest {
    private fun item(id: String, role: String) =
        ChatRenderItem.Single(UiMessage(id = id, role = role, content = id, timestamp = ""), GroupPosition.None)

    @Test
    fun theTurnRunsFromTheLatestPromptToTheNewestItemOldestFirst() {
        // Render order is newest first.
        val newestFirst = listOf(item("a2", "assistant"), item("a1", "assistant"), item("u2", "user"), item("a0", "assistant"), item("u1", "user"))
        assertEquals(listOf("u2", "a1", "a2"), currentTurn(newestFirst).map { (it as ChatRenderItem.Single).message.id })
    }

    @Test
    fun aPromptWithNoReplyYetIsItsOwnTurn() {
        val newestFirst = listOf(item("u2", "user"), item("a1", "assistant"))
        assertEquals(listOf("u2"), currentTurn(newestFirst).map { (it as ChatRenderItem.Single).message.id })
    }

    @Test
    fun anEmptyTimelineHasNoTurn() {
        assertEquals(emptyList(), currentTurn(emptyList()))
    }
}
