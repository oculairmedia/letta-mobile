package com.letta.mobile.runtime.local

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BoundedTranscriptReaderTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `small lines pass through byte-identical`() {
        val file = tempFolder.newFile("messages.jsonl").apply {
            writeText(
                """{"id":"u1","role":"user","content":[{"type":"text","text":"hi"}]}""" + "\n" +
                    """{"id":"a1","role":"assistant","content":[{"type":"text","text":"ok"}]}""" + "\n",
            )
        }
        val lines = BoundedTranscriptReader.readLines(file)
        assertEquals(2, lines.size)
        assertEquals(0L, lines[0].collapsedValueChars)
        assertEquals(0L, lines[1].collapsedValueChars)
        assertTrue(lines[0].text.contains("\"text\":\"hi\""))
    }

    @Test
    fun `oversized string value is collapsed without allocating its full size`() {
        // 20MB of base64-safe filler ('A' contains no quote/backslash) — well
        // beyond a normal photo's base64, forcing the collapse path.
        val hugeValue = "A".repeat(20 * 1024 * 1024)
        val line = """{"id":"u1","role":"user","content":[{"type":"image","mimeType":"image/png","data":"$hugeValue"}]}"""
        val file = tempFolder.newFile("messages.jsonl").apply { writeText(line + "\n") }

        val lines = BoundedTranscriptReader.readLines(file, maxInlineValueChars = 8 * 1024 * 1024)
        assertEquals(1, lines.size)
        val bounded = lines[0]
        assertTrue("collapsed value must be reported", bounded.collapsedValueChars > 0L)
        // The reconstructed line must be small (bounded), NOT ~20MB.
        assertTrue(
            "reconstructed line should be tiny compared to the 20MB source, was ${bounded.text.length}",
            bounded.text.length < 1024,
        )
        // It must still be valid, parseable JSON with the structural fields intact.
        val row = json.parseToJsonElement(bounded.text).jsonObject
        assertEquals("user", row["role"]?.jsonPrimitive?.content)
        assertTrue("content array must still be present", row["content"] != null)
    }

    @Test
    fun `extractOversizedLength recovers the original size`() {
        val hugeValue = "B".repeat(9 * 1024 * 1024)
        val line = """{"id":"u1","role":"user","content":[{"type":"image","mimeType":"image/png","data":"$hugeValue"}]}"""
        val file = tempFolder.newFile("messages.jsonl").apply { writeText(line + "\n") }

        val bounded = BoundedTranscriptReader.readLines(file, maxInlineValueChars = 8 * 1024 * 1024).single()
        // Extract the collapsed "data" value textually and confirm the marker parses back out.
        val dataStart = bounded.text.indexOf("\"data\":\"") + "\"data\":\"".length
        val dataEnd = bounded.text.indexOf("\"", dataStart)
        val dataValue = bounded.text.substring(dataStart, dataEnd)
        val recovered = BoundedTranscriptReader.extractOversizedLength(dataValue)
        assertNotNull(recovered)
        assertTrue("recovered length should be close to the original 9MB", recovered!! >= 9 * 1024 * 1024)
    }

    @Test
    fun `missing file returns empty list`() {
        val missing = File(tempFolder.root, "does-not-exist.jsonl")
        assertEquals(emptyList<BoundedTranscriptReader.BoundedLine>(), BoundedTranscriptReader.readLines(missing))
    }
}
