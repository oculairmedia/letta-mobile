package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.subagents.SubagentDispatchTarget
import com.letta.mobile.data.subagents.subagentViewForDispatch
import com.letta.mobile.runtime.ToolCallId
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** The read-only subagent address and the local transcript reader must agree on conversation identity. */
class LocalBackendMessageReaderSubagentTest {

    @Test
    fun dispatchDefaultViewsReadTheirOwnMessageEnvelopesForRegularAndLocalAgents() {
        val baseDir = Files.createTempDirectory("local-subagent-messages").toFile()
        val support = LocalBackendStoreSupport(baseDir, "http://127.0.0.1:0")
        val reader = LocalBackendMessageReader(support)
        val regularAgent = "agent-regular"
        val localAgent = "agent-local-1234abcd-5678"
        writeDefaultTranscript(baseDir, regularAgent, "regular transcript")
        writeDefaultTranscript(baseDir, localAgent, "local subagent transcript")

        val regularView = dispatchView(regularAgent)
        val localView = dispatchView(localAgent)

        assertEquals("conv-default-$localAgent", localView.conversationId.value)
        assertEquals(listOf("regular transcript"), reader.readTexts(regularView.conversationId.value))
        assertEquals(listOf("local subagent transcript"), reader.readTexts(localView.conversationId.value))
    }

    @Test
    fun explicitConversationUsesItsStoredAgentAndEnvelope() {
        val baseDir = Files.createTempDirectory("local-explicit-subagent-messages").toFile()
        val support = LocalBackendStoreSupport(baseDir, "http://127.0.0.1:0")
        val reader = LocalBackendMessageReader(support)
        val agentId = AgentId("agent-local-explicit-child")
        val conversationId = ConversationId("conv-explicit-child")
        val dir = LocalBackendLayout.conversationDir(baseDir, conversationId, agentId).apply { mkdirs() }
        File(dir, LocalBackendLayout.CONVERSATION_FILE).writeText(
            """{"id":"${conversationId.value}","agent_id":"${agentId.value}"}""",
        )
        File(dir, LocalBackendLayout.TRANSCRIPT_FILE).writeText(messageEnvelope("explicit transcript") + "\n")

        assertEquals(listOf("explicit transcript"), reader.readTexts(conversationId.value))
    }

    @Test
    fun bareDefaultFailsClosedEvenWhenAgentDefaultsExist() {
        val baseDir = Files.createTempDirectory("local-ambiguous-default").toFile()
        val support = LocalBackendStoreSupport(baseDir, "http://127.0.0.1:0")
        writeDefaultTranscript(baseDir, "agent-one", "one")
        writeDefaultTranscript(baseDir, "agent-local-two", "two")

        val rows = LocalBackendMessageReader(support).listMessagesProjected(
            conversationId = LocalBackendLayout.DEFAULT_CONVERSATION_ID,
            agentId = null,
            page = MessagePage(limit = null, before = null, after = null, order = null),
        )

        assertNotNull(rows)
        assertEquals(0, rows.size)
    }

    private fun dispatchView(agentId: String) = listOf(
        SubagentEntry(
            toolCallId = "tool-$agentId",
            status = "completed",
            subagentAgentId = agentId,
            subagentConversationId = LocalBackendLayout.DEFAULT_CONVERSATION_ID,
        ),
    ).subagentViewForDispatch(SubagentDispatchTarget(ToolCallId("tool-$agentId")))
        ?: error("Expected a subagent view for $agentId")

    private fun writeDefaultTranscript(baseDir: File, agentId: String, text: String) {
        val dir = LocalBackendLayout.defaultConversationDir(baseDir, AgentId(agentId)).apply { mkdirs() }
        File(dir, LocalBackendLayout.TRANSCRIPT_FILE).writeText(messageEnvelope(text) + "\n")
    }

    private fun messageEnvelope(text: String): String =
        """{"type":"message","id":"envelope-1","message":{"id":"message-1","role":"user","content":[{"type":"text","text":"$text"}]}}"""

    private fun LocalBackendMessageReader.readTexts(conversationId: String): List<String> =
        listMessagesProjected(
            conversationId = conversationId,
            agentId = null,
            page = MessagePage(limit = null, before = null, after = null, order = null),
        )!!.map { row -> row.jsonObject["content"]!!.jsonPrimitive.content }
}
