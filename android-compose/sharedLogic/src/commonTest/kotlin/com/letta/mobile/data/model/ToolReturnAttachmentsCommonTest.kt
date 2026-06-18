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
