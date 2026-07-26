package com.letta.mobile.ui.chat.render

import com.letta.mobile.data.model.UiMessage
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ChatRenderItemStateTest {
    @Test
    fun tokenOnlyMessageUpdateKeepsItemStateEqual() {
        val initial = ChatUiState(
            messages = persistentListOf(message("Hello")),
            isStreaming = true,
        )
        val nextToken = initial.copy(
            messages = persistentListOf(message("Hello world")),
            completionTokens = 2,
        )

        assertEquals(
            initial.toChatRenderItemState(),
            nextToken.toChatRenderItemState(),
        )
    }

    @Test
    fun itemVisualChangesInvalidateItemState() {
        val initial = ChatUiState(isStreaming = true)
        val changedStates = listOf(
            initial.copy(isStreaming = false),
            initial.copy(activeApprovalRequestId = "approval-1"),
            initial.copy(collapsedRunIds = persistentSetOf("run-1")),
            initial.copy(expandedReasoningMessageIds = persistentSetOf("message-1")),
        )

        changedStates.forEach { changed ->
            assertNotEquals(
                initial.toChatRenderItemState(),
                changed.toChatRenderItemState(),
            )
        }
    }

    private fun message(content: String) = UiMessage(
        id = "message-1",
        role = "assistant",
        content = content,
        timestamp = "2026-07-26T00:00:00Z",
        isPending = true,
    )
}
