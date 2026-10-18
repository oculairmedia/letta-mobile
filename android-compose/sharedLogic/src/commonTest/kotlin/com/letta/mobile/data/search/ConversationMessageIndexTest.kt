package com.letta.mobile.data.search

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ReasoningMessage
import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.UserMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive

/** letta-mobile-ckmub.3.9: the unified search finds message text, not only titles and names. */
class ConversationMessageIndexTest {
    private val messages = mapOf(
        "c-1" to listOf(user("m-1", "Ask Emmanuel about the release"), assistant("m-2", "Done, shipped it.")),
        "c-2" to listOf(
            assistant("m-3", "Nothing to report"),
            ReasoningMessage(id = "m-4", reasoning = "emmanuel thinks"),
            ToolCallMessage(id = "m-5", toolCall = ToolCall(name = "bash", arguments = "echo emmanuel")),
        ),
    )
    private var conversations = listOf(
        IndexedConversation("c-1", agentId = "a-1", revision = "r1"),
        IndexedConversation("c-2", agentId = "a-2", revision = "r1"),
    )
    private val reads = mutableListOf<String>()
    private val reader = ConversationMessageReader { id, _ ->
        reads += id
        messages[id].orEmpty()
    }

    @Test
    fun findsWhatThePersonAndTheAgentSaidButNotReasoning() = runTest {
        val index = ConversationMessageIndex(reader, { conversations })
        val hits = index.search(MessageSearchQuery("emmanuel"))
        assertEquals(listOf("m-1"), hits.map { it.messageId })
        assertEquals("c-1", hits.single().conversationId)
        assertEquals("a-1", hits.single().agentId)
    }

    @Test
    fun reasoningAndToolRolesAreFoundWhenSelected() = runTest {
        val index = ConversationMessageIndex(reader, { conversations })
        val reasoning = index.search(query("emmanuel", MessageSearchRole.Reasoning))
        assertEquals(listOf("m-4"), reasoning.map { it.messageId })
        val tool = index.search(query("emmanuel", MessageSearchRole.Tool))
        assertEquals(listOf("m-5"), tool.map { it.messageId })
    }

    @Test
    fun anAgentScopeReadsOnlyThatAgentsConversations() = runTest {
        val index = ConversationMessageIndex(reader, { conversations })
        assertTrue(index.search(MessageSearchQuery("report", agentId = "a-1")).isEmpty())
        assertEquals(listOf("c-1"), reads)
    }

    @Test
    fun aConversationIsReadAgainOnlyWhenItsRevisionChanges() = runTest {
        val index = ConversationMessageIndex(reader, { conversations })
        index.search(MessageSearchQuery("shipped"))
        index.search(MessageSearchQuery("release"))
        assertEquals(listOf("c-1", "c-2"), reads)
        conversations = listOf(conversations[0].copy(revision = "r2"), conversations[1])
        index.search(MessageSearchQuery("release"))
        assertEquals(listOf("c-1", "c-2", "c-1"), reads)
    }

    @Test
    fun aBlankQueryReadsNothingAndAFailedReadIsSkipped() = runTest {
        val failing = ConversationMessageReader { id, _ -> if (id == "c-2") error("offline") else messages[id].orEmpty() }
        val index = ConversationMessageIndex(failing, { conversations })
        assertTrue(index.search(MessageSearchQuery("  ")).isEmpty())
        assertEquals(listOf("m-2"), index.search(MessageSearchQuery("shipped")).map { it.messageId })
    }

    @Test
    fun theIndexKeepsOnlyAsManyConversationsAsItReads() = runTest {
        val index = ConversationMessageIndex(reader, { conversations }, MessageIndexLimits(conversations = 1))
        index.search(MessageSearchQuery("emmanuel", agentId = "a-1"))
        index.search(MessageSearchQuery("emmanuel", agentId = "a-2"))
        index.search(MessageSearchQuery("emmanuel", agentId = "a-1"))
        assertEquals(listOf("c-1", "c-2", "c-1"), reads)
    }

    @Test
    fun aLongMessageIsIndexedOnlyUpToTheCharacterLimit() = runTest {
        val long = mapOf("c-1" to listOf(user("m-1", "a".repeat(50) + " needle")))
        val index = ConversationMessageIndex(
            { id, _ -> long[id].orEmpty() },
            { conversations.take(1) },
            MessageIndexLimits(charactersPerMessage = 20),
        )
        assertTrue(index.search(MessageSearchQuery("needle")).isEmpty())
        assertEquals(20, index.search(MessageSearchQuery("aaaa")).single().text.length)
    }

    private fun query(text: String, role: MessageSearchRole) =
        MessageSearchQuery(text, selection = MessageSearchSelection(roles = setOf(role)))

    private fun user(id: String, text: String): LettaMessage = UserMessage(id = id, contentRaw = JsonPrimitive(text))

    private fun assistant(id: String, text: String): LettaMessage = AssistantMessage(id = id, contentRaw = JsonPrimitive(text))
}
