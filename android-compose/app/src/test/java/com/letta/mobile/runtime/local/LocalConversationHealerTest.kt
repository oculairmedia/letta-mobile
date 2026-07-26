package com.letta.mobile.runtime.local

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalConversationHealerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }
    private val healer = LocalConversationHealer(json)

    private fun assistantWithToolCall(id: String, callId: String, name: String = "Bash"): String =
        """{"id":"$id","role":"assistant","content":[{"type":"toolCall","id":"$callId","name":"$name","arguments":{"command":"ls"}}]}"""

    private fun toolResult(callId: String, text: String = "ok"): String =
        """{"id":"res-$callId","role":"toolResult","toolCallId":"$callId","isError":false,"content":[{"type":"text","text":"$text"}]}"""

    private fun userRow(id: String, text: String): String =
        """{"id":"$id","role":"user","content":[{"type":"text","text":"$text"}]}"""

    private fun writeTranscript(vararg lines: String): File =
        tempFolder.newFile("messages.jsonl").apply { writeText(lines.joinToString("\n") + "\n") }

    private fun parseRows(file: File): List<JsonObject> =
        file.readLines().filter { it.isNotBlank() }.map { json.parseToJsonElement(it).jsonObject }

    @Test
    fun `well-formed transcript with paired tool call is left untouched`() {
        val file = writeTranscript(
            userRow("u1", "hi"),
            assistantWithToolCall("a1", "call_1"),
            toolResult("call_1"),
        )
        val before = file.readText()
        val report = healer.healTranscript(file)
        assertFalse(report.healed)
        assertEquals(0, report.rowsAppended)
        assertEquals("transcript must be byte-identical when nothing to heal", before, file.readText())
    }

    @Test
    fun `orphaned tool result with no preceding tool call is removed`() {
        // The OpenAI/GPT-5.x corruption: a toolResult whose toolCallId has no
        // declaring toolCall. Provider 400s "role tool must be a response to a
        // preceding message with tool_calls" until it is removed.
        val file = writeTranscript(
            userRow("u1", "hi"),
            toolResult("call_orphan_result"),
            userRow("u2", "still here"),
        )
        val report = healer.healTranscript(file)
        assertTrue(report.healed)
        assertEquals(1, report.rowsRemoved)
        assertEquals(listOf("call_orphan_result"), report.orphanResultIds)
        // the orphaned toolResult row is gone; the user rows remain
        val rows = parseRows(file)
        assertTrue(rows.none { it["role"]?.jsonPrimitive?.content == "toolResult" })
        assertEquals(2, rows.size)
    }

    @Test
    fun `paired tool result is kept and only the orphan is removed`() {
        val file = writeTranscript(
            assistantWithToolCall("a1", "call_paired"),
            toolResult("call_paired"),
            toolResult("call_orphan"),
        )
        val report = healer.healTranscript(file)
        assertEquals(1, report.rowsRemoved)
        assertEquals(listOf("call_orphan"), report.orphanResultIds)
        val resultIds = parseRows(file)
            .filter { it["role"]?.jsonPrimitive?.content == "toolResult" }
            .map { it["toolCallId"]!!.jsonPrimitive.content }
        assertEquals(listOf("call_paired"), resultIds)
    }

    @Test
    fun `both directions heal together - dangling call settled and orphan result removed`() {
        val file = writeTranscript(
            assistantWithToolCall("a1", "call_dangling"), // needs a synthetic result
            toolResult("call_orphan"),                    // must be removed
        )
        val report = healer.healTranscript(file)
        assertTrue(report.healed)
        assertEquals(1, report.rowsAppended)
        assertEquals(1, report.rowsRemoved)
        assertEquals(listOf("call_dangling"), report.orphanCallIds)
        assertEquals(listOf("call_orphan"), report.orphanResultIds)
        val resultIds = parseRows(file)
            .filter { it["role"]?.jsonPrimitive?.content == "toolResult" }
            .map { it["toolCallId"]!!.jsonPrimitive.content }
        // orphan gone, dangling now satisfied
        assertEquals(listOf("call_dangling"), resultIds)
    }

    @Test
    fun `dangling tool call gets a synthetic interrupted tool result appended`() {
        val file = writeTranscript(
            userRow("u1", "run a command"),
            assistantWithToolCall("a1", "call_orphan", name = "Bash"),
        )
        val report = healer.healTranscript(file)
        assertTrue(report.healed)
        assertEquals(1, report.rowsAppended)
        assertEquals(listOf("call_orphan"), report.orphanCallIds)

        val rows = parseRows(file)
        val settled = rows.last()
        assertEquals("toolResult", settled["role"]!!.jsonPrimitive.content)
        assertEquals("call_orphan", settled["toolCallId"]!!.jsonPrimitive.content)
        assertEquals(true, settled["isError"]!!.jsonPrimitive.content.toBoolean())
        val text = settled["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content
        assertTrue("interrupted result should name the tool", text.contains("Bash"))
    }

    @Test
    fun `multiple dangling calls all get settled`() {
        val file = writeTranscript(
            assistantWithToolCall("a1", "call_a", name = "Read"),
            assistantWithToolCall("a2", "call_b", name = "Write"),
            assistantWithToolCall("a3", "call_c", name = "Bash"),
        )
        val report = healer.healTranscript(file)
        assertEquals(3, report.rowsAppended)
        assertEquals(setOf("call_a", "call_b", "call_c"), report.orphanCallIds.toSet())
        val settledIds = parseRows(file)
            .filter { it["role"]?.jsonPrimitive?.content == "toolResult" }
            .map { it["toolCallId"]!!.jsonPrimitive.content }
            .toSet()
        assertEquals(setOf("call_a", "call_b", "call_c"), settledIds)
    }

    @Test
    fun `only the orphan among paired calls is settled`() {
        val file = writeTranscript(
            assistantWithToolCall("a1", "call_paired"),
            toolResult("call_paired"),
            assistantWithToolCall("a2", "call_orphan"),
        )
        val report = healer.healTranscript(file)
        assertEquals(1, report.rowsAppended)
        assertEquals(listOf("call_orphan"), report.orphanCallIds)
    }

    @Test
    fun `tool result that appears before its call still counts as paired`() {
        // The API pairing requirement only needs every tool_use to have SOME
        // tool_result in the request, regardless of strict ordering.
        val file = writeTranscript(
            toolResult("call_1"),
            assistantWithToolCall("a1", "call_1"),
        )
        val report = healer.healTranscript(file)
        assertFalse(report.healed)
    }

    @Test
    fun `healing is idempotent - second pass is a no-op`() {
        val file = writeTranscript(
            assistantWithToolCall("a1", "call_orphan"),
        )
        val first = healer.healTranscript(file)
        assertTrue(first.healed)
        val afterFirst = file.readText()
        val second = healer.healTranscript(file)
        assertFalse("second heal must be a no-op", second.healed)
        assertEquals("transcript unchanged after idempotent second pass", afterFirst, file.readText())
    }

    @Test
    fun `missing transcript file is a safe no-op`() {
        val missing = File(tempFolder.root, "does-not-exist.jsonl")
        val report = healer.healTranscript(missing)
        assertFalse(report.healed)
        assertEquals(0, report.rowsAppended)
    }

    @Test
    fun `empty transcript file is a safe no-op`() {
        val file = tempFolder.newFile("messages.jsonl")
        val report = healer.healTranscript(file)
        assertFalse(report.healed)
    }

    @Test
    fun `malformed json lines are skipped without crashing`() {
        val file = writeTranscript(
            "{ this is not valid json",
            assistantWithToolCall("a1", "call_orphan"),
            "}}}garbage",
        )
        val report = healer.healTranscript(file)
        assertEquals(1, report.rowsAppended)
        assertEquals(listOf("call_orphan"), report.orphanCallIds)
        // original lines preserved (append-only), heal row added.
        assertTrue(file.readLines().any { it.contains("call_orphan") && it.contains("toolResult") })
    }

    @Test
    fun `duplicate orphan ids are settled only once`() {
        val file = writeTranscript(
            assistantWithToolCall("a1", "call_dup"),
            assistantWithToolCall("a2", "call_dup"),
        )
        val report = healer.healTranscript(file)
        assertEquals(1, report.rowsAppended)
        assertEquals(listOf("call_dup"), report.orphanCallIds)
    }

    @Test
    fun `existing transcript lines are preserved verbatim after heal`() {
        val userLine = userRow("u1", "hello")
        val assistantLine = assistantWithToolCall("a1", "call_orphan")
        val file = writeTranscript(userLine, assistantLine)
        healer.healTranscript(file)
        val lines = file.readLines().filter { it.isNotBlank() }
        assertEquals(userLine, lines[0])
        assertEquals(assistantLine, lines[1])
        assertEquals(3, lines.size)
    }
}
