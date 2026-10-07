package com.letta.mobile.ui.chat.surface

import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.chat.projection.parseTimestampEpochMillis
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.PendingToolCall
import com.letta.mobile.ui.common.GroupPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.collections.immutable.persistentListOf

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

    @Test
    fun aRunningToolNamesItselfOnTheCollapsedTurn() {
        val tool = UiToolCall(name = "Bash", arguments = "{}", result = null, status = "running")
        val newestFirst = listOf(
            ChatRenderItem.Single(
                UiMessage(
                    id = "a1",
                    role = "assistant",
                    content = "Measuring.",
                    timestamp = "2026-10-06T16:00:00Z",
                    runId = "run-1",
                    toolCalls = listOf(tool),
                ),
                GroupPosition.None,
            ),
            ChatRenderItem.Single(
                UiMessage(id = "u1", role = "user", content = "go", timestamp = "2026-10-06T16:00:00Z"),
                GroupPosition.None,
            ),
        )
        val turn = collapsedTurnOf(newestFirst, ChatUiState(isStreaming = true))
        assertTrue(turn.working)
        assertEquals("Bash", turn.runningToolName)
        assertEquals(parseTimestampEpochMillis("2026-10-06T16:00:00Z"), turn.startedAtEpochMs)
    }

    @Test
    fun pendingToolsFillTheRunningNameWhenTheCallHasNotLanded() {
        val newestFirst = listOf(
            ChatRenderItem.Single(
                UiMessage(id = "u1", role = "user", content = "go", timestamp = "2026-10-06T16:00:00Z"),
                GroupPosition.None,
            ),
        )
        val turn = collapsedTurnOf(
            newestFirst,
            ChatUiState(
                isStreaming = true,
                pendingTools = persistentListOf(PendingToolCall("t1", "measure", 0L)),
            ),
        )
        assertTrue(turn.working)
        assertEquals("measure", turn.runningToolName)
        assertNull(collapsedTurnOf(newestFirst, ChatUiState()).runningToolName)
    }
}
