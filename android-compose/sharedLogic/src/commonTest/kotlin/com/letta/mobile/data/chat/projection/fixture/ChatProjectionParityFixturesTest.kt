package com.letta.mobile.data.chat.projection.fixture

import com.letta.mobile.data.chat.projection.ChatDisplayMode
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.chat.projection.buildChatRenderModel
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatProjectionParityFixturesTest {

    private fun user(id: String, content: String) = UiMessage(
        id = id,
        role = "user",
        content = content,
        timestamp = "2024-01-01T00:00:00Z"
    )

    private fun reasoning(id: String, content: String, runId: String) = UiMessage(
        id = id,
        role = "assistant",
        content = content,
        timestamp = "2024-01-01T00:00:01Z",
        isReasoning = true,
        runId = runId
    )

    private fun assistant(id: String, content: String, runId: String) = UiMessage(
        id = id,
        role = "assistant",
        content = content,
        timestamp = "2024-01-01T00:00:02Z",
        runId = runId
    )

    private fun assistantToolCall(id: String, runId: String, toolCalls: List<UiToolCall>) = UiMessage(
        id = id,
        role = "assistant",
        content = "",
        timestamp = "2024-01-01T00:00:02Z",
        runId = runId,
        toolCalls = toolCalls
    )

    @Test
    fun `fixture - simple interactive stream correctly aggregates assistant run`() {
        val messages = listOf(
            user("u1", "hello"),
            reasoning("r1", "thinking...", "run-1"),
            assistant("a1", "hi there", "run-1")
        )

        val model = buildChatRenderModel(messages, ChatDisplayMode.Interactive)

        // The list is in reverse order (newest first)
        assertEquals(2, model.renderItems.size)

        val item1 = model.renderItems[0] // run-1 block (reasoning + assistant)
        assertTrue(item1 is ChatRenderItem.RunBlock)
        assertEquals("run-1", item1.runId)
        assertEquals(2, item1.messages.size)

        val item2 = model.renderItems[1] // user message
        assertTrue(item2 is ChatRenderItem.Single)
        assertEquals("u1", item2.message.id)
    }

    @Test
    fun `fixture - identical reasoning and assistant content dedupes reasoning`() {
        val messages = listOf(
            user("u1", "hello"),
            reasoning("r1", "same text", "run-1"),
            assistant("a1", "same text", "run-1")
        )

        val model = buildChatRenderModel(messages, ChatDisplayMode.Interactive)

        assertEquals(2, model.renderItems.size)

        val item1 = model.renderItems[0] // assistant run block
        assertTrue(item1 is ChatRenderItem.Single, "Should be single because reasoning was deduped")
        assertEquals("a1", item1.message.id)
    }

    @Test
    fun `fixture - tool calls are grouped correctly within the same run`() {
        val messages = listOf(
            user("u1", "fetch data"),
            reasoning("r1", "running tools", "run-2"),
            assistantToolCall(
                "t1",
                "run-2",
                listOf(UiToolCall(name = "Fetch", arguments = "{}", result = "OK"))
            ),
            assistant("a1", "done", "run-2")
        )

        val model = buildChatRenderModel(messages, ChatDisplayMode.Interactive)

        assertEquals(2, model.renderItems.size)

        val item1 = model.renderItems[0]
        assertTrue(item1 is ChatRenderItem.RunBlock)
        assertEquals("run-2", item1.runId)
        assertEquals(3, item1.messages.size) // r1, t1, a1
    }
}
