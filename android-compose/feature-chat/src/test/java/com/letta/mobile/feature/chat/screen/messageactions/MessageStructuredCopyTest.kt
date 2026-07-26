package com.letta.mobile.feature.chat.screen.messageactions

import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.feature.chat.screen.buildMessageCopyText
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageStructuredCopyTest {

    @Test
    fun `message actions preserve structured tool copy`() {
        val message = UiMessage(
            id = "assistant-message",
            role = "assistant",
            content = "Done",
            timestamp = "2026-07-25T19:30:00Z",
            toolCalls = listOf(
                UiToolCall(
                    name = "search",
                    arguments = """{"query":"Letta"}""",
                    result = "One result",
                    executionTimeMs = 1250,
                ),
            ),
        )

        assertEquals(
            """
            Done

            Tool: search
            Execution time: 1.3s
            Arguments:
            {"query":"Letta"}
            Result:
            One result
            """.trimIndent(),
            buildMessageCopyText(message),
        )
    }
}
