package com.letta.mobile.data.controller.node.iroh

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class LocalBackendEmptyAssistantRecoveryTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `removes only empty assistant ids from active context and preserves transcript`() = runTest {
        val fixture = fixture(
            activeIds = listOf("user-1", "empty-1", "assistant-1", "tool-1", "empty-2"),
            transcript = """
                {"type":"message","message":{"id":"user-1","role":"user","content":[{"type":"text","text":"hello"}]}}
                {"type":"message","message":{"id":"empty-1","role":"assistant","content":[],"usage":{"totalTokens":0}}}
                {"type":"message","message":{"id":"assistant-1","role":"assistant","content":[{"type":"text","text":"ok"}]}}
                {"type":"message","message":{"id":"tool-1","role":"assistant","content":[{"type":"toolCall","id":"call-1"}]}}
                {"type":"message","message":{"id":"empty-2","role":"assistant","content":[]}}
            """.trimIndent(),
        )
        val originalTranscript = fixture.messages.readText()
        val oldMtime = fixture.conversation.lastModified()

        val removed = fixture.recovery.recover(AGENT_ID, CONVERSATION_ID)

        assertEquals(listOf("empty-1", "empty-2"), removed)
        assertEquals(
            listOf("user-1", "assistant-1", "tool-1"),
            activeIds(fixture.conversation),
        )
        assertEquals(originalTranscript, fixture.messages.readText())
        assertTrue(fixture.conversation.lastModified() > oldMtime)
        assertFalse(fixture.conversation.parentFile.listFiles().orEmpty().any { ".repair-" in it.name })
        assertEquals(emptyList(), fixture.recovery.recover(AGENT_ID, CONVERSATION_ID))
    }

    @Test
    fun `incremental scan finds a later empty assistant record`() = runTest {
        val fixture = fixture(
            activeIds = listOf("user-1"),
            transcript = """{"type":"message","message":{"id":"user-1","role":"user","content":[]}}""",
        )
        assertEquals(emptyList(), fixture.recovery.recover(AGENT_ID, CONVERSATION_ID))

        fixture.messages.appendText(
            "\n" + """{"type":"message","message":{"id":"empty-later","role":"assistant","content":[]}}""",
        )
        writeConversation(fixture.conversation, listOf("user-1", "empty-later"))

        assertEquals(
            listOf("empty-later"),
            fixture.recovery.recover(AGENT_ID, CONVERSATION_ID),
        )
        assertEquals(listOf("user-1"), activeIds(fixture.conversation))
    }

    @Test
    fun `refuses a conversation whose stored identity does not match`() = runTest {
        val fixture = fixture(
            activeIds = listOf("empty-1"),
            transcript = """{"type":"message","message":{"id":"empty-1","role":"assistant","content":[]}}""",
        )

        assertEquals(emptyList(), fixture.recovery.recover("agent-other", CONVERSATION_ID))
        assertEquals(listOf("empty-1"), activeIds(fixture.conversation))
    }

    private fun fixture(activeIds: List<String>, transcript: String): Fixture {
        val base = Files.createTempDirectory("empty-assistant-recovery").toFile()
        val key = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("conversation:$CONVERSATION_ID".toByteArray())
        val dir = File(base, "conversations/$key").apply { mkdirs() }
        val conversation = File(dir, "conversation.json")
        val messages = File(dir, "messages.jsonl")
        writeConversation(conversation, activeIds)
        conversation.setLastModified(1_000L)
        messages.writeText("$transcript\n")
        return Fixture(
            conversation = conversation,
            messages = messages,
            recovery = LocalBackendEmptyAssistantRecovery(base),
        )
    }

    private fun writeConversation(file: File, activeIds: List<String>) {
        val ids = activeIds.joinToString(",") { "\"$it\"" }
        file.writeText(
            """{"id":"$CONVERSATION_ID","agent_id":"$AGENT_ID","summary":"keep","in_context_message_ids":[$ids]}""",
        )
    }

    private fun activeIds(file: File): List<String> =
        json.parseToJsonElement(file.readText())
            .jsonObject["in_context_message_ids"]!!
            .jsonArray
            .map { it.toString().trim('"') }

    private data class Fixture(
        val conversation: File,
        val messages: File,
        val recovery: LocalBackendEmptyAssistantRecovery,
    )

    private companion object {
        const val AGENT_ID = "agent-1"
        const val CONVERSATION_ID = "conv-1"
    }
}
