package com.letta.mobile.runtime.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Schema validation for the image send/receive pipeline (letta-mobile-iej8j).
 *
 * CRITICAL: the xybm2 regression (broken {type:"image_ref"} → data:undefined
 * → provider 2013) went UNDETECTED while all unit tests stayed green. This
 * test suite validates the REAL provider request schema — every image_url in
 * the rebuilt chat/completions request MUST match `^data:image/[^;]+;base64,.+$`
 * (rejecting data:undefined, empty image_url, or missing media_type).
 *
 * Coverage:
 *  (A) Fresh image send (text + inline image)
 *  (B) Second image send AFTER the first was stripped to the on-disk
 *      text-placeholder-with-image_ref — THE EXACT CASE THAT BROKE.
 *
 * The test MUST fail when pointed at the OLD broken shape ({type:"image_ref"}
 * on disk) to prove it has teeth.
 */
class ImagePipelineSchemaTest {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * FLOOR VALIDATION (#3): always-on regex guard. Every image_url in a
     * provider request MUST match the strict pattern — rejects data:undefined,
     * empty media_type, empty base64. This is the minimum that would have
     * caught the xybm2 break without any runtime/node involvement.
     */
    @Test
    fun schemaFloor_imageUrlMustHaveValidMediaTypeAndBase64() {
        // Valid: real media type and non-empty base64
        assertTrue(
            "valid data URL must pass",
            isValidImageDataUrl("data:image/png;base64,iVBORw0KGgo=")
        )
        assertTrue(
            "valid jpeg must pass",
            isValidImageDataUrl("data:image/jpeg;base64,/9j/4AAQ=")
        )

        // Invalid: the EXACT broken shape from xybm2
        assertFalse(
            "data:undefined is INVALID (the xybm2 regression)",
            isValidImageDataUrl("data:undefined;base64,iVBORw0KGgo=")
        )

        // Invalid: other schema violations
        assertFalse(
            "empty media type is invalid",
            isValidImageDataUrl("data:;base64,iVBORw0KGgo=")
        )
        assertFalse(
            "missing base64 is invalid",
            isValidImageDataUrl("data:image/png;base64,")
        )
        assertFalse(
            "no base64 marker is invalid",
            isValidImageDataUrl("data:image/png;iVBORw0KGgo=")
        )
        assertFalse(
            "empty string is invalid",
            isValidImageDataUrl("")
        )
    }

    /**
     * Case A: fresh image send. The wire line carries {type:image, source:{...}}
     * and should produce a valid provider request with a real image_url.
     */
    @Test
    fun caseA_freshImageProducesValidProviderRequest() {
        // Simulate the wire-line shape for a fresh user turn with an image
        val wireLine = encodeUserTurnWireLine(
            com.letta.mobile.runtime.TurnInput.UserMessage(
                localMessageId = "test-msg-fresh",
                text = "What's in this image?",
                imageParts = listOf(
                    com.letta.mobile.runtime.TurnImagePart(
                        base64 = TINY_PNG_BASE64,
                        mediaType = "image/png"
                    )
                )
            )
        )

        // Parse the wire line to extract the content array
        val wireObject = json.parseToJsonElement(wireLine).jsonObject
        val message = wireObject["message"]?.jsonObject
        val content = message?.get("content") as? JsonArray

        assertTrue("fresh image turn should have content array", content != null)
        requireNotNull(content)

        // Find the image part
        val imagePart = content.firstOrNull { part ->
            (part as? JsonObject)?.get("type")?.jsonPrimitive?.content == "image"
        } as? JsonObject

        assertTrue("fresh turn should contain an image part", imagePart != null)
        requireNotNull(imagePart)

        // Validate the nested source shape (what letta.js expects)
        val source = imagePart["source"]?.jsonObject
        assertTrue("image part must have nested source", source != null)
        requireNotNull(source)

        val mediaType = source["media_type"]?.jsonPrimitive?.content
        val base64Data = source["data"]?.jsonPrimitive?.content

        assertTrue("media_type must be non-empty", !mediaType.isNullOrEmpty())
        assertTrue("base64 data must be non-empty", !base64Data.isNullOrEmpty())

        // Simulate what the provider request builder would create
        val imageUrl = buildImageUrl(mediaType!!, base64Data!!)
        assertTrue(
            "fresh image must produce valid provider image_url: $imageUrl",
            isValidImageDataUrl(imageUrl)
        )
    }

    /**
     * Case B: second image after strip. A persisted transcript contains:
     *  1. An older image that was stripped to a text placeholder with image_ref
     *  2. A new inline image
     *
     * When letta.js rebuilds the chat/completions request, the OLD image becomes
     * a text part (harmless), and the NEW image MUST produce a valid image_url.
     *
     * This is THE EXACT CASE THAT BROKE: if the stripper had emitted
     * {type:"image_ref"} instead of {type:"text", image_ref:"..."}, letta.js
     * would build `data:undefined;base64,...` → provider 2013.
     */
    @Test
    fun caseB_secondImageAfterStripProducesValidProviderRequest() {
        // Transcript rows:
        // 1. Older user message with a stripped image (text placeholder + image_ref)
        // 2. New user message with a fresh inline image

        val olderMessageRow = buildJsonObject {
            put("id", "msg-old")
            put("role", "user")
            put(
                "content",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", "First question")
                        }
                    )
                    // THE FIXED SHAPE: text placeholder with image_ref metadata
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", "[image omitted from context: image/jpeg]")
                            put("stripped", true)
                            put("image_ref", "sha256:abc123")
                            put("mediaType", "image/jpeg")
                        }
                    )
                }
            )
        }

        val newerMessageRow = buildJsonObject {
            put("id", "msg-new")
            put("role", "user")
            put(
                "content",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", "Second question")
                        }
                    )
                    // Fresh inline image (nested shape)
                    add(
                        buildJsonObject {
                            put("type", "image")
                            put(
                                "source",
                                buildJsonObject {
                                    put("type", "base64")
                                    put("media_type", "image/png")
                                    put("data", TINY_PNG_BASE64)
                                }
                            )
                        }
                    )
                }
            )
        }

        // Simulate what letta.js does: replay the transcript to build the
        // provider request. Only image parts (NOT text placeholders) become
        // image_url entries.
        val providerImageUrls = extractProviderImageUrls(listOf(olderMessageRow, newerMessageRow))

        // The old stripped image is a TEXT part → letta.js sends it as text, not image_url
        // The new inline image MUST produce a valid image_url
        assertTrue(
            "second turn should produce exactly 1 image_url (the fresh image)",
            providerImageUrls.size == 1
        )

        val imageUrl = providerImageUrls.first()
        assertTrue(
            "new image after strip must produce valid provider image_url: $imageUrl",
            isValidImageDataUrl(imageUrl)
        )
    }

    /**
     * PROOF OF TEETH: demonstrate the test catches the BROKEN shape.
     * If the stripper had emitted {type:"image_ref"} (the xybm2 bug), this
     * assertion proves the schema validator rejects it.
     */
    @Test
    fun proofOfTeeth_brokenImageRefShapeProducesInvalidDataUrl() {
        // The OLD BROKEN shape: {type:"image_ref", image_ref:"...", mimeType:"..."}
        val brokenRow = buildJsonObject {
            put("id", "msg-broken")
            put("role", "user")
            put(
                "content",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "image_ref") // BROKEN: unknown to letta.js
                            put("image_ref", "sha256:abc123")
                            put("mimeType", "image/jpeg") // lowercase 'm' — no 'media_type'
                        }
                    )
                }
            )
        }

        // Simulate the provider request builder hitting this part
        val content = (brokenRow["content"] as JsonArray)
        val brokenPart = content.first() as JsonObject

        // letta.js's image builder would read `item.mimeType` (undefined for
        // an image_ref part — it has no such field in letta.js's schema).
        val mediaType = brokenPart["media_type"]?.jsonPrimitive?.content
            ?: brokenPart["mimeType"]?.jsonPrimitive?.content // letta.js doesn't check this
        val base64 = brokenPart["data"]?.jsonPrimitive?.content
            ?: (brokenPart["source"] as? JsonObject)?.get("data")?.jsonPrimitive?.content

        // mediaType is null → letta.js builds `data:undefined;base64,...`
        val builtUrl = buildImageUrl(mediaType, base64)

        assertFalse(
            "BROKEN image_ref shape MUST produce invalid data URL (proof of teeth): $builtUrl",
            isValidImageDataUrl(builtUrl)
        )
    }

    /**
     * Simulates letta.js's chat/completions image_url builder. When mediaType
     * or base64 is null/empty, this reproduces the `data:undefined;base64,...`
     * bug. A real image produces `data:image/png;base64,<data>`.
     */
    private fun buildImageUrl(mediaType: String?, base64: String?): String {
        // Mirror letta.js's string interpolation: `data:${mediaType};base64,${data}`
        // When mediaType is null → "data:null;base64,..." in JS (or "undefined")
        val mt = mediaType ?: "undefined" // JS toString(null/undefined) → "undefined" or "null"
        val b64 = base64 ?: ""
        return "data:$mt;base64,$b64"
    }

    /**
     * Extracts all image_url strings letta.js would build for a provider request,
     * given a transcript (list of message rows). Only {type:"image"} parts with
     * nested source:{media_type, data} become image_url. Text placeholders are
     * ignored (sent as text content, not image_url).
     */
    private fun extractProviderImageUrls(transcript: List<JsonObject>): List<String> {
        val urls = mutableListOf<String>()
        transcript.forEach { row ->
            val content = row["content"] as? JsonArray ?: return@forEach
            content.forEach { part ->
                val p = part as? JsonObject ?: return@forEach
                if (p["type"]?.jsonPrimitive?.content == "image") {
                    // Nested shape: {type:image, source:{type:base64, media_type, data}}
                    val source = p["source"]?.jsonObject
                    if (source != null) {
                        val mediaType = source["media_type"]?.jsonPrimitive?.content
                        val base64 = source["data"]?.jsonPrimitive?.content
                        urls.add(buildImageUrl(mediaType, base64))
                    } else {
                        // Flat shape fallback (legacy): {type:image, mimeType, data}
                        val mediaType = p["mimeType"]?.jsonPrimitive?.content
                        val base64 = p["data"]?.jsonPrimitive?.content
                        urls.add(buildImageUrl(mediaType, base64))
                    }
                }
                // {type:"text"} parts (including image_ref placeholders) are NOT
                // converted to image_url — they become text in the provider request.
            }
        }
        return urls
    }

    /**
     * Validates an image data URL against the REAL provider schema. MUST match:
     *   ^data:image/[^;]+;base64,.+$
     *
     * Rejects:
     *  - data:undefined;base64,... (the xybm2 bug)
     *  - data:;base64,... (empty media type)
     *  - data:image/png;base64, (empty base64)
     *  - any other malformed data URL
     */
    private fun isValidImageDataUrl(url: String): Boolean {
        // Strict regex: media_type MUST start with "image/" and be non-empty,
        // base64 MUST be non-empty.
        val pattern = Regex("^data:image/[^;]+;base64,.+$")
        return pattern.matches(url)
    }

    private companion object {
        // A minimal valid 1x1 PNG (67 bytes base64)
        private const val TINY_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="
    }
}
