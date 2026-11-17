package com.letta.mobile.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression: tool returns are arbitrary JSON. Attachment extraction walks
 * every nested value and previously read `obj["type"].jsonPrimitive`, which
 * THREW IllegalArgumentException when a `type` field was itself an object
 * (e.g. a router/stats tool return). That crashed the timeline reducer and
 * killed the app mid-stream. Extraction must tolerate non-primitive shapes.
 */
class ToolReturnAttachmentsCommonTest {
    private fun parse(json: String): JsonElement = Json.parseToJsonElement(json)

    @Test
    fun flatImageWithoutMimeRetainsAvailableBytes() {
        val png = "iVBORw0KGgoAAAANSUhEUgAAAAIAAAABCAYAAAD0In+KAAAADUlEQVR42mNk+M9QDwADjgGAv7SktwAAAABJRU5ErkJggg=="
        val image = ToolReturnMessage(
            id = "flat-without-mime", toolReturnRaw = parse("""{"type":"image","data":"$png"}"""),
        ).attachments.single()
        assertEquals(png, image.base64, "MIME metadata is optional when usable inline bytes exist")
        assertEquals("image/unknown", image.mediaType)
        kotlin.test.assertNull(image.unavailableReason)
    }

    @Test
    fun unavailableImagesHaveDistinctTypedReasons() {
        val cases = listOf(
            "{\"type\":\"image\"}" to "Missing",
            "{\"type\":\"image\",\"unavailable_reason\":\"too_large\"}" to "TooLarge",
            "{\"type\":\"image\",\"source\":{\"type\":\"image_ref\",\"id\":\"opaque\"}}" to "UnsupportedSource",
        )
        cases.forEach { (wire, reason) ->
            val image = ToolReturnMessage(id = "unavailable", toolReturnRaw = parse(wire)).attachments.single()
            assertTrue(image.toString().contains("unavailableReason=$reason"), "image must carry typed $reason: $image")
        }
    }

    @Test
    fun loadableUrlIsRetainedForTheImageLoader() {
        val url = "https://example.com/image.png"
        val raw = parse("""{"type":"image","source":{"type":"url","url":"$url"}}""")
        val image = ToolReturnMessage(id = "url-image", toolReturnRaw = raw).attachments.single()
        assertEquals(url, image.toDataUrl(), "supported remote URL must reach the image loader")
        assertTrue(image.toString().contains("unavailableReason=null"))
    }

    @Test
    fun imageWithMissingPayloadSurvivesAsAFailurePlaceholder() {
        val message = ToolReturnMessage(
            id = "missing-image",
            toolReturnRaw = parse("""[{"type":"image","source":{"type":"base64","media_type":"image/png"}}]"""),
        )
        assertEquals(1, message.attachments.size, "a missing image must remain visible as a failed image")
        assertEquals("", message.attachments.single().base64)
        assertEquals("image/png", message.attachments.single().mediaType)
    }

    @Test
    fun imageWithoutMetadataSurvivesAsAFailurePlaceholder() {
        val message = ToolReturnMessage(id = "missing-metadata", toolReturnRaw = parse("""{"type":"image"}"""))
        assertEquals(1, message.attachments.size)
        assertEquals("", message.attachments.single().base64)
    }

    @Test
    fun `object-typed type field does not crash attachment extraction`() {
        // `type` is an object, not a primitive — the exact crash shape.
        val raw = parse(
            """
            {
              "connection": { "type": {}, "ip_address": "192.168.50.81" },
              "state": {},
              "description": { "type": { "nested": true }, "name": "router" }
            }
            """.trimIndent(),
        )
        val msg = ToolReturnMessage(id = "m1", toolReturnRaw = raw)
        // Must not throw, and there are no images in this payload.
        assertEquals(emptyList(), msg.attachments)
    }

    @Test
    fun `valid image part is still extracted through nested objects`() {
        val raw = parse(
            """
            {
              "wrapper": { "type": {},
                "items": [
                  { "type": "image", "source": { "type": "base64", "media_type": "image/png", "data": "AAAA" } }
                ]
              }
            }
            """.trimIndent(),
        )
        val msg = ToolReturnMessage(id = "m2", toolReturnRaw = raw)
        val imgs = msg.attachments
        assertEquals(1, imgs.size)
        assertEquals("image/png", imgs.first().mediaType)
        assertEquals("AAAA", imgs.first().base64)
    }

    @Test
    fun `array-typed type field is tolerated`() {
        val raw = parse("""{ "type": ["a","b"], "data": { "type": 5 } }""")
        val msg = ToolReturnMessage(id = "m3", toolReturnRaw = raw)
        assertTrue(msg.attachments.isEmpty())
    }
}
