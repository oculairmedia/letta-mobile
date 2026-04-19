package com.letta.mobile.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-format invariants for the multimodal content-parts JSON array sent to
 * the Letta server. These tests pin the exact shape the backend expects so
 * we don't accidentally break compatibility while refactoring.
 *
 * The server schema (verified against `/openapi.json` of Letta 0.16.7) defines
 * `LettaMessageContentUnion` as a discriminated union over `text`, `image`,
 * `tool_call`, `tool_return`, `reasoning`, `redacted_reasoning`,
 * `omitted_reasoning`. There is intentionally NO `image_url` variant — the
 * client must use `{type:"image", source:{type:"base64", media_type, data}}`.
 */
class MessageContentPartTest {

    @Test
    fun `text part serializes with type=text and text body`() {
        val arr = listOf<MessageContentPart>(MessageContentPart.Text("hello world")).toJsonArray()
        assertEquals(1, arr.size)
        val obj = arr.first().jsonObject
        assertEquals("text", obj["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("hello world", obj["text"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `image part serializes as Letta ImageContent with Base64Image source`() {
        val img = MessageContentPart.Image(base64 = "AAAA", mediaType = "image/jpeg")
        val arr = listOf<MessageContentPart>(img).toJsonArray()
        val obj = arr.first().jsonObject
        // Top-level discriminator must be "image" (NOT "image_url").
        assertEquals("image", obj["type"]?.jsonPrimitive?.contentOrNull)
        // No legacy field should be present.
        assertNull(obj["image_url"])
        // source.* must match Base64Image schema.
        val source = obj["source"]?.jsonObject
        assertNotNull("source must be present on image part", source)
        assertEquals("base64", source!!["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("image/jpeg", source["media_type"]?.jsonPrimitive?.contentOrNull)
        // data must be the RAW base64 — no `data:` URL prefix.
        assertEquals("AAAA", source["data"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `image part data field never carries a data URL prefix`() {
        val img = MessageContentPart.Image(base64 = "ZZZZ", mediaType = "image/png")
        val arr = listOf<MessageContentPart>(img).toJsonArray()
        val data = arr.first().jsonObject["source"]?.jsonObject
            ?.get("data")?.jsonPrimitive?.contentOrNull
        assertNotNull(data)
        assertTrue(
            "data must be raw base64 (no `data:` prefix)",
            data!!.startsWith("ZZZZ") && !data.startsWith("data:"),
        )
    }

    @Test
    fun `mixed parts preserve order text-then-image`() {
        val parts = listOf<MessageContentPart>(
            MessageContentPart.Text("look at this:"),
            MessageContentPart.Image(base64 = "BBBB", mediaType = "image/png"),
        )
        val arr = parts.toJsonArray()
        assertEquals(2, arr.size)
        assertEquals("text", arr[0].jsonObject["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("image", arr[1].jsonObject["type"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `buildContentParts drops blank text and keeps images`() {
        val img = MessageContentPart.Image(base64 = "Z", mediaType = "image/jpeg")
        val parts = buildContentParts(text = "   ", images = listOf(img))
        assertEquals(1, parts.size)
        assertTrue(parts.first() is MessageContentPart.Image)
    }

    @Test
    fun `buildContentParts keeps text first then images`() {
        val img = MessageContentPart.Image(base64 = "Z", mediaType = "image/jpeg")
        val parts = buildContentParts(text = "caption", images = listOf(img))
        assertEquals(2, parts.size)
        assertEquals("caption", (parts[0] as MessageContentPart.Text).text)
        assertTrue(parts[1] is MessageContentPart.Image)
    }

    @Test
    fun `serialized array round-trips through Json without throwing`() {
        val parts = listOf<MessageContentPart>(
            MessageContentPart.Text("hi"),
            MessageContentPart.Image(base64 = "AB+/=", mediaType = "image/png"),
        )
        val arr = parts.toJsonArray()
        // Re-parse to confirm valid JSON
        val decoded = Json.parseToJsonElement(arr.toString())
        assertEquals(2, (decoded as kotlinx.serialization.json.JsonArray).size)
    }

    @Test
    fun `image data URL helper still works for in-app rendering`() {
        // toDataUrl() is no longer on the wire, but in-app callers (Coil
        // AsyncImage) still depend on it. Particularly: `+`, `/`, `=` from
        // standard base64 alphabet are preserved verbatim.
        val payload = "abc+/=="
        val img = MessageContentPart.Image(base64 = payload, mediaType = "image/jpeg")
        assertEquals("data:image/jpeg;base64,abc+/==", img.toDataUrl())
    }

    @Test
    fun `user message extracts attachments from new Letta image shape`() {
        val content = buildJsonArray {
            add(buildJsonObject {
                put("type", JsonPrimitive("text"))
                put("text", JsonPrimitive("caption"))
            })
            add(buildJsonObject {
                put("type", JsonPrimitive("image"))
                put("source", buildJsonObject {
                    put("type", JsonPrimitive("base64"))
                    put("media_type", JsonPrimitive("image/png"))
                    put("data", JsonPrimitive("ABCD+/=="))
                })
            })
        }

        val message = UserMessage(id = "u1", contentRaw = content)

        assertEquals("caption", message.content)
        assertEquals(1, message.attachments.size)
        assertEquals("image/png", message.attachments.first().mediaType)
        assertEquals("ABCD+/==", message.attachments.first().base64)
    }

    @Test
    fun `user message still extracts attachments from legacy image_url shape`() {
        // Backward-compat: anything previously persisted under the old shape
        // must still render correctly.
        val content = buildJsonArray {
            add(buildJsonObject {
                put("type", JsonPrimitive("text"))
                put("text", JsonPrimitive("caption"))
            })
            add(buildJsonObject {
                put("type", JsonPrimitive("image_url"))
                put("image_url", buildJsonObject {
                    put("url", JsonPrimitive("data:image/png;base64,LEGACY+/=="))
                })
            })
        }

        val message = UserMessage(id = "u1-legacy", contentRaw = content)

        assertEquals("caption", message.content)
        assertEquals(1, message.attachments.size)
        assertEquals("image/png", message.attachments.first().mediaType)
        assertEquals("LEGACY+/==", message.attachments.first().base64)
    }

    @Test
    fun `non data url image parts are ignored`() {
        // A bare https URL on the legacy shape is not decodable — drop it.
        val content = buildJsonArray {
            add(buildJsonObject {
                put("type", JsonPrimitive("image_url"))
                put("image_url", buildJsonObject {
                    put("url", JsonPrimitive("https://example.com/image.png"))
                })
            })
        }

        val message = AssistantMessage(id = "a1", contentRaw = content)

        assertTrue(message.attachments.isEmpty())
    }

    @Test
    fun `letta image source with inline data is decoded as attachment`() {
        // letta-mobile-mge5.24: the server returns persisted user image
        // messages as `source.type=letta` but ALWAYS inlines the base64
        // payload under `data`. Treat that exactly like `source.type=base64`
        // so history hydration preserves the image bubble.
        val content = buildJsonArray {
            add(buildJsonObject {
                put("type", JsonPrimitive("image"))
                put("source", buildJsonObject {
                    put("type", JsonPrimitive("letta"))
                    put("file_id", JsonPrimitive("file-abc"))
                    put("media_type", JsonPrimitive("image/jpeg"))
                    put("data", JsonPrimitive("LETTA_INLINE+/=="))
                })
            })
        }

        val message = UserMessage(id = "u-letta", contentRaw = content)

        assertEquals(1, message.attachments.size)
        assertEquals("image/jpeg", message.attachments.first().mediaType)
        assertEquals("LETTA_INLINE+/==", message.attachments.first().base64)
    }

    @Test
    fun `letta image source without inline data is dropped`() {
        // A pure file-reference (no `data` field) can't be rendered inline —
        // the caller would need to fetch via /v1/files. Until that path
        // exists, drop it silently so we don't render a broken placeholder.
        val content = buildJsonArray {
            add(buildJsonObject {
                put("type", JsonPrimitive("image"))
                put("source", buildJsonObject {
                    put("type", JsonPrimitive("letta"))
                    put("file_id", JsonPrimitive("file-abc"))
                    put("media_type", JsonPrimitive("image/jpeg"))
                })
            })
        }

        val message = AssistantMessage(id = "a-letta", contentRaw = content)

        assertTrue(message.attachments.isEmpty())
    }

    @Test
    fun `unused imports stay tree-shakeable`() {
        // Smoke: make sure a JsonObject/JsonPrimitive import isn't elided.
        @Suppress("UNUSED_VARIABLE")
        val probe: JsonObject = JsonObject(mapOf("x" to JsonPrimitive(1)))
        assertNotNull(probe)
    }
}
