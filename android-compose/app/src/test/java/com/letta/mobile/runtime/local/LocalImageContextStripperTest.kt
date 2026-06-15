package com.letta.mobile.runtime.local

import java.io.File
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalImageContextStripperTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }
    private val stripper = LocalImageContextStripper(blobStore = null, json = json)

    private fun write(vararg lines: String): File =
        tempFolder.newFile("messages.jsonl").apply { writeText(lines.joinToString("\n") + "\n") }

    private fun rows(file: File): List<JsonObject> =
        file.readLines().filter { it.isNotBlank() }.map { json.parseToJsonElement(it).jsonObject }

    private fun flatImageRow(data: String) =
        """{"id":"u1","role":"user","content":[{"type":"text","text":"look"},{"type":"image","mimeType":"image/jpeg","data":"$data"}]}"""

    private fun nestedImageRow(data: String) =
        """{"id":"u2","role":"user","content":[{"type":"image","source":{"type":"base64","media_type":"image/png","data":"$data"}}]}"""

    private fun assistantRow(id: String = "a1", text: String = "ok") =
        """{"id":"$id","role":"assistant","content":[{"type":"text","text":"$text"}]}"""

    // ── existing tests, updated for "latest-image-preserved" behaviour ──────────

    @Test
    fun `flat image data is not stripped when it is the only (latest) image`() {
        // A single image-bearing user row is the latest by definition → preserved.
        val data = "QUJDREVGR0g="
        val file = write(flatImageRow(data))
        val report = stripper.stripTranscript(file)

        // Nothing was stripped — the lone latest image is kept.
        assertFalse(report.stripped)
        assertEquals(0, report.partsStripped)
        assertEquals(0, report.bytesFreed)

        val row = rows(file).single()
        val parts = row["content"]!!.jsonArray.map { it.jsonObject }
        assertTrue("image part should still be present", parts.any { it["type"]?.jsonPrimitive?.content == "image" })
        val imagePart = parts.single { it["type"]?.jsonPrimitive?.content == "image" }
        assertEquals(data, imagePart["data"]!!.jsonPrimitive.content)
    }

    @Test
    fun `nested source data is not stripped when it is the only (latest) image`() {
        val data = "SEVMTE8="
        val file = write(nestedImageRow(data))
        val report = stripper.stripTranscript(file)

        assertFalse(report.stripped)
        assertEquals(0, report.partsStripped)

        val row = rows(file).single()
        val parts = row["content"]!!.jsonArray.map { it.jsonObject }
        assertTrue("image part should still be present", parts.any { it["type"]?.jsonPrimitive?.content == "image" })
        val imagePart = parts.single { it["type"]?.jsonPrimitive?.content == "image" }
        assertEquals(data, (imagePart["source"]!!.jsonObject)["data"]!!.jsonPrimitive.content)
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
            """{"id":"u1","role":"user","content":[{"type":"text","text":"[image omitted from context: image/jpeg]","stripped":true}]}""",
        )
        val before = file.readText()
        val report = stripper.stripTranscript(file)
        assertFalse(report.stripped)
        assertEquals(before, file.readText())
    }

    @Test
    fun `idempotent - second pass is a no-op`() {
        // Need at least two image rows so the first gets stripped (the second is latest → preserved).
        val file = write(flatImageRow("QUJDRA=="), nestedImageRow("SEVMTE8="))
        assertTrue(stripper.stripTranscript(file).stripped)
        val after = file.readText()
        assertFalse(stripper.stripTranscript(file).stripped)
        assertEquals(after, file.readText())
    }

    @Test
    fun `older images across rows are stripped when a newer image row exists`() {
        val userText = """{"id":"u0","role":"user","content":[{"type":"text","text":"q"}]}"""
        // flatImageRow (QUFB) is older; nestedImageRow (QkJC) is the latest → preserved.
        val file = write(userText, flatImageRow("QUFB"), nestedImageRow("QkJC"))
        val report = stripper.stripTranscript(file)
        assertEquals(1, report.partsStripped) // only the older flat image
        val all = rows(file)
        assertEquals(3, all.size)

        // first row unchanged (text-only)
        assertEquals("q", all[0]["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)

        // second row (older image): image part stripped to text placeholder
        val row1Parts = all[1]["content"]!!.jsonArray.map { it.jsonObject }
        assertTrue("older image should be stripped", row1Parts.none { it["type"]?.jsonPrimitive?.content == "image" })
        val placeholder = row1Parts.single { it["stripped"]?.jsonPrimitive?.content == "true" }
        assertEquals("text", placeholder["type"]!!.jsonPrimitive.content)
        assertTrue(placeholder["text"]!!.jsonPrimitive.content.contains("image/jpeg"))

        // third row (latest image): preserved unchanged
        val row2Parts = all[2]["content"]!!.jsonArray.map { it.jsonObject }
        val imagePart = row2Parts.single { it["type"]?.jsonPrimitive?.content == "image" }
        assertEquals("QkJC", (imagePart["source"]!!.jsonObject)["data"]!!.jsonPrimitive.content)
    }

    // ── new tests for latest-image-preservation behaviour ────────────────────────

    @Test
    fun `latest image-bearing user message is preserved with base64 intact`() {
        val latestData = "TEFURVNUX0JBU0U2NA=="
        val olderData = "T0xERVJfQkFTRTY0"
        val file = write(
            flatImageRow(olderData),
            assistantRow(),
            flatImageRow(latestData),
        )
        val report = stripper.stripTranscript(file)

        // One older image stripped, latest preserved.
        assertEquals(1, report.partsStripped)
        assertTrue(report.stripped)

        val persisted = file.readText()
        assertFalse("older base64 should be gone", persisted.contains(olderData))
        assertTrue("latest base64 should be preserved", persisted.contains(latestData))
        assertTrue("older image placeholder should be present", persisted.contains("[image omitted from context"))
    }

    @Test
    fun `older image-bearing user message is stripped when a newer image row exists`() {
        val olderData = "QUJDREVGR0hJSktMTU5PUA=="
        val latestData = "WllYV1ZVVFRTUQ=="
        val file = write(
            nestedImageRow(olderData),
            assistantRow(),
            nestedImageRow(latestData),
        )
        val report = stripper.stripTranscript(file)

        assertEquals(1, report.partsStripped)
        assertTrue(report.stripped)

        val persisted = file.readText()
        assertFalse("older base64 should be gone", persisted.contains(olderData))
        assertTrue("latest base64 should be preserved", persisted.contains(latestData))
    }

    @Test
    fun `only the single latest image is preserved when there are three image rows`() {
        val d1 = "RklSU1RfSU1BR0U="
        val d2 = "U0VDT05EX0lNQUdF"
        val d3 = "VEhJUkRfSU1BR0U="
        val file = write(
            flatImageRow(d1),
            assistantRow("a1", "reply1"),
            flatImageRow(d2),
            assistantRow("a2", "reply2"),
            nestedImageRow(d3),
        )
        val report = stripper.stripTranscript(file)

        assertEquals(2, report.partsStripped) // d1 and d2 stripped

        val persisted = file.readText()
        assertFalse(persisted.contains(d1))
        assertFalse(persisted.contains(d2))
        assertTrue(persisted.contains(d3))
    }

    @Test
    fun `assistant image rows are always stripped even if they are the last image`() {
        // An assistant-authored image row should NOT block stripping — only user images
        // get the latest-is-preserved carve-out.
        val assistantImage = """{"id":"a99","role":"assistant","content":[{"type":"image","mimeType":"image/png","data":"QVNTU1RfSU1BRw=="}]}"""
        val file = write(assistantImage)
        val report = stripper.stripTranscript(file)

        // Assistant image must still be stripped.
        assertEquals(1, report.partsStripped)
        assertTrue(report.stripped)
        val persisted = file.readText()
        assertFalse(persisted.contains("QVNTU1RfSU1BRw=="))
    }

    @Test
    fun `all rows stripped when there is no user image row`() {
        // No user image rows exist, so latestImageUserIndex is -1 and every image gets stripped.
        val d1 = "Tk9fVVNFUl8x"
        val d2 = "Tk9fVVNFUl8y"
        val file = write(
            """{"id":"s1","role":"system","content":[{"type":"image","mimeType":"image/png","data":"$d1"}]}""",
            """{"id":"a1","role":"assistant","content":[{"type":"image","mimeType":"image/jpeg","data":"$d2"}]}""",
        )
        val report = stripper.stripTranscript(file)
        assertEquals(2, report.partsStripped)
        val persisted = file.readText()
        assertFalse(persisted.contains(d1))
        assertFalse(persisted.contains(d2))
    }

    // ── tests for blob-store integration (image_ref persistence) ─────────────────

    @Test
    fun `older image persisted to blob store and becomes image_ref`() {
        val blobStore = LocalImageBlobStore(tempFolder.root)
        val stripperWithBlobs = LocalImageContextStripper(blobStore = blobStore, json = json)

        val olderData = Base64.getEncoder().encodeToString("older image bytes".toByteArray())
        val latestData = Base64.getEncoder().encodeToString("latest image bytes".toByteArray())
        val file = write(flatImageRow(olderData), flatImageRow(latestData))

        val report = stripperWithBlobs.stripTranscript(file)

        assertEquals(1, report.partsStripped)
        assertTrue(report.stripped)

        val all = rows(file)
        assertEquals(2, all.size)

        // Older image becomes a TEXT placeholder carrying an image_ref pointer
        // (NOT a type:image_ref part — letta.js replays this file to the
        // provider and must only ever see a sendable text part).
        val olderParts = all[0]["content"]!!.jsonArray.map { it.jsonObject }
        val imageRefPart = olderParts.find {
            it["type"]?.jsonPrimitive?.content == "text" && it["image_ref"] != null
        }
        assertNotNull("older image should become text placeholder with image_ref", imageRefPart)
        val ref = imageRefPart!!["image_ref"]?.jsonPrimitive?.content
        assertNotNull("placeholder should carry an image_ref field", ref)
        assertTrue("ref should start with sha256:", ref!!.startsWith("sha256:"))
        assertEquals("image/jpeg", imageRefPart["mediaType"]?.jsonPrimitive?.content)
        // strict-provider safety: it is a text part, never an image part.
        assertEquals("text", imageRefPart["type"]?.jsonPrimitive?.content)

        // Blob should exist in store
        assertTrue("blob should exist in store", blobStore.has(ref))
        val retrievedBytes = blobStore.getBytes(ref)
        assertNotNull("blob bytes should be retrievable", retrievedBytes)

        // Latest image should still be inline
        val latestParts = all[1]["content"]!!.jsonArray.map { it.jsonObject }
        val latestImagePart = latestParts.find { it["type"]?.jsonPrimitive?.content == "image" }
        assertNotNull("latest image should still be inline", latestImagePart)
        assertEquals(latestData, latestImagePart!!["data"]?.jsonPrimitive?.content)
    }

    @Test
    fun `image_ref parts from previous pass are left untouched - idempotent`() {
        val blobStore = LocalImageBlobStore(tempFolder.root)
        val stripperWithBlobs = LocalImageContextStripper(blobStore = blobStore, json = json)

        val olderData = Base64.getEncoder().encodeToString("old".toByteArray())
        val latestData = Base64.getEncoder().encodeToString("new".toByteArray())
        val file = write(flatImageRow(olderData), flatImageRow(latestData))

        // First pass: older image becomes image_ref
        stripperWithBlobs.stripTranscript(file)
        val afterFirst = file.readText()

        // Second pass: should be no-op
        val report2 = stripperWithBlobs.stripTranscript(file)
        assertFalse("second pass should be no-op", report2.stripped)
        assertEquals("file should be unchanged", afterFirst, file.readText())
    }

    @Test
    fun `fallback to text placeholder when blobStore is null`() {
        val stripperNoBlobs = LocalImageContextStripper(blobStore = null, json = json)
        val olderData = Base64.getEncoder().encodeToString("fallback test".toByteArray())
        val latestData = Base64.getEncoder().encodeToString("latest".toByteArray())
        val file = write(flatImageRow(olderData), flatImageRow(latestData))

        val report = stripperNoBlobs.stripTranscript(file)
        assertEquals(1, report.partsStripped)

        val all = rows(file)
        val olderParts = all[0]["content"]!!.jsonArray.map { it.jsonObject }

        // Should be text placeholder, NOT image_ref
        val placeholderPart = olderParts.find { 
            it["type"]?.jsonPrimitive?.content == "text" && 
            it["text"]?.jsonPrimitive?.content?.contains("image omitted") == true 
        }
        assertNotNull("should have text placeholder", placeholderPart)
        assertEquals("true", placeholderPart!!["stripped"]?.jsonPrimitive?.content)
        assertTrue("text should mention image", placeholderPart["text"]?.jsonPrimitive?.content?.contains("image") == true)

        // With no blob store, the placeholder carries NO image_ref pointer.
        assertTrue("should not carry image_ref when blobStore is null", placeholderPart["image_ref"] == null)
    }

    @Test
    fun `nested source image persisted to blob store correctly`() {
        val blobStore = LocalImageBlobStore(tempFolder.root)
        val stripperWithBlobs = LocalImageContextStripper(blobStore = blobStore, json = json)

        val olderData = Base64.getEncoder().encodeToString("nested older".toByteArray())
        val latestData = Base64.getEncoder().encodeToString("nested latest".toByteArray())
        val file = write(nestedImageRow(olderData), nestedImageRow(latestData))

        val report = stripperWithBlobs.stripTranscript(file)
        assertEquals(1, report.partsStripped)

        val all = rows(file)
        val olderParts = all[0]["content"]!!.jsonArray.map { it.jsonObject }
        val imageRefPart = olderParts.find {
            it["type"]?.jsonPrimitive?.content == "text" && it["image_ref"] != null
        }
        assertNotNull("nested image should become text placeholder with image_ref", imageRefPart)
        assertEquals("image/png", imageRefPart!!["mediaType"]?.jsonPrimitive?.content)

        val ref = imageRefPart["image_ref"]?.jsonPrimitive?.content
        assertTrue("blob should be retrievable", blobStore.has(ref!!))
    }

    @Test
    fun `no empty image_url ever emitted - strict provider safety`() {
        val blobStore = LocalImageBlobStore(tempFolder.root)
        val stripperWithBlobs = LocalImageContextStripper(blobStore = blobStore, json = json)

        val data = Base64.getEncoder().encodeToString("test".toByteArray())
        val file = write(flatImageRow(data), flatImageRow(data))

        stripperWithBlobs.stripTranscript(file)
        val persisted = file.readText()

        // Parse all parts and verify no image part has empty/missing data
        val all = rows(file)
        for (row in all) {
            val parts = row["content"]?.jsonArray?.map { it.jsonObject } ?: continue
            for (part in parts) {
                val type = part["type"]?.jsonPrimitive?.content
                if (type == "image") {
                    // If it's an image type, it must have data (not empty)
                    val data = part["data"]?.jsonPrimitive?.content
                    val sourceData = (part["source"] as? JsonObject)?.get("data")?.jsonPrimitive?.content
                    assertTrue("image parts must have data", data?.isNotEmpty() == true || sourceData?.isNotEmpty() == true)
                }
            }
        }

        // Should only have image_ref (for older) or image (for latest), never empty image
        assertFalse("should not contain empty image shell", persisted.contains(""""type":"image","data":""""))
        assertFalse("should not contain image with empty source", persisted.contains(""""type":"image","source":{}"""))
    }
}
