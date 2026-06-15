package com.letta.mobile.runtime.local

import android.content.Context
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.data.model.UserMessage
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LettaCodeLocalBackendStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun store(): LettaCodeLocalBackendStore {
        val context = mockk<Context>()
        every { context.filesDir } returns temp.root
        return LettaCodeLocalBackendStore(context)
    }

    private fun transcriptFile(agentId: String): File {
        val key = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("default:$agentId".toByteArray(Charsets.UTF_8))
        val dir = File(temp.root, "embedded-lettacode/local-backend/conversations/$key")
        dir.mkdirs()
        return File(dir, "messages.jsonl")
    }

    private fun writeTranscript(agentId: String, vararg rows: String) {
        transcriptFile(agentId).writeText(rows.joinToString("\n") + "\n")
    }

    // pi-ai rows as letta.js writes them: tool call as an assistant content
    // part, result as a dedicated toolResult row.
    private val userRow =
        """{"id":"ui-msg-1","role":"user","content":[{"type":"text","text":"run it"}]}"""
    private val assistantToolRow =
        """{"id":"ui-msg-2","role":"assistant","content":[{"type":"thinking","thinking":"hm"},{"type":"toolCall","id":"call_1","name":"Bash","arguments":{"command":"echo hi"}}]}"""
    private val toolResultRow =
        """{"id":"ui-msg-3","role":"toolResult","toolCallId":"call_1","toolName":"Bash","content":[{"type":"text","text":"hi\n"}],"isError":false}"""
    private val assistantTextRow =
        """{"id":"ui-msg-4","role":"assistant","content":[{"type":"text","text":"done: hi"}]}"""

    @Test
    fun `transcript surfaces tool calls and returns alongside text`() = runTest {
        writeTranscript("agent-1", userRow, assistantToolRow, toolResultRow, assistantTextRow)

        val messages = store().readTranscript("agent-1")

        assertEquals(
            listOf(
                UserMessage::class,
                ToolCallMessage::class,
                ToolReturnMessage::class,
                AssistantMessage::class,
            ),
            messages.map { it::class },
        )
        val call = messages[1] as ToolCallMessage
        assertEquals("call_1", call.toolCall?.id)
        assertEquals("Bash", call.toolCall?.name)
        assertTrue(call.toolCall?.arguments.orEmpty().contains("echo hi"))
        val ret = messages[2] as ToolReturnMessage
        assertEquals("call_1", ret.toolCallId)
        assertEquals("success", ret.status)
        assertEquals("hi\n", ret.toolReturn.funcResponse)
    }

    @Test
    fun `assistant rows with both text and tool call yield both messages`() = runTest {
        writeTranscript(
            "agent-2",
            """{"id":"m1","role":"assistant","content":[{"type":"text","text":"on it"},{"type":"toolCall","id":"call_9","name":"Read","arguments":{"file_path":"/tmp/x"}}]}""",
        )

        val messages = store().readTranscript("agent-2")

        assertEquals(listOf(AssistantMessage::class, ToolCallMessage::class), messages.map { it::class })
        assertEquals("Read", (messages[1] as ToolCallMessage).toolCall?.name)
    }

    @Test
    fun `readToolResults maps persisted results by call id`() = runTest {
        writeTranscript(
            "agent-3",
            toolResultRow,
            """{"id":"ui-msg-9","role":"toolResult","toolCallId":"call_2","content":[{"type":"text","text":"boom"}],"isError":true}""",
        )

        val results = store().readToolResults("agent-3")

        assertEquals(2, results.size)
        assertEquals("hi\n", results.getValue("call_1").body)
        assertEquals(false, results.getValue("call_1").isError)
        assertEquals(true, results.getValue("call_2").isError)
    }

    @Test
    fun `stripPersistedImagePayloads preserves latest image turn for follow-up questions`() = runTest {
        val rawBase64 = "A".repeat(1_200)
        writeTranscript(
            "agent-4",
            """{"id":"m1","role":"user","content":[{"type":"text","text":"describe this"},{"type":"image","mimeType":"image/png","data":"$rawBase64"}]}""",
            """{"id":"m2","role":"assistant","content":[{"type":"text","text":"looks like a photo"}]}""",
        )

        store().stripPersistedImagePayloads("agent-4")

        val persisted = transcriptFile("agent-4").readText()
        assertTrue(persisted.contains(rawBase64))
        assertTrue(persisted.contains("\"data\""))
        assertFalse(persisted.contains("image omitted from persisted history"))
    }

    @Test
    fun `stripPersistedImagePayloads replaces older base64 image content with compact metadata`() = runTest {
        val oldBase64 = "A".repeat(1_200)
        val latestBase64 = "B".repeat(1_200)
        writeTranscript(
            "agent-4b",
            """{"id":"m1","role":"user","content":[{"type":"text","text":"old image"},{"type":"image","mimeType":"image/png","data":"$oldBase64"}]}""",
            """{"id":"m2","role":"assistant","content":[{"type":"text","text":"old answer"}]}""",
            """{"id":"m3","role":"user","content":[{"type":"text","text":"new image"},{"type":"image","mimeType":"image/jpeg","data":"$latestBase64"}]}""",
        )

        store().stripPersistedImagePayloads("agent-4b")

        val persisted = transcriptFile("agent-4b").readText()
        assertFalse(persisted.contains(oldBase64))
        assertTrue(persisted.contains(latestBase64))
        assertTrue(persisted.contains("image omitted from persisted history"))
        assertTrue(persisted.contains("\"media_type\": \"image/png\""))
        assertTrue(persisted.contains("\"approx_bytes\": 900"))
    }

    @Test
    fun `stripPersistedImagePayloads replaces older data image urls in persisted text`() = runTest {
        val rawBase64 = "AQIDBAUGBwgJCgsMDQ4PEA=="
        val latestBase64 = "B".repeat(1_200)
        writeTranscript(
            "agent-5",
            """{"id":"m1","role":"user","content":[{"type":"text","text":"inline data:image/jpeg;base64,$rawBase64 done"}]}""",
            """{"id":"m2","role":"assistant","content":[{"type":"text","text":"ok"}]}""",
            """{"id":"m3","role":"user","content":[{"type":"text","text":"latest"},{"type":"image","mimeType":"image/png","data":"$latestBase64"}]}""",
        )

        store().stripPersistedImagePayloads("agent-5")

        val persisted = transcriptFile("agent-5").readText()
        assertFalse(persisted.contains(rawBase64))
        assertFalse(persisted.contains("data:image/jpeg;base64"))
        assertTrue(persisted.contains(latestBase64))
        assertTrue(persisted.contains("inline [image omitted from persisted history: image/jpeg"))
        assertTrue(persisted.contains(" done"))
    }
}
