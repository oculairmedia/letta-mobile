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

    // ── readSingleLineFull (vision regression fix, re: PR #1017) ────────────────

    @Test
    fun `readSingleLineFull recovers a collapsed line's full uncapped content`() {
        val hugeValue = "D".repeat(20 * 1024 * 1024)
        val line = """{"id":"u1","role":"user","content":[{"type":"image","mimeType":"image/png","data":"$hugeValue"}]}"""
        val file = tempFolder.newFile("messages.jsonl").apply {
            writeText("""{"id":"u0","role":"user","content":[{"type":"text","text":"hi"}]}""" + "\n" + line + "\n")
        }

        // Bounded pass collapses the second (index 1) line...
        val bounded = BoundedTranscriptReader.readLines(file, maxInlineValueChars = 8 * 1024 * 1024)
        assertEquals(2, bounded.size)
        assertTrue(bounded[1].collapsedValueChars > 0L)

        // ...but a targeted re-read of that exact index recovers the full value.
        val full = BoundedTranscriptReader.readSingleLineFull(file, 1)
        assertNotNull(full)
        assertTrue("recovered line must contain the full uncollapsed base64", full!!.contains(hugeValue))
        val row = json.parseToJsonElement(full).jsonObject
        assertEquals("user", row["role"]?.jsonPrimitive?.content)
    }

    @Test
    fun `readSingleLineFull skips blank lines when computing non-blank index`() {
        val file = tempFolder.newFile("messages.jsonl").apply {
            writeText(
                """{"id":"u0","role":"user","content":[]}""" + "\n" +
                    "\n" + // blank line — must not consume an index slot
                    """{"id":"u1","role":"user","content":[]}""" + "\n",
            )
        }
        val second = BoundedTranscriptReader.readSingleLineFull(file, 1)
        assertNotNull(second)
        assertTrue(second!!.contains("\"id\":\"u1\""))
    }

    @Test
    fun `readSingleLineFull returns null for an out-of-range index or missing file`() {
        val file = tempFolder.newFile("messages.jsonl").apply {
            writeText("""{"id":"u0","role":"user","content":[]}""" + "\n")
        }
        assertEquals(null, BoundedTranscriptReader.readSingleLineFull(file, 5))
        assertEquals(null, BoundedTranscriptReader.readSingleLineFull(file, -1))
        val missing = File(tempFolder.root, "does-not-exist.jsonl")
        assertEquals(null, BoundedTranscriptReader.readSingleLineFull(missing, 0))
    }

    @Test
    fun `readSingleLineFull works when target line has no trailing newline at EOF`() {
        val file = tempFolder.newFile("messages.jsonl")
        file.writeText("""{"id":"u0","role":"user","content":[]}""") // no trailing "\n"
        val line = BoundedTranscriptReader.readSingleLineFull(file, 0)
        assertNotNull(line)
        assertTrue(line!!.contains("\"id\":\"u0\""))
    }
}
