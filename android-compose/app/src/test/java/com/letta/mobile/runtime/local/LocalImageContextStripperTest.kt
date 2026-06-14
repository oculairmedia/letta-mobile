package com.letta.mobile.runtime.local

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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

class LocalImageContextStripperTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }
    private val stripper = LocalImageContextStripper(json)

    private fun write(vararg lines: String): File =
        tempFolder.newFile("messages.jsonl").apply { writeText(lines.joinToString("\n") + "\n") }

    private fun rows(file: File): List<JsonObject> =
        file.readLines().filter { it.isNotBlank() }.map { json.parseToJsonElement(it).jsonObject }

    private fun flatImageRow(data: String) =
        """{"id":"u1","role":"user","content":[{"type":"text","text":"look"},{"type":"image","mimeType":"image/jpeg","data":"$data"}]}"""

    private fun nestedImageRow(data: String) =
        """{"id":"u2","role":"user","content":[{"type":"image","source":{"type":"base64","media_type":"image/png","data":"$data"}}]}"""

    @Test
    fun `flat image data is stripped to empty with stripped marker`() {
        val file = write(flatImageRow("QUJDREVGR0g="))
        val report = stripper.stripTranscript(file)
        assertTrue(report.stripped)
        assertEquals(1, report.partsStripped)
        assertEquals("QUJDREVGR0g=".length, report.bytesFreed)

        val img = rows(file).single()["content"]!!.jsonArray
            .map { it.jsonObject }.single { it["type"]?.jsonPrimitive?.content == "image" }
        assertEquals("", img["data"]!!.jsonPrimitive.content)
        assertEquals("true", img["stripped"]!!.jsonPrimitive.content)
        // mimeType preserved
        assertEquals("image/jpeg", img["mimeType"]!!.jsonPrimitive.content)
    }

    @Test
    fun `nested source data is stripped`() {
        val file = write(nestedImageRow("SEVMTE8="))
        val report = stripper.stripTranscript(file)
        assertEquals(1, report.partsStripped)
        val img = rows(file).single()["content"]!!.jsonArray
            .map { it.jsonObject }.single { it["type"]?.jsonPrimitive?.content == "image" }
        assertEquals("", img["source"]!!.jsonObject["data"]!!.jsonPrimitive.content)
        assertEquals("base64", img["source"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("true", img["stripped"]!!.jsonPrimitive.content)
    }

    @Test
    fun `text-only content is left untouched`() {
        val line = """{"id":"u1","role":"user","content":[{"type":"text","text":"hi"}]}"""
        val file = write(line)
        val before = file.readText()
        val report = stripper.stripTranscript(file)
        assertFalse(report.stripped)
        assertEquals(before, file.readText())
    }

    @Test
    fun `already-stripped image is a no-op`() {
        val file = write(
            """{"id":"u1","role":"user","content":[{"type":"image","mimeType":"image/jpeg","data":"","stripped":true}]}""",
        )
        val before = file.readText()
        val report = stripper.stripTranscript(file)
        assertFalse(report.stripped)
        assertEquals(before, file.readText())
    }

    @Test
    fun `idempotent - second pass is a no-op`() {
        val file = write(flatImageRow("QUJDRA=="))
        assertTrue(stripper.stripTranscript(file).stripped)
        val after = file.readText()
        assertFalse(stripper.stripTranscript(file).stripped)
        assertEquals(after, file.readText())
    }

    @Test
    fun `multiple images across rows all stripped, non-image rows preserved`() {
        val userText = """{"id":"u0","role":"user","content":[{"type":"text","text":"q"}]}"""
        val file = write(userText, flatImageRow("QUFB"), nestedImageRow("QkJC"))
        val report = stripper.stripTranscript(file)
        assertEquals(2, report.partsStripped)
        val all = rows(file)
        assertEquals(3, all.size)
        // first row unchanged
        assertEquals("q", all[0]["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
    }
}
