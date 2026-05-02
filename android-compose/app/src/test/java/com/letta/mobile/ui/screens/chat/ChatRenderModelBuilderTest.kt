package com.letta.mobile.ui.screens.chat

import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.common.GroupPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRenderModelBuilderTest {

    @Test
    fun `reasoning followed by assistant with same content skips assistant echo`() {
        val messages = listOf(
            reasoning("r1", content = "thinking"),
            assistant("a1", content = "thinking"),
        )

        assertEquals(listOf("r1"), dedupeReasoningAssistantEchoes(messages).map { it.id })
    }

    @Test
    fun `reasoning followed by assistant with different content keeps both`() {
        val messages = listOf(
            reasoning("r1", content = "thinking"),
            assistant("a1", content = "answer"),
        )

        assertEquals(listOf("r1", "a1"), dedupeReasoningAssistantEchoes(messages).map { it.id })
    }

    @Test
    fun `non-reasoning intervening message resets reasoning echo dedupe`() {
        val messages = listOf(
            reasoning("r1", content = "thinking"),
            user("u1", content = "interrupt"),
            assistant("a1", content = "thinking"),
        )

        assertEquals(listOf("r1", "u1", "a1"), dedupeReasoningAssistantEchoes(messages).map { it.id })
    }

    @Test
    fun `assistant echo is skipped only for exact content match`() {
        val messages = listOf(
            reasoning("r1", content = "Thinking"),
            assistant("a1", content = "thinking"),
            reasoning("r2", content = "done"),
            assistant("a2", content = "done "),
        )

        assertEquals(listOf("r1", "a1", "r2", "a2"), dedupeReasoningAssistantEchoes(messages).map { it.id })
    }

    @Test
    fun `simple mode keeps user assistant and errors but filters reasoning`() {
        val messages = listOf(
            user("u1"),
            reasoning("r1"),
            assistant("a1"),
            toolError("e1"),
        )

        val visible = filterMessagesForMode(messages, ChatDisplayMode.Simple)

        assertEquals(listOf("u1", "a1", "e1"), visible.map { it.id })
    }

    @Test
    fun `interactive and debug keep the same message set`() {
        val messages = listOf(
            user("u1"),
            reasoning("r1"),
            assistant("a1"),
            toolError("e1"),
        )

        assertEquals(messages, filterMessagesForMode(messages, ChatDisplayMode.Interactive))
        assertEquals(messages, filterMessagesForMode(messages, ChatDisplayMode.Debug))
    }

    @Test
    fun `string chat mode maps simple and debug explicitly and unknown to interactive`() {
        assertEquals(ChatDisplayMode.Simple, "simple".toChatDisplayMode())
        assertEquals(ChatDisplayMode.Debug, "debug".toChatDisplayMode())
        assertEquals(ChatDisplayMode.Interactive, "interactive".toChatDisplayMode())
        assertEquals(ChatDisplayMode.Interactive, "anything-else".toChatDisplayMode())
    }

    @Test
    fun `render model exposes visible and grouped chronological messages`() {
        val model = buildChatRenderModel(
            messages = listOf(user("u1"), user("u2"), assistant("a1")),
            mode = ChatDisplayMode.Interactive,
        )

        assertEquals(listOf("u1", "u2", "a1"), model.visibleMessages.map { it.id })
        assertEquals(
            listOf(GroupPosition.First, GroupPosition.Last, GroupPosition.None),
            model.groupedMessages.map { it.second },
        )
    }

    @Test
    fun `render model adds first assistant latency from previous user timestamp`() {
        val model = buildChatRenderModel(
            messages = listOf(
                user("u1", ts = "2026-04-19T12:00:00Z"),
                assistant("a1", ts = "2026-04-19T12:00:02.500Z"),
                assistant("a2", ts = "2026-04-19T12:00:04Z"),
            ),
            mode = ChatDisplayMode.Interactive,
        )

        assertEquals(2500L, model.visibleMessages.first { it.id == "a1" }.latencyMs)
        assertEquals(null, model.visibleMessages.first { it.id == "a2" }.latencyMs)
    }

    @Test
    fun `render model preserves explicit latency metadata`() {
        val explicitLatency = assistant("a1", ts = "2026-04-19T12:00:02Z").copy(latencyMs = 42L)
        val model = buildChatRenderModel(
            messages = listOf(user("u1", ts = "2026-04-19T12:00:00Z"), explicitLatency),
            mode = ChatDisplayMode.Interactive,
        )

        assertEquals(42L, model.visibleMessages.first { it.id == "a1" }.latencyMs)
    }

    @Test
    fun `render items are newest first for reverse layout`() {
        val model = buildChatRenderModel(
            messages = listOf(
                user("u1", ts = "2026-04-19T12:00:00Z"),
                assistant("a1", ts = "2026-04-19T12:00:01Z"),
                user("u2", ts = "2026-04-19T12:00:02Z"),
            ),
            mode = ChatDisplayMode.Interactive,
        )

        assertEquals(listOf("msg-u2", "msg-a1", "msg-u1"), model.renderItems.map { it.key })
    }

    @Test
    fun `duplicate message IDs are filtered before render item shaping`() {
        val model = buildChatRenderModel(
            messages = listOf(
                user("dup", content = "first"),
                assistant("a1"),
                user("dup", content = "second"),
            ),
            mode = ChatDisplayMode.Interactive,
        )

        assertEquals(listOf("msg-a1", "msg-dup"), model.renderItems.map { it.key })
        val duplicateItem = model.renderItems[1] as ChatRenderItem.Single
        assertEquals("first", duplicateItem.message.content)
    }

    @Test
    fun `adjacent assistant messages with same runId produce newest-first RunBlock item`() {
        val model = buildChatRenderModel(
            messages = listOf(
                user("u1", ts = "2026-04-19T11:59:59Z"),
                assistant("a1", runId = "r1", ts = "2026-04-19T12:00:00Z"),
                assistant("a2", runId = "r1", ts = "2026-04-19T12:00:30Z"),
            ),
            mode = ChatDisplayMode.Interactive,
        )

        assertEquals(2, model.renderItems.size)
        val block = model.renderItems.first() as ChatRenderItem.RunBlock
        assertEquals("run-r1", block.key)
        assertEquals("2026-04-19T12:00:30Z", block.boundaryTimestamp)
        assertEquals(listOf("a1", "a2"), block.messages.map { it.first.id })
        assertEquals(listOf(GroupPosition.First, GroupPosition.Last), block.messages.map { it.second })
        assertTrue(block.containsMessageId("a1"))
        assertTrue(block.containsMessageId("a2"))
    }

    @Test
    fun `single assistant run keeps stable run key through full pipeline`() {
        val model = buildChatRenderModel(
            messages = listOf(user("u1"), assistant("a1", runId = "r1")),
            mode = ChatDisplayMode.Interactive,
        )

        assertEquals("run-r1", model.renderItems.first().key)
    }

    private fun user(
        id: String,
        content: String = "u-$id",
        ts: String = "2026-04-19T12:00:00Z",
    ) = UiMessage(
        id = id,
        role = "user",
        content = content,
        timestamp = ts,
    )

    private fun assistant(
        id: String,
        content: String = "a-$id",
        runId: String? = null,
        ts: String = "2026-04-19T12:00:00Z",
    ) = UiMessage(
        id = id,
        role = "assistant",
        content = content,
        timestamp = ts,
        runId = runId,
    )

    private fun reasoning(
        id: String,
        content: String = "reasoning-$id",
        ts: String = "2026-04-19T12:00:00Z",
    ) = UiMessage(
        id = id,
        role = "assistant",
        content = content,
        timestamp = ts,
        isReasoning = true,
    )

    private fun toolError(
        id: String,
        content: String = "error-$id",
        ts: String = "2026-04-19T12:00:00Z",
    ) = UiMessage(
        id = id,
        role = "tool",
        content = content,
        timestamp = ts,
        isError = true,
    )
}
