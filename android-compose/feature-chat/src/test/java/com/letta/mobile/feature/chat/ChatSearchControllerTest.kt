package com.letta.mobile.feature.chat

import com.letta.mobile.data.model.MessageSearchRequest
import com.letta.mobile.data.model.MessageSearchResult
import com.letta.mobile.data.model.ParsedSearchMessage
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.repository.MessageRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.jupiter.api.Tag

@Tag("unit")
class ChatSearchControllerTest {
    private val messageRepository: MessageRepository = mockk()
    private val controller = ChatSearchController(messageRepository)

    @Test
    fun `localResults searches visible user and assistant messages only`() {
        val messages = listOf(
            UiMessage(id = "u1", role = "user", content = "Needle from user", timestamp = "2026-05-01T00:00:00Z"),
            UiMessage(id = "a1", role = "assistant", content = "needle from assistant", timestamp = "2026-05-01T00:00:01Z"),
            UiMessage(id = "t1", role = "tool", content = "needle from tool", timestamp = "2026-05-01T00:00:02Z"),
        )

        val results = controller.localResults(
            query = "needle",
            messages = messages,
            agentId = "agent-1",
            conversationId = "conv-1",
        )

        assertEquals(listOf("u1", "a1"), results.map { it.messageId })
        assertEquals(listOf("conv-1", "conv-1"), results.map { it.conversationId })
    }

    @Test
    fun `remoteResults scopes request and filters other agents`() = runTest {
        var capturedRequest: MessageSearchRequest? = null
        coEvery { messageRepository.searchMessages(any()) } answers {
            capturedRequest = firstArg()
            listOf(
                searchResult(messageId = "m1", agentId = "agent-1", content = "hit"),
                searchResult(messageId = "m2", agentId = "agent-2", content = "wrong agent"),
            )
        }

        val results = controller.remoteResults("needle", "agent-1")

        assertEquals("needle", capturedRequest?.query)
        assertEquals("fts", capturedRequest?.searchMode)
        assertEquals(listOf("user", "assistant"), capturedRequest?.roles)
        assertEquals("agent-1", capturedRequest?.agentId)
        assertEquals(50, capturedRequest?.limit)
        assertEquals(listOf("m1"), results.map { it.messageId })
        coVerify(exactly = 1) { messageRepository.searchMessages(any()) }
    }

    @Test
    fun `mergeResults deduplicates by message id before fallback identity`() {
        val local = listOf(
            ParsedSearchMessage("same", "agent-1", "user", "local", "date", "conv-1"),
            ParsedSearchMessage(null, "agent-1", "assistant", "fallback", "date", "conv-1"),
        )
        val remote = listOf(
            ParsedSearchMessage("same", "agent-1", "assistant", "remote", "date", "conv-1"),
            ParsedSearchMessage(null, "agent-1", "assistant", "fallback", "later", "conv-1"),
            ParsedSearchMessage("remote-only", "agent-1", "assistant", "remote", "date", "conv-1"),
        )

        val merged = controller.mergeResults(local, remote)

        assertEquals(listOf("same", null, "remote-only"), merged.map { it.messageId })
        assertEquals(listOf("local", "fallback", "remote"), merged.map { it.content })
    }

    private fun searchResult(
        messageId: String,
        agentId: String,
        content: String,
    ): MessageSearchResult = MessageSearchResult(
        embeddedText = content,
        message = JsonObject(
            mapOf(
                "message_id" to JsonPrimitive(messageId),
                "agent_id" to JsonPrimitive(agentId),
                "message_type" to JsonPrimitive("assistant"),
                "content" to JsonPrimitive(content),
                "conversation_id" to JsonPrimitive("conv-1"),
            )
        ),
    )
}
